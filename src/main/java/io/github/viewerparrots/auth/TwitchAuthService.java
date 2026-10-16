package io.github.viewerparrots.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Public-client OAuth work and secret storage run only on this service's worker. */
public final class TwitchAuthService implements AutoCloseable {
    public enum State { IDLE, VALIDATING, WAITING_AUTHORIZATION, AUTHENTICATED, REFRESHING, NEEDS_LOGIN, ERROR }

    /** This is safe for screens/logs; credentials and raw OAuth errors never enter it. */
    public record Status(State state, String login, String clientId, long expiresAtEpochSecond,
                         String userCode, String verificationUri, String message, boolean hasCredentials) { }

    @FunctionalInterface
    interface OAuthTransport extends AutoCloseable {
        OAuthResponse request(String method, String endpoint, Map<String, String> form, String accessToken)
                throws IOException, InterruptedException;
        @Override default void close() { }
    }

    record OAuthResponse(int status, JsonObject data) { }
    private enum Kind { STARTUP, LOGIN, VALIDATE, REFRESH, LOGOUT }
    @FunctionalInterface private interface Work { void run(Flight flight) throws Exception; }
    private static final String SESSION_NAME = "twitch-session";
    private static final String SCOPES = "chat:read";
    private static final long REFRESH_MARGIN_SECONDS = 300;
    private static final long VALIDATE_INTERVAL_SECONDS = 3600;
    private static final int MAX_SECRET_LENGTH = 16 * 1024;

    private final Object lock = new Object();
    private final Path configDirectory;
    private final LocalSecretStore secrets;
    private final Consumer<String> onAccessChanged;
    private final OAuthTransport transport;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private volatile Status status = new Status(State.IDLE, "", "", 0, "", "", "Вход Twitch не выполнен.", false);
    private volatile String configuredClientId = "";
    private volatile String expectedLogin = "";
    private volatile Session session;
    private volatile boolean needsLogin;
    private volatile boolean pendingValidation;
    private boolean loaded;
    private boolean loadFailed;
    private boolean closed;
    private long generation;
    private long validatedAt;
    private long retryNotBefore;
    private String publishedAccess = "";
    private String lastRejectedAccess = "";
    private Flight flight;
    private ScheduledFuture<?> automatic;

    public TwitchAuthService(Path configDir, Consumer<String> onAccessChanged) {
        this(configDir, onAccessChanged, new HttpOAuthTransport(), Clock.systemUTC(), new LocalSecretStore(configDir));
    }

