package io.github.viewerparrots.client.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.viewerparrots.control.ControlPanelService;
import io.github.viewerparrots.client.SpeechAudioReceiver;
import io.github.viewerparrots.settings.ModSettings;
import io.github.viewerparrots.settings.SettingsStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Russian control panel. Editing is local until the user explicitly applies the draft. */
public final class ControlPanelScreen extends JsonFormScreen {
    private static final String[] TABS = {"Обзор", "Twitch", "Команды", "Лимиты и блокировки", "Попугаи", "Поведение", "Озвучка и голоса", "Рулетка", "Профили и прочее"};
    private JsonObject saved;
    private int tab;
    private int ticks;
    private boolean busy, refreshing, wasLoading;
    private ControlPanelService.Dashboard dashboard;
    private String newProfileName = "", importFilename = "", openRouterKey = "";
    private String authStamp = "";
    private boolean readySubscribed;
    private boolean openAuthBrowser;
    private String openedUserCode = "";

    public ControlPanelScreen(Screen parent) {
        super(parent, "Viewer Parrots — панель управления", json(ControlPanelService.settings()), json(ModSettings.defaults()));
        saved = data.deepCopy();
        alignDefaults();
        wasLoading = loading();
    }

    static JsonObject json(ModSettings settings) { return JsonParser.parseString(SettingsStore.toJson(settings)).getAsJsonObject(); }
    String activeId() { return data.get("activeProfile").getAsString(); }
    String profilePath() { return "profiles." + activeId(); }
    JsonObject profile() { return get(data, profilePath()).getAsJsonObject(); }
    JsonObject defaultProfile() { return get(defaults, profilePath()).getAsJsonObject(); }
    boolean worldOpen() { return dashboard != null && dashboard.worldOpen() && client != null && client.getServer() != null; }
    boolean dirty() { return hasInvalidInput() || !data.equals(saved); }
    private boolean loading() { return !ControlPanelService.ready().isDone(); }

    private void alignDefaults() {
        JsonObject template = json(ModSettings.defaults()).getAsJsonObject("profiles").getAsJsonObject("default");
        JsonObject profiles = defaults.getAsJsonObject("profiles");
        for (String id : data.getAsJsonObject("profiles").keySet()) if (!profiles.has(id)) profiles.add(id, template.deepCopy());
    }

    @Override protected void init() {
        super.init();
        if (!readySubscribed) {
            readySubscribed = true;
            ControlPanelService.ready().whenComplete((ignored, error) -> client.execute(() -> {
                if (!dirty()) { data = json(ControlPanelService.settings()); saved = data.deepCopy(); alignDefaults(); clearInputErrors(); }
                wasLoading = false;
                rebuild(); refresh();
            }));
        }
    }

    @Override protected void bounds() {
        left = Math.min(157, Math.max(119, width / 4));
        right = width - 13;
        top = 64;
        bottom = height - 49;
    }

    @Override protected void chrome() {
        int navWidth = left - 21;
        for (int i = 0; i < TABS.length; i++) {
            int selected = i;
            int step = Math.max(17, Math.min(24, (height - 95) / 9));
            ButtonWidget button = fixed(button(TABS[i], 8, 47 + i * step, navWidth, () -> {
                tab = selected;
                query = "";
                scroll = 0;
                rebuild();
                if (tab == 0 || tab == 4) refresh();
            }));
            button.setHeight(Math.min(20, step - 1));
            button.active = selected != tab;
        }
        search(left, 34, Math.max(40, right - left - 29));
        fixed(button("↻", right - 24, 34, 24, this::refresh));
        ButtonWidget apply = fixed(button("Применить", width - 224, height - 30, 106, () -> apply(null)));
        apply.active = !busy && !loading();
        fixed(button("Закрыть", width - 112, height - 30, 100, this::close));
    }

