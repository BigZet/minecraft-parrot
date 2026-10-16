package io.github.viewerparrots.client.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Typed controls over an immutable-settings draft; no source editor is shown. */
abstract class JsonFormScreen extends MenuFormScreen {
    protected JsonObject data;
    protected JsonObject defaults;
    protected final Map<String, String> invalid = new LinkedHashMap<>();
    private final Map<String, String> raw = new HashMap<>();

    protected JsonFormScreen(Screen parent, String title, JsonObject data, JsonObject defaults) {
        super(parent, title);
        this.data = data;
        this.defaults = defaults;
    }

    protected final void group(String path) { group(path, ""); }
    protected final void group(String path, String prefix) {
        JsonElement node = get(data, path);
        if (node == null || !node.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("schemaVersion")) continue;
            String next = path.isBlank() ? entry.getKey() : path + "." + entry.getKey();
            if (entry.getValue().isJsonObject()) {
                heading(prefix + label(entry.getKey()));
                group(next, "");
            } else if (entry.getValue().isJsonArray()) {
                JsonArray array = entry.getValue().getAsJsonArray();
                if (array.isEmpty() || array.get(0).isJsonPrimitive()) {
                    action(prefix + label(entry.getKey()), help(next), "Изменить список (" + array.size() + ")", true,
                            () -> client.setScreen(new StringListScreen(this, label(entry.getKey()), array.deepCopy(), changed -> {
                                set(data, next, changed);
                                rebuild();
                            })));
                }
            } else field(next, prefix + label(entry.getKey()), help(next));
        }
    }

    protected final void field(String path) { field(path, label(last(path)), help(path)); }

    protected final void field(String path, String label, String help) {
        JsonElement value = get(data, path);
        if (value == null || !value.isJsonPrimitive()) return;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        boolean seconds = seconds(path);
        String suffix = seconds || last(path).endsWith("Seconds") ? " (сек.)" : "";
        Row row = row(label + suffix, help);
        if (row == null) return;
        int resetSize = 23;
        if (primitive.isBoolean()) {
            widget(row, ButtonWidget.builder(Text.literal(primitive.getAsBoolean() ? "Включено" : "Выключено"), pressed -> {
                boolean enabled = !get(data, path).getAsBoolean();
                set(data, path, new JsonPrimitive(enabled));
                onDraftChanged(path);
                pressed.setMessage(Text.literal(enabled ? "Включено" : "Выключено"));
            }).dimensions(controlX(), 0, controlWidth() - resetSize - 3, 20).build());
        } else if (last(path).equals("deathMode")) {
            List<String> modes = List.of("IMMORTAL", "MANUAL_RESPAWN", "VANILLA_FINAL");
            widget(row, ButtonWidget.builder(Text.literal(enumLabel(value.getAsString())), pressed -> {
                int index = modes.indexOf(get(data, path).getAsString());
                String next = modes.get((index + 1) % modes.size());
                set(data, path, new JsonPrimitive(next));
                onDraftChanged(path);
                pressed.setMessage(Text.literal(enumLabel(next)));
            }).dimensions(controlX(), 0, controlWidth() - resetSize - 3, 20).build());
        } else {
            TextFieldWidget input = new TextFieldWidget(textRenderer, controlX(), 0, controlWidth() - resetSize - 3, 20, Text.literal(label));
            input.setMaxLength(primitive.isString() ? 1024 : 20);
            String initial = value.getAsString();
            if (primitive.isNumber() && seconds) initial = decimal(BigDecimal.valueOf(value.getAsDouble()).divide(BigDecimal.valueOf(20)));
            if (last(path).equals("color")) initial = String.format("#%06X", value.getAsInt());
            input.setText(raw.getOrDefault(path, initial));
            input.setEditableColor(invalid.containsKey(path) ? 0xFF8F86 : 0xE0E0E0);
            input.setChangedListener(text -> {
                raw.put(path, text);
                try {
                    JsonPrimitive next;
                    if (primitive.isNumber()) {
                        BigDecimal number;
                        if (last(path).equals("color")) number = BigDecimal.valueOf(Integer.parseInt(text.replace("#", ""), 16));
                        else number = new BigDecimal(text.strip());
                        if (seconds) number = number.multiply(BigDecimal.valueOf(20)).setScale(0, RoundingMode.HALF_UP);
                        next = new JsonPrimitive(number);
                    } else next = new JsonPrimitive(text);
                    set(data, path, next);
                    onDraftChanged(path);
                    invalid.remove(path);
                    input.setEditableColor(0xE0E0E0);
                } catch (RuntimeException badNumber) {
                    invalid.put(path, label + ": введите число" + (last(path).equals("color") ? " цвета, например #FFCC33" : ""));
                    input.setEditableColor(0xFF8F86);
                }
            });
            widget(row, input);
        }
        ButtonWidget reset = widget(row, button("↶", right - resetSize - 9, 0, resetSize, () -> {
            JsonElement original = get(defaults, path);
            if (original != null) set(data, path, original.deepCopy());
            onDraftChanged(path);
            raw.remove(path);
            invalid.remove(path);
            rebuild();
        }));
        reset.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Стандартное значение")));
    }

    protected final boolean hasInvalidInput() { return !invalid.isEmpty(); }
    protected void onDraftChanged(String path) { }
    protected final void clearInputErrors() { raw.clear(); invalid.clear(); }

    static JsonElement get(JsonObject root, String path) {
        if (path.isBlank()) return root;
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (!current.isJsonObject() || !current.getAsJsonObject().has(part)) return null;
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    static void set(JsonObject root, String path, JsonElement value) {
        String[] pieces = path.split("\\.");
        JsonObject node = root;
        for (int i = 0; i < pieces.length - 1; i++) node = node.getAsJsonObject(pieces[i]);
        node.add(pieces[pieces.length - 1], value);
    }

    static String last(String path) { return path.substring(path.lastIndexOf('.') + 1); }
    static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    static boolean seconds(String path) {
        return last(path).endsWith("Ticks") || path.contains(".roleCooldownTicks.") || path.contains(".general.cooldowns.");
    }

    static String enumLabel(String name) {
        return switch (name) {
            case "IMMORTAL" -> "Бессмертие";
            case "MANUAL_RESPAWN" -> "Смерть с возрождением";
            case "VANILLA_FINAL" -> "Окончательная смерть";
            case "EVERYONE" -> "Все зрители";
            case "SUBSCRIBER" -> "Подписчики";
            case "VIP" -> "VIP";
            case "MODERATOR" -> "Модераторы";
            case "BROADCASTER" -> "Стример";
            case "NORMAL" -> "Обычный";
            case "COWARD" -> "Трусливый";
            case "LAZY" -> "Ленивый";
            case "LOUD" -> "Крикливый";
            case "HERO" -> "Герой";
            case "THIEF" -> "Вор";
            default -> name;
        };
    }

    static String label(String key) {
        return LABELS.getOrDefault(key, enumLabel(key));
    }

    static String help(String path) {
        if (seconds(path)) return "Время в секундах. Изменение вступит в силу после применения. 1 секунда = 20 игровых тиков.";
        return switch (last(path)) {
            case "nameFormat" -> "Шаблон имени птицы: {viewer_name}.";
            case "hourlyRequests", "hourlyWords" -> "Часовой лимит платного синтеза. 0 отключает лимит.";
            case "roles" -> "Выберите роли, которым разрешена команда. Все зрители включает остальные роли.";
            case "weight" -> "Относительный вес. Шанс категории равен её весу, делённому на сумму весов.";
            case "color" -> "Цвет категории в формате #RRGGBB.";
            case "guest" -> "Обычный публичный чат можно читать без входа в Twitch.";
            case "blockedViewers" -> "Команды этих зрителей пропускаются. ID из списка «Попугаи», например twitch:123456; можно через запятую.";
            case "mutedViewers" -> "Отключает !say. ID из списка «Попугаи», например twitch:123456; можно через запятую. Другие команды остаются доступны.";
            case "model" -> "Идентификатор модели озвучки OpenRouter. Текущая модель: elevenlabs/eleven-v4-turbo.";
            case "headMaxAngle" -> "Предельный поворот головы относительно тела в градусах.";
            case "headTurnThreshold" -> "При превышении этого угла птица начинает поворачивать тело.";
            case "spinDegreesPerTick" -> "Скорость вращения, градусов за игровой тик.";
            case "queueCapacity", "maxConcurrentJobs", "checkpointIntervalTicks", "shutdownTimeoutSeconds" -> "Изменение части параметров очередей и хранения потребует повторно открыть мир.";
            default -> "Изменение сохраняется после кнопки «Применить». ↶ возвращает стандартное значение.";
        };
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("name", "Название"), Map.entry("enabled", "Включено"), Map.entry("id", "Voice ID"),
            Map.entry("maxActiveParrots", "Максимум попугаев"), Map.entry("deathMode", "Режим смерти"),
            Map.entry("nameFormat", "Формат имени"), Map.entry("nameVisible", "Показывать имя"),
            Map.entry("permissions", "Права в игре"), Map.entry("controlLevel", "Уровень управления"), Map.entry("adminLevel", "Уровень администратора"),
            Map.entry("come", "!come — прилёт"), Map.entry("maxPathfindDistance", "Расстояние поиска пути"), Map.entry("arrivalRadius", "Радиус прибытия"),
            Map.entry("timeoutTicks", "Тайм-аут"), Map.entry("teleportIfFar", "Телепорт издалека"), Map.entry("teleportOnPathFailure", "Телепорт при застревании"), Map.entry("crossDimensionTeleport", "Телепорт между мирами"),
            Map.entry("follow", "!follow — следование"), Map.entry("startDistance", "Начинать следование"), Map.entry("stopDistance", "Останавливать следование"), Map.entry("repathTicks", "Обновление пути"),
            Map.entry("dance", "!dance — танец"), Map.entry("durationTicks", "Длительность"), Map.entry("resultTicks", "Показ результата"),
            Map.entry("cooldowns", "Базовые интервалы"), Map.entry("sit", "!sit"), Map.entry("scream", "!scream"), Map.entry("respawn", "Возрождение"),
            Map.entry("sound", "Звуки попугаев"), Map.entry("volume", "Громкость"), Map.entry("minPitch", "Минимальная высота тона"), Map.entry("maxPitch", "Максимальная высота тона"), Map.entry("allowedSounds", "Доступные звуки"),
            Map.entry("behavior", "Естественное поведение"), Map.entry("idleMinTicks", "Минимум покоя"), Map.entry("idleMaxTicks", "Максимум покоя"),
            Map.entry("dangerCheckTicks", "Проверять опасность"), Map.entry("dangerRadius", "Радиус опасности"), Map.entry("lazyMinTicks", "Минимальная задержка ленивого"), Map.entry("lazyMaxTicks", "Максимальная задержка ленивого"),
            Map.entry("normalSoundChance", "Шанс обычного крика"), Map.entry("loudSoundChance", "Шанс крика громкого"), Map.entry("traitWeights", "Веса характеров"),
            Map.entry("limits", "Общие лимиты"), Map.entry("actionsPer20Ticks", "Действий в секунду"), Map.entry("soundsPer20Ticks", "Звуков в секунду"), Map.entry("queueCapacity", "Размер очереди"),
            Map.entry("recovery", "Восстановление птиц"), Map.entry("maxConcurrentJobs", "Одновременных задач"), Map.entry("storage", "Сохранение мира"), Map.entry("checkpointIntervalTicks", "Интервал сохранения"), Map.entry("shutdownTimeoutSeconds", "Ожидание при выходе"),
            Map.entry("autoConnect", "Подключаться автоматически"), Map.entry("channel", "Канал Twitch"), Map.entry("login", "Логин аккаунта"), Map.entry("clientId", "Twitch Client ID"), Map.entry("guest", "Гостевой доступ к чату"),
            Map.entry("cooldownTicks", "Интервал команды"), Map.entry("speech", "Озвучка"), Map.entry("model", "Модель озвучки"), Map.entry("maxWords", "Максимум слов"), Map.entry("timeoutSeconds", "Ожидание ответа"),
            Map.entry("hourlyRequests", "Запросов за час"), Map.entry("hourlyWords", "Слов за час"), Map.entry("voices", "Голоса"),
            Map.entry("spinDurationTicks", "Длительность !spin"), Map.entry("spinDegreesPerTick", "Скорость !spin"), Map.entry("freefollowRadius", "Радиус !freefollow"), Map.entry("freefollowReturnDistance", "Остановка возвращения"),
            Map.entry("headMaxAngle", "Максимальный угол головы"), Map.entry("headTurnThreshold", "Начинать поворот тела"),
            Map.entry("twitchQueueCapacity", "Очередь Twitch"), Map.entry("commandsPerTick", "Команд за тик"), Map.entry("blockedViewers", "Блокировка по ID зрителя"), Map.entry("mutedViewers", "Без озвучки — ID зрителей"),
            Map.entry("weight", "Вес категории"), Map.entry("color", "Цвет категории"), Map.entry("count", "Количество"), Map.entry("itemId", "Предмет")
    );

    static final class StringListScreen extends MenuFormScreen {
        private final JsonArray values;
        private final JsonArray initial;
        private final Consumer<JsonArray> saved;
        private String next = "";
        StringListScreen(Screen parent, String title, JsonArray values, Consumer<JsonArray> saved) {
            super(parent, title);
            this.values = values;
            this.initial = values.deepCopy();
            this.saved = saved;
        }
        @Override protected void chrome() {
            search(left, 33, right - left);
            fixed(button("Сохранить список", right - 222, height - 29, 130, () -> { saved.accept(values); super.close(); }));
            fixed(button("Отмена", right - 87, height - 29, 87, this::close));
        }
        @Override protected void content() {
            Row inputRow = row("Новые записи", "ID зрителя, ID звука или другая запись. Несколько записей можно разделить запятой.");
            if (inputRow != null) textInput(inputRow, next, 128, value -> next = value);
            action("Добавление", "Пустые и повторяющиеся строки не добавляются.", "Добавить", true, () -> {
                String value = next.strip();
                if (value.isEmpty()) { notify("Введите запись", true); return; }
                for (String part : value.split("[,;]")) {
                    String entry = part.strip();
                    if (entry.isEmpty()) continue;
                    boolean duplicate = java.util.stream.StreamSupport.stream(values.spliterator(), false).anyMatch(item -> item.getAsString().equals(entry));
                    if (!duplicate) values.add(entry);
                }
                next = "";
                rebuild();
            });
            for (int i = 0; i < values.size(); i++) {
                int index = i;
                action(values.get(i).getAsString(), "Запись списка", "Удалить", true, () -> { values.remove(index); rebuild(); });
            }
        }
        @Override public void close() {
            if (!initial.equals(values)) client.setScreen(new net.minecraft.client.gui.screen.ConfirmScreen(answer -> client.setScreen(answer ? parent : this),
                    Text.literal("Отменить список?"), Text.literal("Изменения этого списка будут потеряны."), Text.literal("Отменить изменения"), Text.literal("Назад")));
            else super.close();
        }
    }
}