    TwitchAuthService(Path configDir, Consumer<String> onAccessChanged, OAuthTransport transport,
                      Clock clock, LocalSecretStore secrets) {
        this.configDirectory = Objects.requireNonNull(configDir, "configDir");
        this.onAccessChanged = Objects.requireNonNull(onAccessChanged, "onAccessChanged");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "viewer-parrots-twitch-auth");
            thread.setDaemon(true);
            return thread;
        });
        synchronized (lock) { startLocked(Kind.STARTUP, this::initialize); }
    }

    public Status status() { return status; }

    /** Client ID is public configuration. Blank derives the ID from an existing token. */
    public void configure(String clientId, String expectedLogin) {
        synchronized (lock) {
            configuredClientId = clean(clientId);
            this.expectedLogin = clean(expectedLogin).toLowerCase(Locale.ROOT);
            if (flight == null) setStatus(status.state(), "", "", status.message());
        }
    }

    public CompletableFuture<Status> login(String clientId, String expectedLogin) {
        configure(clientId, expectedLogin);
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(status);
            if (flight != null && flight.kind == Kind.STARTUP) {
                return flight.result.thenCompose(ignored -> login(clientId, expectedLogin));
            }
            if (flight != null) return flight.result;
            String id = effectiveClientId();
            if (!validClientId(id) || !validExpectedLogin()) {
                setStatus(State.NEEDS_LOGIN, "", "", "Укажите Client ID публичного Twitch-приложения и корректный логин.");
                return CompletableFuture.completedFuture(status);
            }
            return startLocked(Kind.LOGIN, op -> deviceLogin(op, id)).result;
        }
    }

    public CompletableFuture<Status> refresh() {
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(status);
            if (flight != null && flight.kind == Kind.STARTUP) {
                return flight.result.thenCompose(ignored -> refresh());
            }
            if (flight != null) return flight.result;
            if (!loaded) return startLocked(Kind.STARTUP, this::initialize).result.thenCompose(ignored -> refresh());
            return startLocked(Kind.REFRESH, this::refreshSession).result;
        }
    }

    /** Called by the bridge after IRC AUTH_FAILED; one attempt for each rejected token. */
    public CompletableFuture<Status> authenticationFailed() {
        synchronized (lock) {
            if (closed || session == null || needsLogin || session.accessToken.equals(lastRejectedAccess)) {
                return CompletableFuture.completedFuture(status);
            }
            if (flight != null) return flight.result;
            lastRejectedAccess = session.accessToken;
            if (session.refreshToken.isEmpty()) {
                requireLogin("Токен Twitch больше не подходит. Войдите повторно.");
                return CompletableFuture.completedFuture(status);
            }
            return startLocked(Kind.REFRESH, this::refreshSession).result;
        }
    }

    /** Cancels only pending work; a previously working saved session remains available. */
    public void cancel() {
        Flight cancelled;
        Status cancelledStatus;
        synchronized (lock) {
            if (flight != null && flight.refreshing) {
                // Once a refresh is sent its old token may already be consumed.
                // Finish the rotation in the worker, but suppress its callback.
                flight.suppressPublish = true;
                return;
            }
            generation++;
            cancelled = flight;
            flight = null;
            cancelAutomatic();
            if (cancelled != null && cancelled.task != null) cancelled.task.cancel(true);
            setStatus(session == null ? State.IDLE : usable() ? State.AUTHENTICATED : State.NEEDS_LOGIN,
                    "", "", session == null ? "Вход отменён." : usable() ? "Вход Twitch выполнен." : "Войдите в Twitch повторно.");
            cancelledStatus = status;
            scheduleAutomatic();
        }
        if (cancelled != null) cancelled.result.complete(cancelledStatus);
    }

    public CompletableFuture<Status> logout() {
        cancel();
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(status);
            if (flight != null) return flight.result.handle((ignored, error) -> status).thenCompose(ignored -> logout());
            return startLocked(Kind.LOGOUT, op -> {
                checkCurrent(op);
                secrets.delete(SESSION_NAME);
                checkCurrent(op);
                Files.deleteIfExists(configDirectory.resolve("viewerparrots-twitch-token.txt"));
                synchronized (lock) {
                    checkCurrent(op);
                    session = null;
                    needsLogin = false;
                    loaded = true;
                    loadFailed = false;
                    pendingValidation = false;
                    validatedAt = 0;
                    lastRejectedAccess = "";
                    setStatus(State.IDLE, "", "", "Вход Twitch завершён. Доступно гостевое чтение.");
                }
                publishAccess(op, "");
            }).result;
        }
    }

    /** Returns an empty token only when no credentials exist, never for an expired grant. */
    public CompletableFuture<String> accessToken() {
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Сервис Twitch закрыт."));
            if (flight != null) return flight.result.thenCompose(ignored -> accessToken());
            if (!loaded) return startLocked(Kind.STARTUP, this::initialize).result.thenCompose(ignored -> accessToken());
            if (session == null) return loadFailed ? unavailableAccess() : CompletableFuture.completedFuture("");
            if (needsLogin) return unavailableAccess();
            if (pendingValidation) {
                if (now() < retryNotBefore) return unavailableAccess();
                return startLocked(Kind.VALIDATE, op -> validateExisting(op, false)).result
                        .thenCompose(ignored -> tokenAfterOperation());
            }
            if (!configurationMatches(session)) {
                requireLogin("Настройки аккаунта изменены. Войдите в Twitch повторно.");
                return unavailableAccess();
            }
            if (session.expiresAtEpochSecond <= now()) {
                if (session.refreshToken.isEmpty()) {
                    requireLogin("Старый токен Twitch истёк. Войдите для автоматического продления.");
                    return unavailableAccess();
                }
                // A failed automatic refresh must not recurse into an unbounded retry loop.
                if (now() < retryNotBefore) return unavailableAccess();
                return startLocked(Kind.REFRESH, this::refreshSession).result.thenCompose(ignored -> tokenAfterOperation());
            }
            if (now() - validatedAt >= VALIDATE_INTERVAL_SECONDS && now() >= retryNotBefore) {
                return startLocked(Kind.VALIDATE, op -> validateExisting(op, false)).result
                        .thenCompose(ignored -> tokenAfterOperation());
            }
            return CompletableFuture.completedFuture(session.accessToken);
        }
    }

    private CompletableFuture<String> tokenAfterOperation() {
        synchronized (lock) {
            return usable() ? CompletableFuture.completedFuture(session.accessToken) : unavailableAccess();
        }
    }

    private CompletableFuture<String> unavailableAccess() {
        return CompletableFuture.failedFuture(new IllegalStateException("Twitch требует повторного входа или повтора подключения."));
    }

    private void initialize(Flight op) throws Exception {
        String saved = secrets.read(SESSION_NAME);
        checkCurrent(op);
        Session imported = saved.isBlank() ? null : Session.decode(saved);
        boolean legacy = false;
        if (imported == null) {
            Path oldFile = configDirectory.resolve("viewerparrots-twitch-token.txt");
            if (Files.isRegularFile(oldFile)) {
                if (Files.size(oldFile) > MAX_SECRET_LENGTH) throw new IOException("Invalid local credentials.");
                String token = Files.readString(oldFile, StandardCharsets.UTF_8).strip();
                if (token.startsWith("oauth:")) token = token.substring(6).strip();
                if (!token.isEmpty()) {
                    if (!validAccessToken(token)) throw new IOException("Invalid local credentials.");
                    imported = new Session(token, "", "", "", "", 0);
                    legacy = true;
                }
            }
        }
        synchronized (lock) {
            checkCurrent(op);
            session = imported;
            loaded = true;
            loadFailed = false;
            if (session == null) {
                setStatus(State.IDLE, "", "", "Доступно гостевое чтение Twitch.");
                return;
            }
        }
        validateExisting(op, legacy);
    }

    private void validateExisting(Flight op, boolean importedLegacy) throws Exception {
        Session previous = session;
        if (previous == null) { requireLogin(op, "Сначала войдите в Twitch."); return; }
        if (!configurationMatches(previous)) { requireLogin(op, "Настройки аккаунта изменены. Войдите повторно."); return; }
        setCurrentStatus(op, State.VALIDATING, "", "", "Проверка входа Twitch…");
        OAuthResponse response = request(op, "Get", "validate", Map.of(), previous.accessToken);
        if (response.status == 401) {
            if (previous.refreshToken.isEmpty()) requireLogin(op, "Токен Twitch истёк. Войдите для автоматического продления.");
            else refreshSession(op);
            return;
        }
        if (response.status != 200) { failHttp(op, response.status); return; }
        Session validated = validated(previous.accessToken, previous.refreshToken, previous.clientId, response.data);
        if (validated == null) { requireLogin(op, "Аккаунт или разрешения Twitch не совпадают. Войдите повторно."); return; }
        accept(op, validated, importedLegacy);
        if (!validated.refreshToken.isEmpty() && validated.expiresAtEpochSecond - now() <= REFRESH_MARGIN_SECONDS) {
            refreshSession(op);
        }
    }

    private void deviceLogin(Flight op, String clientId) throws Exception {
        setCurrentStatus(op, State.VALIDATING, "", "", "Подготовка входа Twitch…");
        OAuthResponse device = request(op, "Post", "device", Map.of("client_id", clientId, "scopes", SCOPES), "");
        if (device.status != 200) { failHttp(op, device.status); return; }
        String deviceCode = string(device.data, "device_code");
        String userCode = string(device.data, "user_code");
        String verificationUri = string(device.data, "verification_uri");
        int interval = (int) number(device.data, "interval", 5);
        long expiresIn = number(device.data, "expires_in", 0);
        if (!validSecret(deviceCode) || !userCode.matches("[A-Za-z0-9-]{1,32}") || interval < 1 || expiresIn < 1
                || !validApprovalUri(verificationUri)) throw new IOException("Invalid device response.");
        setCurrentStatus(op, State.WAITING_AUTHORIZATION, userCode, verificationUri,
                "Откройте Twitch в браузере и подтвердите вход.");
        long deadline = now() + expiresIn;
        while (now() < deadline) {
            pause(op, Math.min(interval, Math.max(1, deadline - now())));
            if (now() >= deadline) break;
            OAuthResponse response = request(op, "Post", "token", Map.of("client_id", clientId,
                    "scopes", SCOPES, "device_code", deviceCode,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"), "");
            if (response.status == 200) { acceptTokenResponse(op, response.data, clientId, false); return; }
            String error = errorCode(response.data);
            if (error.equals("authorization_pending")) continue;
            if (error.equals("slow_down")) { interval = Math.min(300, interval + 5); continue; }
            setCurrentStatus(op, State.NEEDS_LOGIN, "", "", error.equals("access_denied")
                    ? "Вход в Twitch отклонён. Можно попробовать снова."
                    : "Код Twitch больше не действует. Нажмите «Войти» ещё раз.");
            return;
        }
        setCurrentStatus(op, State.NEEDS_LOGIN, "", "", "Код Twitch истёк. Нажмите «Войти» ещё раз.");
    }

    private void refreshSession(Flight op) throws Exception {
        Session previous = session;
        if (previous == null || previous.refreshToken.isEmpty()) {
            requireLogin(op, "Войдите в Twitch, чтобы включить автоматическое продление.");
            return;
        }
        if (!configurationMatches(previous) || !validClientId(previous.clientId)) {
            requireLogin(op, "Настройки аккаунта изменены. Войдите в Twitch повторно.");
            return;
        }
        synchronized (lock) { checkCurrent(op); op.refreshing = true; }
        setCurrentStatus(op, State.REFRESHING, "", "", "Продление доступа Twitch…");
        OAuthResponse response = request(op, "Post", "token", Map.of("client_id", previous.clientId,
                "grant_type", "refresh_token", "refresh_token", previous.refreshToken), "");
        if (response.status == 400 || response.status == 401 || response.status == 403) {
            requireLogin(op, "Доступ Twitch отозван или истёк. Войдите повторно.");
            return;
        }
        if (response.status != 200) { failHttp(op, response.status); return; }
        acceptTokenResponse(op, response.data, previous.clientId, true);
    }

    private void acceptTokenResponse(Flight op, JsonObject data, String clientId, boolean rotated) throws Exception {
        String access = string(data, "access_token");
        String refresh = string(data, "refresh_token");
        if (!validAccessToken(access) || !validSecret(refresh)) throw new IOException("Invalid token response.");
        if (rotated) {
            // Public refresh tokens are single-use. Preserve the replacement even
            // if the following validation endpoint is temporarily unavailable.
            Session previous = session;
            long lifetime = number(data, "expires_in", 0);
            if (previous == null || lifetime <= 0) throw new IOException("Invalid refresh response.");
            Session replacement = new Session(access, refresh, clientId, previous.login, previous.userId, now() + lifetime);
            checkCurrent(op);
            secrets.write(SESSION_NAME, replacement.encode());
            synchronized (lock) {
                checkCurrent(op);
                session = replacement;
                pendingValidation = true;
            }
        }
        OAuthResponse check = request(op, "Get", "validate", Map.of(), access);
        if (check.status != 200) {
            if (check.status == 401) requireLogin(op, "Новый доступ Twitch недействителен. Войдите повторно.");
            else failHttp(op, check.status);
            return;
        }
        Session next = validated(access, refresh, clientId, check.data);
        if (next == null) { requireLogin(op, "Аккаунт или разрешения Twitch не совпадают. Войдите повторно."); return; }
        accept(op, next, !rotated);
    }

    private Session validated(String access, String refresh, String originalClientId, JsonObject data) {
        String clientId = string(data, "client_id");
        String login = string(data, "login").toLowerCase(Locale.ROOT);
        String userId = string(data, "user_id");
        long expires = number(data, "expires_in", 0);
        JsonArray scopes = data.has("scopes") && data.get("scopes").isJsonArray() ? data.getAsJsonArray("scopes") : new JsonArray();
        boolean canRead = scopes.asList().stream().anyMatch(scope -> scope.isJsonPrimitive()
                && scope.getAsJsonPrimitive().isString() && scope.getAsString().equals(SCOPES));
        if (!validClientId(clientId) || !login.matches("[a-z0-9_]{1,25}") || !userId.matches("[0-9]+")
                || expires <= 0 || !canRead || !validExpectedLogin()
                || (!originalClientId.isEmpty() && !originalClientId.equals(clientId))
                || (!configuredClientId.isEmpty() && !configuredClientId.equals(clientId))
                || (!expectedLogin.isEmpty() && !expectedLogin.equals(login))) return null;
        return new Session(access, refresh, clientId, login, userId, now() + expires);
    }

    private void accept(Flight op, Session next, boolean persist) throws Exception {
        checkCurrent(op);
        if (persist) {
            secrets.write(SESSION_NAME, next.encode());
            checkCurrent(op);
            Files.deleteIfExists(configDirectory.resolve("viewerparrots-twitch-token.txt"));
        }
        synchronized (lock) {
            checkCurrent(op);
            session = next;
            needsLogin = false;
            pendingValidation = false;
            loadFailed = false;
            validatedAt = now();
            retryNotBefore = 0;
            setStatus(State.AUTHENTICATED, "", "", "Вход Twitch выполнен. Продление работает автоматически.");
        }
        publishAccess(op, next.accessToken);
    }

    private void publishAccess(Flight op, String token) throws InterruptedException {
        synchronized (lock) {
            checkCurrent(op);
            if (op.suppressPublish) return;
            if (publishedAccess.equals(token)) return;
            publishedAccess = token;
            // Internal consumers must only enqueue work; this short handoff is
            // serialized with cancel/logout so no obsolete callback starts later.
            try { onAccessChanged.accept(token); } catch (RuntimeException ignored) { }
        }
    }

    private OAuthResponse request(Flight op, String method, String endpoint, Map<String, String> form, String access)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            checkCurrent(op);
            try {
                OAuthResponse response = transport.request(method, endpoint, form, access);
                checkCurrent(op);
                if ((response.status == 429 || response.status >= 500) && attempt < 2) {
                    pause(op, 1L << attempt);
                    continue;
                }
                return response;
            } catch (IOException transientFailure) {
                checkCurrent(op);
                if (attempt >= 2) throw new IOException("Twitch network request failed.");
                pause(op, 1L << attempt);
            }
        }
    }

    private void pause(Flight op, long seconds) throws InterruptedException {
        long remaining = seconds;
        while (remaining > 0) {
            checkCurrent(op);
            long chunk = Math.min(30, remaining);
            TimeUnit.SECONDS.sleep(chunk);
            remaining -= chunk;
        }
        checkCurrent(op);
    }

    private Flight startLocked(Kind kind, Work work) {
        cancelAutomatic();
        Flight next = new Flight(++generation, kind);
        flight = next;
        next.task = worker.submit(() -> {
            try {
                work.run(next);
            } catch (InterruptedException | CancellationException cancelled) {
                Thread.currentThread().interrupt();
            } catch (Exception safeFailure) {
                synchronized (lock) {
                    if (current(next)) {
                        retryNotBefore = now() + 60;
                        setStatus(State.ERROR, "", "", "Не удалось связаться с Twitch или сохранить вход. Повторите попытку.");
                        if (next.kind == Kind.STARTUP && !loaded) { loaded = true; loadFailed = true; }
                    }
                }
            } finally {
                Status result;
                synchronized (lock) {
                    if (flight == next) {
                        flight = null;
                        scheduleAutomatic();
                    }
                    result = status;
                }
                next.result.complete(result);
            }
        });
        return next;
    }

    private void scheduleAutomatic() {
        cancelAutomatic();
        if (closed || flight != null || session == null || needsLogin) return;
        long delay = Math.max(1, validatedAt + VALIDATE_INTERVAL_SECONDS - now());
        if (!session.refreshToken.isEmpty()) {
            delay = Math.min(delay, Math.max(1, session.expiresAtEpochSecond - REFRESH_MARGIN_SECONDS - now()));
        } else {
            delay = Math.min(delay, Math.max(1, session.expiresAtEpochSecond - now()));
        }
        if (status.state() == State.ERROR || pendingValidation) delay = Math.min(delay, 60);
        delay = Math.max(delay, retryNotBefore - now());
        automatic = worker.schedule(() -> {
            synchronized (lock) {
                if (closed || flight != null || session == null || needsLogin) return;
                if (!session.refreshToken.isEmpty() && session.expiresAtEpochSecond - now() <= REFRESH_MARGIN_SECONDS) {
                    startLocked(Kind.REFRESH, this::refreshSession);
                } else {
                    startLocked(Kind.VALIDATE, op -> validateExisting(op, false));
                }
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelAutomatic() {
        if (automatic != null) { automatic.cancel(false); automatic = null; }
    }

    private void setCurrentStatus(Flight op, State state, String code, String uri, String message) throws InterruptedException {
        synchronized (lock) { checkCurrent(op); setStatus(state, code, uri, message); }
    }

    private void setStatus(State state, String code, String uri, String message) {
        Session current = session;
        status = new Status(state, current == null ? expectedLogin : current.login, effectiveClientId(),
                current == null ? 0 : current.expiresAtEpochSecond, code, uri, message, current != null || loadFailed);
    }

    private void requireLogin(Flight op, String message) throws InterruptedException {
        synchronized (lock) { checkCurrent(op); requireLogin(message); }
    }

    private void requireLogin(String message) {
        synchronized (lock) {
            needsLogin = true;
            cancelAutomatic();
            setStatus(State.NEEDS_LOGIN, "", "", message);
        }
    }

    private void failHttp(Flight op, int httpStatus) throws InterruptedException {
        synchronized (lock) {
            checkCurrent(op);
            retryNotBefore = now() + 60;
            setStatus(State.ERROR, "", "", httpStatus == 429 ? "Twitch просит подождать. Повторите чуть позже."
                    : "Twitch временно недоступен. Повторите попытку.");
        }
    }

    private boolean configurationMatches(Session value) {
        return validExpectedLogin() && (configuredClientId.isEmpty() || validClientId(configuredClientId))
                && (value.clientId.isEmpty() || configuredClientId.isEmpty() || configuredClientId.equals(value.clientId))
                && (value.login.isEmpty() || expectedLogin.isEmpty() || expectedLogin.equals(value.login));
    }

    private boolean validExpectedLogin() { return expectedLogin.isEmpty() || expectedLogin.matches("[a-z0-9_]{1,25}"); }
    private boolean usable() { return session != null && !needsLogin && !pendingValidation && session.expiresAtEpochSecond > now(); }
    private String effectiveClientId() { return !configuredClientId.isEmpty() ? configuredClientId : session == null ? "" : session.clientId; }
    private long now() { return clock.instant().getEpochSecond(); }
    private boolean current(Flight value) { return !closed && flight == value && generation == value.generation; }
    private void checkCurrent(Flight value) throws InterruptedException {
        synchronized (lock) { if (!current(value) || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    }

    @Override public void close() {
        Flight previous;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            previous = flight;
            flight = null;
            cancelAutomatic();
            if (previous != null && previous.task != null) previous.task.cancel(true);
        }
        worker.shutdownNow();
        transport.close();
        if (previous != null) previous.result.complete(status);
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static boolean validClientId(String value) { return value.matches("[A-Za-z0-9]{1,128}"); }
    private static boolean validAccessToken(String value) { return value.matches("[A-Za-z0-9_-]{1,4096}"); }
    private static boolean validSecret(String value) {
        return !value.isEmpty() && value.length() <= 4096 && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean validApprovalUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "www.twitch.tv".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && "/activate".equals(uri.getPath()) && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }
    private static String string(JsonObject data, String name) {
        return data.has(name) && data.get(name).isJsonPrimitive() && data.getAsJsonPrimitive(name).isString()
                ? data.get(name).getAsString() : "";
    }
    private static long number(JsonObject data, String name, long fallback) {
        try { return data.has(name) ? data.get(name).getAsLong() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static String errorCode(JsonObject data) {
        String code = string(data, "error");
        return switch (code) {
            case "authorization_pending", "slow_down", "access_denied", "expired_token" -> code;
            default -> string(data, "message");
        };
    }

    private static final class Flight {
        final long generation;
        final Kind kind;
        final CompletableFuture<Status> result = new CompletableFuture<>();
        Future<?> task;
        boolean refreshing;
        boolean suppressPublish;
        Flight(long generation, Kind kind) { this.generation = generation; this.kind = kind; }
    }

    private record Session(String accessToken, String refreshToken, String clientId, String login,
                           String userId, long expiresAtEpochSecond) {
        String encode() {
            JsonObject data = new JsonObject();
            data.addProperty("accessToken", accessToken);
            data.addProperty("refreshToken", refreshToken);
            data.addProperty("clientId", clientId);
            data.addProperty("login", login);
            data.addProperty("userId", userId);
            data.addProperty("expiresAtEpochSecond", expiresAtEpochSecond);
            return data.toString();
        }
        static Session decode(String json) throws IOException {
            try {
                JsonObject data = JsonParser.parseString(json).getAsJsonObject();
                String access = string(data, "accessToken"), refresh = string(data, "refreshToken");
                String client = string(data, "clientId"), login = string(data, "login"), userId = string(data, "userId");
                if (!validAccessToken(access) || (!refresh.isEmpty() && !validSecret(refresh))
                        || !validClientId(client) || !login.matches("[a-z0-9_]{1,25}") || !userId.matches("[0-9]+")) {
                    throw new IOException("Invalid local Twitch session.");
                }
                return new Session(access, refresh, client, login, userId, number(data, "expiresAtEpochSecond", 0));
            } catch (RuntimeException ignored) { throw new IOException("Invalid local Twitch session."); }
        }
        @Override public String toString() { return "TwitchSession[redacted]"; }
    }

    private static final class HttpOAuthTransport implements OAuthTransport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        @Override public OAuthResponse request(String method, String endpoint, Map<String, String> form, String access)
                throws IOException, InterruptedException {
            try {
                if (!endpoint.equals("device") && !endpoint.equals("token") && !endpoint.equals("validate")) {
                    throw new IOException("Invalid OAuth endpoint.");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/" + endpoint))
                        .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
                if (method.equals("Post")) {
                    String body = form.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
                    request.header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                } else {
                    request.header("Authorization", "OAuth " + access).GET();
                }
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.body().length() > 65536) throw new IOException("OAuth response too large.");
                return new OAuthResponse(response.statusCode(), JsonParser.parseString(response.body()).getAsJsonObject());
            } catch (RuntimeException malformed) { throw new IOException("Invalid Twitch response."); }
        }
        @Override public void close() { client.shutdownNow(); }
    }
}