    @Override protected void content() {
        if (loading()) { info("Настройки", "Загрузка…"); return; }
        if (busy) { info("Сохранение", "Дождитесь завершения операции…"); return; }
        switch (tab) {
            case 0 -> overview();
            case 1 -> twitch();
            case 2 -> commands();
            case 3 -> moderation();
            case 4 -> birds();
            case 5 -> behavior();
            case 6 -> speech();
            case 7 -> roulette();
            case 8 -> advanced();
            default -> { }
        }
    }

    private void overview() {
        heading("Состояние мода");
        info("Мир", worldOpen() ? "Открыт; меню ставит игру на паузу" : "Откройте одиночный мир для игровых действий");
        action("Пауза при Alt+Tab",
                "Если включена, переход в браузер ставит одиночный мир на паузу, и команды Twitch пропускаются. "
                        + "Меняется и сохраняется сразу в настройках Minecraft для всего инстанса. Esc и F8 по-прежнему ставят мир на паузу.",
                client.options.pauseOnLostFocus ? "Включена" : "Выключена", true, () -> {
                    client.options.pauseOnLostFocus = !client.options.pauseOnLostFocus;
                    client.options.write();
                    notify("Пауза при Alt+Tab " + (client.options.pauseOnLostFocus ? "включена" : "выключена") + ". Сохранено в Minecraft.", false);
                    rebuild();
                });
        info("Профиль", profile().get("name").getAsString());
        if (dashboard != null) {
            info("Twitch", dashboard.twitchStatus() + (dashboard.channel().isBlank() ? "" : " · " + dashboard.channel()));
            info("Игрок стримера", dashboard.streamer().isBlank() ? "Не выбран" : dashboard.streamer());
            info("Попугаев", Integer.toString(dashboard.birds().size()));
            info("Очереди", "Чат: " + dashboard.twitchPending() + " · голос: " + dashboard.speechPending() + " · рулетка: " + dashboard.roulettePending());
            info("Озвучка за час", dashboard.hourlyRequests() + " запросов · " + dashboard.hourlyWords() + " слов");
            if (dashboard.restartRequired()) info("Повторно откройте мир", "Часть настроек ожидает перезапуска");
        }
        action("Команды Twitch", "Остановленные команды пропускаются, очередь не копится.", ControlPanelClient.chatStopped() ? "Возобновить приём" : "Остановить приём", true,
                () -> { ControlPanelClient.toggleChatStopped(); rebuild(); });
        action("Остановить озвучку", "Удаляет ожидающие реплики и останавливает воспроизведение.", "Остановить", true, this::stopSpeech);
        action("Игрок стримера", "Назначить владельца одиночного мира стримером. Меню закроется.", "Назначить себя", worldOpen(), () -> runWorld("", "SETSTREAMER", "", false));
        heading("Основные настройки");
        for (String key : List.of("maxActiveParrots", "deathMode", "nameFormat", "nameVisible")) field(profilePath() + ".general." + key);
        heading("Недавние команды");
        if (dashboard == null || dashboard.recent().isEmpty()) info("История", "Команд пока нет");
        else for (var recent : dashboard.recent()) info(recent.viewer() + " · " + recent.action(), recent.result());
    }

