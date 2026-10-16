package io.github.viewerparrots.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TwitchAuthServiceTest {
    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    @Test
    void refreshPersistsRotatedPairBeforePublishingAccess(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        AtomicReference<String> persistedAtCallback = new AtomicReference<>();
        AtomicReference<Map<String, String>> sentForm = new AtomicReference<>();
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("validate")) return validated(access, "demo", "publicclient", 3600);
            sentForm.set(Map.copyOf(form));
            return tokens("next-access", "next-refresh");
        };
        try (TwitchAuthService service = service(directory, store, transport, access -> {
            if (access.equals("next-access")) {
                try { persistedAtCallback.set(store.read("twitch-session")); }
                catch (IOException error) { throw new AssertionError(error); }
            }
        })) {
            assertEquals("old-access", service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals(TwitchAuthService.State.AUTHENTICATED, service.refresh().get(3, TimeUnit.SECONDS).state());
            assertEquals("old-refresh", sentForm.get().get("refresh_token"));
            assertFalse(sentForm.get().containsKey("client_secret"));
            JsonObject persisted = JsonParser.parseString(persistedAtCallback.get()).getAsJsonObject();
            assertEquals("next-access", persisted.get("accessToken").getAsString());
            assertEquals("next-refresh", persisted.get("refreshToken").getAsString());
            assertEquals(NOW + 3600, service.status().expiresAtEpochSecond());
        }
    }

    @Test
    void simultaneousRefreshesShareOneFlight(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshRequests = new AtomicInteger();
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("validate")) return validated(access, "demo", "publicclient", 3600);
            refreshRequests.incrementAndGet();
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return tokens("next-access", "next-refresh");
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            CompletableFuture<TwitchAuthService.Status> first = service.refresh();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            CompletableFuture<TwitchAuthService.Status> second = service.refresh();
            release.countDown();
            assertSame(first, second);
            assertEquals(TwitchAuthService.State.AUTHENTICATED, first.get(3, TimeUnit.SECONDS).state());
            assertEquals(1, refreshRequests.get());
        } finally { release.countDown(); }
    }

    @Test
    void cancelPreventsLateDeviceResponseFromStartingPolling(@TempDir Path directory) throws Exception {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("device")) {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                return device();
            }
            tokenRequests.incrementAndGet();
            return tokens("must-not-publish", "must-not-persist");
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> callbacks.incrementAndGet())) {
            assertEquals("", service.accessToken().get(3, TimeUnit.SECONDS));
            var pending = service.login("publicclient", "demo");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.cancel();
            release.countDown();
            assertEquals(TwitchAuthService.State.IDLE, pending.get(3, TimeUnit.SECONDS).state());
            assertEquals("", service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals(0, tokenRequests.get());
            assertEquals(0, callbacks.get());
            assertEquals("", store.read("twitch-session"));
        } finally { release.countDown(); }
    }

    @Test
    void transientRefreshFailurePreservesStoredCredentials(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        String original = store.read("twitch-session");
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) ->
                endpoint.equals("validate") ? validated(access, "demo", "publicclient", 3600)
                        : response(503, "{\"message\":\"server unavailable old-refresh\"}");
        try (TwitchAuthService service = service(directory, store, transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            var failed = service.refresh().get(10, TimeUnit.SECONDS);
            assertEquals(TwitchAuthService.State.ERROR, failed.state());
            assertTrue(failed.hasCredentials());
            assertEquals(original, store.read("twitch-session"));
            assertEquals("old-access", service.accessToken().get(3, TimeUnit.SECONDS));
            assertFalse(failed.toString().contains("old-refresh"));
        }
    }

    @Test
    void rotatedRefreshSurvivesAnOutageDuringTokenValidation(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("token")) return tokens("next-access", "next-refresh");
            if (access.equals("next-access")) return response(503, "{\"message\":\"temporarily unavailable\"}");
            return validated(access, "demo", "publicclient", 3600);
        };
        AtomicInteger publishedNewAccess = new AtomicInteger();
        try (TwitchAuthService service = service(directory, store, transport, token -> {
            if (token.equals("next-access")) publishedNewAccess.incrementAndGet();
        })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            assertEquals(TwitchAuthService.State.ERROR, service.refresh().get(10, TimeUnit.SECONDS).state());
            JsonObject saved = JsonParser.parseString(store.read("twitch-session")).getAsJsonObject();
            assertEquals("next-refresh", saved.get("refreshToken").getAsString());
            assertEquals(0, publishedNewAccess.get());
        }
    }

    @Test
    void newlyAuthorizedWrongAccountIsNeverSaved(@TempDir Path directory) throws Exception {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> switch (endpoint) {
            case "device" -> device();
            case "token" -> tokens("new-access", "new-refresh");
            default -> validated(access, "someoneelse", "publicclient", 3600);
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> fail("Wrong account was published."))) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            assertEquals(TwitchAuthService.State.NEEDS_LOGIN, service.login("publicclient", "demo").get(5, TimeUnit.SECONDS).state());
            assertEquals("", store.read("twitch-session"));
        }
    }

    @Test
    void cancelDuringRefreshKeepsTheRotatedSessionCoherent(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        CountDownLatch validatingReplacement = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("token")) return tokens("next-access", "next-refresh");
            if (access.equals("next-access")) {
                validatingReplacement.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
            return validated(access, "demo", "publicclient", 3600);
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            var refreshing = service.refresh();
            assertTrue(validatingReplacement.await(3, TimeUnit.SECONDS));
            service.cancel();
            release.countDown();
            assertEquals(TwitchAuthService.State.AUTHENTICATED, refreshing.get(3, TimeUnit.SECONDS).state());
            assertEquals("next-access", service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals("next-refresh", JsonParser.parseString(store.read("twitch-session")).getAsJsonObject()
                    .get("refreshToken").getAsString());
        } finally { release.countDown(); }
    }

    @Test
    void unreadableManagedCredentialsCannotSilentlyBecomeGuestAccess(@TempDir Path directory) throws Exception {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        store.write("twitch-session", "not valid json");
        try (TwitchAuthService service = service(directory, store,
                (method, endpoint, form, access) -> fail("Unreadable token must not be sent."), ignored -> { })) {
            assertThrows(Exception.class, () -> service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals(TwitchAuthService.State.ERROR, service.status().state());
        }
    }

    @Test
    void legacyAccessDerivesPublicClientAndEventuallyRequiresLoginWithoutRefresh(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("viewerparrots-twitch-token.txt"), "oauth:legacy-access\n");
        AtomicReference<String> rejected = new AtomicReference<>("");
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) ->
                access.equals(rejected.get()) ? response(401, "{\"message\":\"invalid access token\"}")
                        : validated(access, "demo", "derivedclient", 3600);
        try (TwitchAuthService service = service(directory, new LocalSecretStore(directory, false), transport, ignored -> { })) {
            service.configure("", "demo");
            assertEquals("legacy-access", service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals("derivedclient", service.status().clientId());
            rejected.set("legacy-access");
            assertEquals(TwitchAuthService.State.NEEDS_LOGIN, service.authenticationFailed().get(3, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void authenticationFailureDoesNotRetryTheSameRejectedGrant(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        AtomicInteger refreshes = new AtomicInteger();
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("validate")) return validated(access, "demo", "publicclient", 3600);
            refreshes.incrementAndGet();
            return response(400, "{\"error\":\"Bad Request\",\"message\":\"Invalid refresh token\"}");
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            assertEquals(TwitchAuthService.State.NEEDS_LOGIN, service.authenticationFailed().get(3, TimeUnit.SECONDS).state());
            assertEquals(TwitchAuthService.State.NEEDS_LOGIN, service.authenticationFailed().get(3, TimeUnit.SECONDS).state());
            assertEquals(1, refreshes.get());
        }
    }

    @Test
    void cancelledLoginKeepsAnExistingWorkingSession(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("validate")) return validated(access, "demo", "publicclient", 3600);
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return device();
        };
        try (TwitchAuthService service = service(directory, store, transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            String previous = store.read("twitch-session");
            service.login("publicclient", "demo");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.cancel();
            release.countDown();
            assertEquals("old-access", service.accessToken().get(3, TimeUnit.SECONDS));
            assertEquals(previous, store.read("twitch-session"));
        } finally { release.countDown(); }
    }

    @Test
    void expiredAccessIsRefreshedBeforeItIsReturned(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        MutableClock clock = new MutableClock();
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) -> {
            if (endpoint.equals("validate")) return validated(access, "demo", "publicclient", 3600);
            return tokens("next-access", "next-refresh");
        };
        try (TwitchAuthService service = new TwitchAuthService(directory, ignored -> { }, transport, clock, store)) {
            assertEquals("old-access", service.accessToken().get(3, TimeUnit.SECONDS));
            clock.epoch += 4000;
            assertEquals("next-access", service.accessToken().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void statusAndErrorsNeverExposeTokensOrProviderBodies(@TempDir Path directory) throws Exception {
        TwitchAuthService.OAuthTransport transport = (method, endpoint, form, access) ->
                response(400, "{\"message\":\"access-secret refresh-secret device-secret\"}");
        try (TwitchAuthService service = service(directory, new LocalSecretStore(directory, false), transport, ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            var result = service.login("publicclient", "demo").get(3, TimeUnit.SECONDS);
            assertEquals(TwitchAuthService.State.ERROR, result.state());
            assertFalse(result.toString().contains("secret"));
            assertEquals("", service.accessToken().get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void logoutRemovesManagedAndLegacyCredentials(@TempDir Path directory) throws Exception {
        LocalSecretStore store = sessionStore(directory);
        Files.writeString(directory.resolve("viewerparrots-twitch-token.txt"), "legacy-access");
        try (TwitchAuthService service = service(directory, store,
                (method, endpoint, form, access) -> validated(access, "demo", "publicclient", 3600), ignored -> { })) {
            service.accessToken().get(3, TimeUnit.SECONDS);
            assertFalse(service.logout().get(3, TimeUnit.SECONDS).hasCredentials());
            assertEquals("", store.read("twitch-session"));
            assertFalse(Files.exists(directory.resolve("viewerparrots-twitch-token.txt")));
            assertEquals("", service.accessToken().get(3, TimeUnit.SECONDS));
        }
    }

    private static TwitchAuthService service(Path directory, LocalSecretStore store,
            TwitchAuthService.OAuthTransport transport, Consumer<String> callback) {
        return new TwitchAuthService(directory, callback, transport, CLOCK, store);
    }

    private static LocalSecretStore sessionStore(Path directory) throws IOException {
        LocalSecretStore store = new LocalSecretStore(directory, false);
        store.write("twitch-session", """
                {"accessToken":"old-access","refreshToken":"old-refresh","clientId":"publicclient",
                "login":"demo","userId":"42","expiresAtEpochSecond":1800003600}
                """);
        return store;
    }

    private static TwitchAuthService.OAuthResponse validated(String access, String login, String client, int expiry) {
        return response(200, "{\"client_id\":\"" + client + "\",\"login\":\"" + login
                + "\",\"user_id\":\"42\",\"scopes\":[\"chat:read\"],\"expires_in\":" + expiry + "}");
    }

    private static TwitchAuthService.OAuthResponse tokens(String access, String refresh) {
        return response(200, "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
                + "\",\"expires_in\":3600,\"token_type\":\"bearer\",\"scope\":[\"chat:read\"]}");
    }

    private static TwitchAuthService.OAuthResponse device() {
        return response(200, """
                {"device_code":"device-secret","user_code":"ABCD1234","interval":1,"expires_in":300,
                "verification_uri":"https://www.twitch.tv/activate?public=true&device-code=ABCD1234"}
                """);
    }

    private static TwitchAuthService.OAuthResponse response(int status, String json) {
        return new TwitchAuthService.OAuthResponse(status, JsonParser.parseString(json).getAsJsonObject());
    }

    private static final class MutableClock extends Clock {
        private volatile long epoch = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(epoch); }
    }
}