    private void twitch() {
        heading("Подключение к чату");
        info("Публичный чат", "Гостевой доступ работает без Twitch-токена и приложения");
        group("twitch");
        actions("Соединение", "Подключение использует значения после применения черновика.", new String[]{"Применить и войти", "Отключить"}, new Runnable[]{
                () -> apply(() -> async(ControlPanelService.twitchConnect(), "Подключение запрошено")),
                () -> async(ControlPanelService.twitchDisconnect(), "Отключено")}, !busy);
        heading("Вход через Twitch — по желанию");
        var auth = ControlPanelService.auth().status();
        info("Авторизация", authLabel(auth.state().toString()));
        if (!auth.login().isBlank()) info("Аккаунт", auth.login());
        if (auth.expiresAtEpochSecond() > 0) info("Действителен до", DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(auth.expiresAtEpochSecond())));
        if (!auth.message().isBlank()) info("Подробности", auth.message());
        if (!auth.userCode().isBlank()) {
            info("Код входа", auth.userCode());
            actions("Подтверждение", "Откройте Twitch и введите код. Браузер открывается только по этой кнопке.", new String[]{"Открыть Twitch", "Копировать код"}, new Runnable[]{
                    () -> { try { Util.getOperatingSystem().open(URI.create(auth.verificationUri())); } catch (RuntimeException badUrl) { notify("Не удалось открыть страницу Twitch", true); } },
                    () -> { client.keyboard.setClipboard(auth.userCode()); notify("Код скопирован", false); }}, true);
        }
        actions("Вход и обновление", "Для входа нужен Client ID публичного Twitch-приложения. Секрет приложения не требуется.", new String[]{"Войти", "Обновить", "Выйти"}, new Runnable[]{
                () -> { openAuthBrowser = true; authTask(ControlPanelService.auth().login(get(data, "twitch.clientId").getAsString(), get(data, "twitch.login").getAsString())); },
                () -> authTask(ControlPanelService.auth().refresh()),
                () -> authTask(ControlPanelService.auth().logout())}, true);
        action("Ожидание входа", "Отменяет незавершённую авторизацию.", "Отменить вход", true, () -> { ControlPanelService.auth().cancel(); rebuild(); });
    }

    private void commands() {
        heading("Чат-команды — " + profile().getAsJsonObject("commands").size() + " действий");
        JsonObject commands = profile().getAsJsonObject("commands");
        for (String name : commands.keySet()) {
            JsonObject rule = commands.getAsJsonObject(name);
            String label = commandLabel(name);
            String summary = (rule.get("enabled").getAsBoolean() ? "Включена" : "Выключена") + ", " + decimal(BigDecimalSafe.of(rule.get("cooldownTicks").getAsInt()).divide(java.math.BigDecimal.valueOf(20))) + " сек.";
            action(label, commandDescription(name) + ". " + summary, "Права и интервал", true,
                    () -> client.setScreen(new ControlPanelEditors.CommandEditor(this, label, rule.deepCopy(), defaultProfile().getAsJsonObject("commands").getAsJsonObject(name).deepCopy(), changed -> {
                        commands.add(name, changed); synchronizeCommand(name, changed); rebuild();
                    })));
        }
        heading("Общие базовые интервалы");
        group(profilePath() + ".general.cooldowns");
    }

    private void synchronizeCommand(String name, JsonObject rule) {
        JsonObject commands = profile().getAsJsonObject("commands");
        String paired = switch (name) {
            case "VIEW" -> "DEVIEW"; case "DEVIEW" -> "VIEW";
            case "ARM" -> "DEARM"; case "DEARM" -> "ARM";
            case "HAT" -> "DEHAT"; case "DEHAT" -> "HAT";
            default -> "";
        };
        if (!paired.isEmpty() && commands.has(paired)) {
            commands.getAsJsonObject(paired).add("cooldownTicks", rule.get("cooldownTicks").deepCopy());
            commands.getAsJsonObject(paired).add("roleCooldownTicks", rule.get("roleCooldownTicks").deepCopy());
        }
        if (name.equals("ROULETTE")) profile().getAsJsonObject("roulette").add("cooldownTicks", rule.get("cooldownTicks").deepCopy());
        String legacy = name.toLowerCase(Locale.ROOT);
        JsonObject cooldowns = profile().getAsJsonObject("general").getAsJsonObject("cooldowns");
        if (cooldowns.has(legacy)) cooldowns.add(legacy, rule.get("cooldownTicks").deepCopy());
    }

    @Override protected void onDraftChanged(String path) {
        JsonObject commands = profile().getAsJsonObject("commands");
        if (path.equals(profilePath() + ".roulette.cooldownTicks")) commands.getAsJsonObject("ROULETTE").add("cooldownTicks", get(data, path).deepCopy());
        if (path.startsWith(profilePath() + ".general.cooldowns.")) {
            String name = last(path).toUpperCase(Locale.ROOT);
            if (commands.has(name)) commands.getAsJsonObject(name).add("cooldownTicks", get(data, path).deepCopy());
        }
    }

    private void moderation() {
        heading("Лимиты игровых действий");
        group(profilePath() + ".general.limits");
        heading("Чат и модерация");
        group(profilePath() + ".moderation");
    }

    private void birds() {
        info("Управление птицами", "Игровое действие закрывает меню и снимает паузу");
        action("Обновить список", "Показывает состояние всех зрительских птиц.", "Обновить", worldOpen(), this::refresh);
        if (!worldOpen()) { info("Мир не открыт", "Откройте одиночный мир"); return; }
        if (dashboard.birds().isEmpty()) info("Список пуст", "Зритель создаёт птицу командой !parrot");
        for (var bird : dashboard.birds()) {
            action(bird.displayName() + " · " + bird.viewerId(), "Состояние: " + bird.state() + "; " + mode(bird.behavior()) + "; голос: " + bird.voice() + "; шляпа: " + bird.hat() + "; плечо: " + bird.shoulder(), "Управлять",
                    true, () -> client.setScreen(new ControlPanelEditors.BirdEditor(this, bird)));
        }
        action("Создать тестовую птицу", "Без Twitch. Введите владельца на следующем экране.", "Создать…", worldOpen(), () -> client.setScreen(new ControlPanelEditors.CreateBirdScreen(this)));
    }

    private void behavior() {
        heading("Движение и взгляд");
        group(profilePath() + ".gameplay");
        heading("Прилёт, следование и танец");
        for (String group : List.of("come", "follow", "dance", "behavior", "sound")) {
            heading(label(group)); group(profilePath() + ".general." + group);
        }
        heading("Дуэли !fight");
        action("Дуэли", "Снять все ожидающие вызовы и прервать текущие дуэли без победителя.", "Отменить все дуэли",
                dashboard != null && dashboard.worldOpen(), () -> runWorld(null, "DUEL_CANCEL_ALL", "", false));
    }

    private void speech() {
        JsonObject speech = profile().getAsJsonObject("speech");
        heading("Озвучка !say");
        for (String key : speech.keySet()) if (!key.equals("voices")) field(profilePath() + ".speech." + key);
        if (dashboard != null) info("Потрачено за час", dashboard.hourlyRequests() + " запросов / " + dashboard.hourlyWords() + " слов");
        heading("Интонации — !say [стиль] текст");
        info("Тег ElevenLabs", "Латиницей в квадратных скобках, например [whispers] или [excited, loud]. Пусто — без тега.");
        JsonObject styleTags = speech.getAsJsonObject("styleTags");
        if (styleTags != null) for (String style : styleTags.keySet()) {
            field(profilePath() + ".speech.styleTags." + style, "[" + style + "]", "Зритель пишет !say [" + style + "] текст.");
        }
        heading("Тег по характеру птицы — когда стиль не указан");
        JsonObject traitTags = speech.getAsJsonObject("traitTags");
        if (traitTags != null) for (String trait : traitTags.keySet()) {
            field(profilePath() + ".speech.traitTags." + trait, enumLabel(trait), "Характер назначается при создании птицы.");
        }
        Row key = row("Новый ключ OpenRouter", "Ключ скрыт и сохраняется отдельно от профиля. Текущий ключ не читается и не показывается.");
        if (key != null) {
            TextFieldWidget field = textInput(key, openRouterKey, 512, value -> openRouterKey = value);
            field.setRenderTextProvider((value, start) -> Text.literal("•".repeat(value.length())).asOrderedText());
        }
        action("Ключ озвучки", "Сохранить новый ключ отдельно от настроек. Не отправляйте его другим людям.", "Сохранить ключ", true, () -> {
            String value = openRouterKey.strip();
            if (value.isEmpty()) { notify("Введите новый ключ", true); return; }
            openRouterKey = "";
            rebuild();
            async(ControlPanelService.saveOpenRouterKey(value), "Ключ сохранён");
        });
        action("Очередь озвучки", "Остановить воспроизведение и ожидающие реплики.", "Остановить всё", true, this::stopSpeech);
        heading("Голоса — без повторов до исчерпания списка");
        JsonArray voices = speech.getAsJsonArray("voices");
        action("Новый голос", "Voice ID из библиотеки ElevenLabs.", "Добавить голос", voices.size() < 128, () -> {
            JsonObject voice = new JsonObject(); voice.addProperty("id", ""); voice.addProperty("name", "Новый голос"); voice.addProperty("enabled", true);
            editVoice(voice, -1);
        });
        for (int i = 0; i < voices.size(); i++) {
            int index = i;
            JsonObject voice = voices.get(i).getAsJsonObject();
            action((voice.get("enabled").getAsBoolean() ? "✓ " : "— ") + voice.get("name").getAsString(), voice.get("id").getAsString(), "Изменить / послушать", true, () -> editVoice(voice, index));
        }
    }

    private void editVoice(JsonObject voice, int index) {
        JsonArray voices = profile().getAsJsonObject("speech").getAsJsonArray("voices");
        client.setScreen(new ControlPanelEditors.VoiceEditor(this, voice.deepCopy(), changed -> {
            if (index < 0) voices.add(changed); else voices.set(index, changed);
            rebuild();
        }, index < 0 || voices.size() == 1 ? null : () -> { voices.remove(index); rebuild(); }));
    }

    private void roulette() {
        heading("Колесо удачи !roulette");
        JsonObject roulette = profile().getAsJsonObject("roulette");
        for (String key : roulette.keySet()) if (!key.equals("categories")) field(profilePath() + ".roulette." + key);
        info("Шансы", "Вес категории / сумма весов; внутри категории предметы равновероятны");
        JsonArray categories = roulette.getAsJsonArray("categories");
        long total = 0; for (JsonElement category : categories) total += category.getAsJsonObject().get("weight").getAsLong();
        action("Новая категория", "От 1 до 16 категорий призов.", "Добавить категорию", categories.size() < 16, () -> {
            JsonObject category = new JsonObject(); category.addProperty("name", "Категория " + (categories.size() + 1)); category.addProperty("weight", 1); category.addProperty("color", 0xF6CE6B);
            JsonObject prize = new JsonObject(); prize.addProperty("itemId", "minecraft:wheat_seeds"); prize.addProperty("count", 1);
            JsonArray items = new JsonArray(); items.add(prize); category.add("items", items);
            editCategory(category, -1);
        });
        for (int i = 0; i < categories.size(); i++) {
            int index = i;
            JsonObject category = categories.get(i).getAsJsonObject();
            String chance = String.format(Locale.ROOT, "%.2f%%", category.get("weight").getAsDouble() * 100 / Math.max(1, total));
            action(category.get("name").getAsString() + " · " + chance, "Предметов: " + category.getAsJsonArray("items").size() + ". Настроить вес, цвет и призы.", "Изменить категорию", true, () -> editCategory(category, index));
        }
    }

    private void editCategory(JsonObject category, int index) {
        JsonArray categories = profile().getAsJsonObject("roulette").getAsJsonArray("categories");
        client.setScreen(new ControlPanelEditors.CategoryEditor(this, category.deepCopy(), changed -> {
            if (index < 0) categories.add(changed); else categories.set(index, changed);
            rebuild();
        }, index < 0 || categories.size() == 1 ? null : () -> { categories.remove(index); rebuild(); }));
    }

    private void advanced() {
        heading("Профили");
        field(profilePath() + ".name", "Имя активного профиля", "У каждого профиля должны быть своё уникальное имя и настройки.");
        JsonObject profiles = data.getAsJsonObject("profiles");
        for (String id : profiles.keySet()) action(profiles.getAsJsonObject(id).get("name").getAsString(), id.equals(activeId()) ? "Активный черновик" : "Выбрать этот профиль в черновике", id.equals(activeId()) ? "Выбран" : "Выбрать", !id.equals(activeId()), () -> {
            data.addProperty("activeProfile", id); clearInputErrors(); query = ""; scroll = 0; rebuild();
        });
        Row name = row("Имя нового профиля", "Создаётся копия текущего профиля.");
        if (name != null) textInput(name, newProfileName, 64, value -> newProfileName = value);
        action("Копия настроек", "Создать отдельный профиль из текущего черновика.", "Создать копию", profiles.size() < 32, () -> {
            if (newProfileName.isBlank()) { notify("Введите имя нового профиля", true); return; }
            JsonObject copy = profile().deepCopy(); copy.addProperty("name", newProfileName.strip());
            String id = "profile-" + UUID.randomUUID().toString(); profiles.add(id, copy); data.addProperty("activeProfile", id);
            newProfileName = ""; alignDefaults(); clearInputErrors(); rebuild();
        });
        action("Сброс профиля", "Вернуть активный профиль к стандартным настройкам. Потребуется нажать «Применить».", "Сбросить…", true,
                () -> confirm("Сбросить профиль?", "Настройки активного профиля будут заменены стандартными в черновике.", () -> {
                    String nameValue = profile().get("name").getAsString(); JsonObject reset = defaultProfile().deepCopy(); reset.addProperty("name", nameValue); profiles.add(activeId(), reset); clearInputErrors(); rebuild();
                }));
        action("Удаление профиля", "Удаляется только из черновика, пока вы не примените изменения.", "Удалить…", profiles.size() > 1,
                () -> confirm("Удалить профиль?", profile().get("name").getAsString(), () -> {
                    profiles.remove(activeId()); data.addProperty("activeProfile", profiles.keySet().iterator().next()); clearInputErrors(); rebuild();
                }));
        action("Экспорт профиля", "Экспорт сохранённого профиля в config/viewerparrots-profiles. Сначала примените изменения.", "Экспортировать", !dirty(), () -> async(ControlPanelService.exportProfile(activeId()), "Профиль экспортирован"));
        Row file = row("Файл для импорта", "Имя .json файла в config/viewerparrots-profiles, например friend.json.");
        if (file != null) textInput(file, importFilename, 128, value -> importFilename = value);
        action("Импорт профиля", "Загрузить профиль из папки экспорта в черновик.", "Импортировать", !busy, () -> {
            busy = true; rebuild();
            ControlPanelService.importProfile(importFilename.strip()).whenComplete((settings, error) -> client.execute(() -> {
                busy = false;
                if (error != null) notify(errorText(error), true);
                else {
                    JsonObject imported = json(settings);
                    data.getAsJsonObject("profiles").add(settings.activeProfile(), imported.getAsJsonObject("profiles").get(settings.activeProfile()).deepCopy());
                    data.addProperty("activeProfile", settings.activeProfile());
                    alignDefaults(); clearInputErrors(); notify("Профиль импортирован. Нажмите «Применить».", false);
                }
                rebuild();
            }));
        });
        heading("Дополнительно");
        for (String group : List.of("permissions", "recovery", "storage")) { heading(label(group)); group(profilePath() + ".general." + group); }
        action("Перечитать настройки", "Отклоняет черновик и перечитывает сохранённый файл.", "Перечитать…", !busy, () -> confirm("Перечитать настройки?", "Несохранённые изменения будут отменены.", () -> {
            busy = true; rebuild();
            ControlPanelService.reload().whenComplete((settings, error) -> client.execute(() -> {
                busy = false;
                if (error != null) notify(errorText(error), true);
                else { data = json(settings); saved = data.deepCopy(); alignDefaults(); clearInputErrors(); notify("Настройки перечитаны", false); }
                rebuild();
            }));
        }));
    }

    private void apply(Runnable after) {
        if (busy) return;
        if (hasInvalidInput()) { notify(invalid.values().iterator().next(), true); return; }
        final ModSettings candidate;
        try { candidate = SettingsStore.parse(data.toString()); }
        catch (RuntimeException invalidSettings) { notify("Проверьте настройки: " + errorText(invalidSettings), true); return; }
        busy = true; notify("Сохранение…", false); rebuild();
        ControlPanelService.save(candidate).whenComplete((result, error) -> client.execute(() -> {
            busy = false;
            if (error != null) notify(errorText(error), true);
            else {
                data = json(ControlPanelService.settings()); saved = data.deepCopy(); alignDefaults(); clearInputErrors();
                notify(result.message() + (result.restartRequired() ? " · Повторно откройте мир" : ""), false);
                if (after != null) after.run();
            }
            rebuild(); refresh();
        }));
    }

    void refresh() {
        if (refreshing) return;
        refreshing = true;
        ControlPanelService.snapshot().whenComplete((snapshot, error) -> client.execute(() -> {
            refreshing = false;
            if (error != null) { notify(errorText(error), true); return; }
            dashboard = snapshot;
            if ((tab == 0 || tab == 4) && !(getFocused() instanceof TextFieldWidget)) rebuild();
        }));
    }

    void async(CompletableFuture<?> task, String success) {
        notify("Выполняется…", false);
        task.whenComplete((result, error) -> client.execute(() -> {
            notify(error == null ? (result instanceof String string ? string : success) : errorText(error), error != null);
            refresh();
        }));
    }

    private void stopSpeech() {
        SpeechAudioReceiver.stopAll();
        async(ControlPanelService.stopSpeech(), "Озвучка остановлена");
    }

    private void authTask(CompletableFuture<?> task) {
        async(task, "Запрос авторизации обработан");
        task.whenComplete((result, error) -> client.execute(() -> { if (client.currentScreen == this) rebuild(); }));
    }

    void confirm(String title, String explanation, Runnable accepted) {
        client.setScreen(new ConfirmScreen(answer -> {
            client.setScreen(this);
            if (answer) accepted.run();
        }, Text.literal(title), Text.literal(explanation), Text.literal("Подтвердить"), Text.literal("Отмена")));
    }

    void runWorld(String viewer, String action, String argument, boolean dangerous) {
        Runnable run = () -> worldTask(() -> ControlPanelService.execute(viewer, action, argument));
        if (dangerous || dirty()) confirm(dangerous ? "Подтвердить действие?" : "Закрыть без сохранения?",
                (dangerous ? "Действие: " + commandLabel(action) + " для " + viewer + ". " : "") + (dirty() ? "Несохранённые настройки будут отменены." : "Меню закроется, мир продолжит работу."), run);
        else run.run();
    }

    void worldTask(Supplier<CompletableFuture<String>> operation) {
        MinecraftClient game = client;
        game.setScreen(null);
        operation.get().whenComplete((result, error) -> game.execute(() -> {
            String text = error == null ? result : errorText(error);
            if (game.player != null) game.player.sendMessage(Text.literal("[Попугаи] " + text), false);
            else game.getToastManager().add(SystemToast.create(game, SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.literal("Viewer Parrots"), Text.literal(text)));
        }));
    }

    @Override public void tick() {
        super.tick();
        if (++ticks % 40 == 1) refresh();
        if (wasLoading && !loading() && !dirty()) {
            data = json(ControlPanelService.settings()); saved = data.deepCopy(); alignDefaults(); clearInputErrors(); wasLoading = false; rebuild();
        }
        if (tab == 1) {
            var status = ControlPanelService.auth().status();
            if (openAuthBrowser && status.state().toString().equals("WAITING_AUTHORIZATION") && !status.userCode().isBlank() && !status.userCode().equals(openedUserCode)) {
                openedUserCode = status.userCode();
                openAuthBrowser = false;
                try { Util.getOperatingSystem().open(URI.create(status.verificationUri())); }
                catch (RuntimeException badUrl) { notify("Откройте страницу Twitch кнопкой в панели", true); }
            }
            String stamp = status.state() + status.userCode() + status.message();
            if (!stamp.equals(authStamp) && !(getFocused() instanceof TextFieldWidget)) { authStamp = stamp; rebuild(); }
        }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, "РАЗДЕЛЫ", 11, 32, 0xAFC0D5);
        String badge = busy ? "Сохранение…" : loading() ? "Загрузка…" : dirty() ? "Есть изменения" : "Сохранено";
        int badgeWidth = Math.max(30, Math.min(left - 20, width - 241));
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(badge, badgeWidth), 11, height - 34, dirty() ? 0xF6CE6B : 0xAFC0D5);
        if (dashboard != null && dashboard.restartRequired()) context.drawTextWithShadow(textRenderer, "Нужен вход в мир заново", left, height - 43, 0xF6CE6B);
        String error = ControlPanelService.settingsError();
        if (!error.isBlank() && !loading() && message.isBlank()) context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(error, right - left), left, height - 42, 0xFF8F86);
    }

    @Override public void close() {
        if (busy) { notify("Дождитесь окончания сохранения", false); return; }
        if (dirty()) {
            client.setScreen(new ConfirmScreen(discard -> { if (discard) client.setScreen(parent); else client.setScreen(this); },
                    Text.literal("Закрыть без сохранения?"), Text.literal("Изменения черновика будут отменены."), Text.literal("Не сохранять"), Text.literal("Назад")));
        } else super.close();
    }

    static String errorText(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String text = cause.getMessage();
        return text == null || text.isBlank() ? "Операция не выполнена" : text;
    }

    static String commandLabel(String name) { return name.equals("CREATE") ? "!parrot" : "!" + name.toLowerCase(Locale.ROOT); }
    static String commandDescription(String name) {
        return switch (name) {
            case "CREATE" -> "Создать своего попугая";
            case "COME" -> "Прилететь к стримеру";
            case "FOLLOW" -> "Следовать за стримером";
            case "FREE" -> "Естественное свободное поведение";
            case "FREEFOLLOW" -> "Гулять рядом со стримером";
            case "VIEW" -> "Смотреть на стримера";
            case "DEVIEW" -> "Перестать следить взглядом";
            case "ARM" -> "Сесть на плечо";
            case "DEARM" -> "Покинуть плечо";
            case "SIT" -> "Сесть на месте";
            case "DANCE" -> "Танцевать";
            case "SPIN" -> "Быстро вращаться";
            case "SCREAM" -> "Издать случайный крик попугая";
            case "SAY" -> "Озвучить текст голосом птицы";
            case "LOVE" -> "Показать сердечки";
            case "HAT" -> "Сменить случайную шляпу";
            case "DEHAT" -> "Снять шляпу";
            case "COLOR" -> "Сменить окрас на случайный другой";
            case "NAME" -> "Кличка птицы (!name Текст; !name — убрать)";
            case "EMOTE" -> "Случайная эмоция: поклон, нет, кивок, прыжок";
            case "FIGHT" -> "Вызов на дуэль (!fight @ник)";
            case "ACCEPT" -> "Принять вызов на дуэль";
            case "DECLINE" -> "Отклонить вызов на дуэль";
            case "EXP" -> "Разбить пузырёк опыта у птицы";
            case "ROULETTE" -> "Разыграть предмет";
            default -> name;
        };
    }

    static String mode(String mode) {
        return switch (mode) {
            case "FREE" -> "Свободен"; case "FREEFOLLOW" -> "Рядом со стримером"; case "SHOULDER" -> "На плече";
            case "SITTING" -> "Сидит"; case "FOLLOWING" -> "Следует"; case "RETURNING" -> "Возвращается"; case "DANCING" -> "Танцует";
            default -> mode;
        };
    }

    private static String authLabel(String state) {
        return switch (state) {
            case "IDLE", "LOGGED_OUT", "DISCONNECTED" -> "Вход не выполнен";
            case "AUTHENTICATED", "CONNECTED", "READY" -> "Вход выполнен";
            case "PENDING", "WAITING", "WAITING_FOR_USER", "AUTHORIZING", "WAITING_AUTHORIZATION" -> "Ожидание подтверждения Twitch";
            case "VALIDATING" -> "Проверка доступа";
            case "NEEDS_LOGIN" -> "Требуется повторный вход";
            case "REFRESHING" -> "Обновление доступа"; case "EXPIRED" -> "Доступ истёк";
            case "ERROR", "FAILED" -> "Ошибка входа";
            default -> state;
        };
    }

    private static final class BigDecimalSafe {
        static java.math.BigDecimal of(int number) { return java.math.BigDecimal.valueOf(number); }
    }
}
