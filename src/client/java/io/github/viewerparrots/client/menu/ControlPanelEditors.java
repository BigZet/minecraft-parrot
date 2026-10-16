package io.github.viewerparrots.client.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.viewerparrots.control.ControlPanelService;
import io.github.viewerparrots.cosmetic.ParrotHats;
import io.github.viewerparrots.roulette.RouletteSettings;
import io.github.viewerparrots.settings.ModSettings;
import io.github.viewerparrots.settings.TwitchRole;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Nested editors retain their own draft and return typed values to the panel. */
final class ControlPanelEditors {
    private ControlPanelEditors() { }

    abstract static class ObjectEditor extends JsonFormScreen {
        final JsonObject initial;
        final Consumer<JsonObject> save;
        ObjectEditor(Screen parent, String title, JsonObject value, JsonObject defaults, Consumer<JsonObject> save) {
            super(parent, title, value, defaults);
            initial = value.deepCopy();
            this.save = save;
        }
        @Override protected void chrome() {
            search(left, 33, right - left);
            fixed(button("Сохранить в черновик", right - 246, height - 29, 150, this::commit));
            fixed(button("Отмена", right - 90, height - 29, 90, this::close));
        }
        void validateValue() { }
        void commit() {
            if (hasInvalidInput()) { notify(invalid.values().iterator().next(), true); return; }
            try { validateValue(); }
            catch (RuntimeException badValue) { notify("Проверьте поля: " + ControlPanelScreen.errorText(badValue), true); return; }
            save.accept(data.deepCopy());
            client.setScreen(parent);
        }
        void confirm(String title, Runnable accepted) {
            client.setScreen(new ConfirmScreen(answer -> { client.setScreen(this); if (answer) accepted.run(); },
                    Text.literal(title), Text.literal("Изменение попадёт в черновик. Настройки сохраняются кнопкой «Применить» в панели."), Text.literal("Подтвердить"), Text.literal("Отмена")));
        }
        @Override public void close() {
            if (!initial.equals(data) || hasInvalidInput()) client.setScreen(new ConfirmScreen(answer -> client.setScreen(answer ? parent : this),
                    Text.literal("Отменить изменения?"), Text.literal("Изменения этого редактора будут потеряны."), Text.literal("Отменить изменения"), Text.literal("Назад")));
            else super.close();
        }
    }

    static final class CommandEditor extends ObjectEditor {
        CommandEditor(Screen parent, String title, JsonObject rule, JsonObject defaults, Consumer<JsonObject> save) {
            super(parent, "Команда " + title, rule, defaults, save);
        }
        @Override protected void content() {
            field("enabled", "Доступна в чате", "Выключенная команда не выполняется независимо от роли.");
            field("cooldownTicks", "Общий интервал", "Пауза между командами одного зрителя, в секундах.");
            heading("Кому разрешена команда");
            info("Все зрители", "Разрешает команду каждому; остальные роли уточняют доступ");
            JsonArray roles = data.getAsJsonArray("roles");
            for (TwitchRole role : TwitchRole.values()) {
                boolean allowed = contains(roles, role.name());
                action(enumLabel(role.name()), "Разрешение для роли " + enumLabel(role.name()), allowed ? "Разрешено" : "Запрещено", true, () -> {
                    if (contains(roles, role.name())) for (int i = roles.size() - 1; i >= 0; i--) { if (roles.get(i).getAsString().equals(role.name())) roles.remove(i); }
                    else roles.add(role.name());
                    rebuild();
                });
            }
            heading("Интервалы по ролям");
            JsonObject overrides = data.getAsJsonObject("roleCooldownTicks");
            for (TwitchRole role : List.of(TwitchRole.BROADCASTER, TwitchRole.MODERATOR, TwitchRole.VIP, TwitchRole.SUBSCRIBER)) {
                String name = role.name();
                if (!overrides.has(name)) action(enumLabel(name), "Если отдельный интервал не задан, используется общий.", "Как у всех · изменить", true, () -> {
                    overrides.add(name, data.get("cooldownTicks").deepCopy()); rebuild();
                });
                else {
                    field("roleCooldownTicks." + name, enumLabel(name), "Личный интервал команды для этой роли в секундах.");
                    action("Сброс: " + enumLabel(name), "Вернуться к общему интервалу.", "Использовать общий", true, () -> {
                        overrides.remove(name); clearInputErrors(); rebuild();
                    });
                }
            }
        }
        @Override void validateValue() {
            var roles = new HashSet<TwitchRole>();
            for (JsonElement role : data.getAsJsonArray("roles")) roles.add(TwitchRole.valueOf(role.getAsString()));
            var overrides = new EnumMap<TwitchRole, Integer>(TwitchRole.class);
            data.getAsJsonObject("roleCooldownTicks").entrySet().forEach(entry -> overrides.put(TwitchRole.valueOf(entry.getKey()), entry.getValue().getAsBigDecimal().intValueExact()));
            new ModSettings.CommandRule(data.get("enabled").getAsBoolean(), roles, data.get("cooldownTicks").getAsBigDecimal().intValueExact(), overrides);
        }
    }

    static final class VoiceEditor extends ObjectEditor {
        private final ControlPanelScreen owner;
        private final Runnable delete;
        private String phrase = "Привет! Я твой попугай, и я готов к приключениям!";
        VoiceEditor(ControlPanelScreen owner, JsonObject voice, Consumer<JsonObject> save, Runnable delete) {
            super(owner, "Голос попугая", voice, voice.deepCopy(), save);
            this.owner = owner;
            this.delete = delete;
        }
        @Override protected void content() {
            field("name", "Название голоса", "Ваше имя голоса в списке.");
            field("id", "ElevenLabs Voice ID", "Публичный Voice ID из библиотеки ElevenLabs.");
            field("enabled", "Использовать для птиц", "Выключенный голос не выдаётся новым птицам.");
            Row text = row("Пробная фраза", "Прослушивание использует платный запрос OpenRouter и учитывается в часовом лимите.");
            if (text != null) textInput(text, phrase, 1000, value -> phrase = value);
            action("Прослушать голос", "Меню закроется. Черновик сохранится до возврата через F8 или кнопку Viewer Parrots. Запрос платный.", "Прослушать (платно)", true, () -> {
                try { validateValue(); }
                catch (RuntimeException invalid) { notify("Проверьте Voice ID", true); return; }
                String id = data.get("id").getAsString();
                String textValue = phrase;
                ControlPanelClient.rememberPreview(this);
                owner.worldTask(() -> ControlPanelService.previewVoice(id, textValue));
            });
            if (delete != null) action("Удаление голоса", "Другие голоса останутся в списке.", "Удалить…", true,
                    () -> confirm("Удалить голос?", () -> { delete.run(); client.setScreen(parent); }));
        }
        @Override void validateValue() {
            new ModSettings.Voice(data.get("id").getAsString(), data.get("name").getAsString(), data.get("enabled").getAsBoolean());
        }
    }

    static final class CategoryEditor extends ObjectEditor {
        private final Runnable delete;
        CategoryEditor(Screen parent, JsonObject value, Consumer<JsonObject> save, Runnable delete) {
            super(parent, "Категория призов", value, value.deepCopy(), save);
            this.delete = delete;
        }
        @Override protected void content() {
            field("name"); field("weight"); field("color");
            info("Расчёт шансов", "Итоговый шанс отображается в списке категорий");
            JsonArray prizes = data.getAsJsonArray("items");
            action("Новый приз", "В категории может быть от 1 до 64 призов.", "Добавить предмет", prizes.size() < 64, () -> {
                JsonObject prize = new JsonObject(); prize.addProperty("itemId", "minecraft:wheat_seeds"); prize.addProperty("count", 1);
                editPrize(prize, -1);
            });
            for (int i = 0; i < prizes.size(); i++) {
                int index = i;
                JsonObject prize = prizes.get(i).getAsJsonObject();
                ItemStack stack = item(prize.get("itemId").getAsString());
                Row row = row("×" + prize.get("count").getAsInt() + " " + stack.getName().getString(), prize.get("itemId").getAsString() + ". Вероятность внутри категории: " + String.format(Locale.ROOT, "%.2f%%", 100.0 / prizes.size()));
                if (row != null) {
                    row.icon = stack;
                    widget(row, button("Изменить", controlX(), 0, controlWidth(), () -> editPrize(prize, index)));
                }
            }
            if (delete != null) action("Удаление категории", "Удалить категорию вместе с призами.", "Удалить…", true,
                    () -> confirm("Удалить категорию?", () -> { delete.run(); client.setScreen(parent); }));
        }
        private void editPrize(JsonObject prize, int index) {
            JsonArray prizes = data.getAsJsonArray("items");
            client.setScreen(new PrizeEditor(this, prize.deepCopy(), changed -> {
                if (index < 0) prizes.add(changed); else prizes.set(index, changed); rebuild();
            }, index < 0 || prizes.size() == 1 ? null : () -> { prizes.remove(index); rebuild(); }));
        }
        @Override void validateValue() {
            List<RouletteSettings.Prize> prizes = new ArrayList<>();
            for (JsonElement value : data.getAsJsonArray("items")) {
                JsonObject prize = value.getAsJsonObject();
                prizes.add(new RouletteSettings.Prize(prize.get("itemId").getAsString(), prize.get("count").getAsBigDecimal().intValueExact()));
            }
            new RouletteSettings.Category(data.get("name").getAsString(), data.get("weight").getAsBigDecimal().intValueExact(), data.get("color").getAsInt(), prizes);
        }
    }

    static final class PrizeEditor extends ObjectEditor {
        private final Runnable delete;
        PrizeEditor(Screen parent, JsonObject prize, Consumer<JsonObject> save, Runnable delete) {
            super(parent, "Приз рулетки", prize, prize.deepCopy(), save);
            this.delete = delete;
        }
        @Override protected void content() {
            ItemStack stack = item(data.get("itemId").getAsString());
            Row row = row("Предмет", data.get("itemId").getAsString());
            if (row != null) {
                row.icon = stack;
                widget(row, button(stack.getName().getString(), controlX(), 0, controlWidth(), () -> client.setScreen(new ItemPicker(this, chosen -> {
                    data.addProperty("itemId", chosen); rebuild();
                }))));
            }
            field("count", "Количество", "От 1 до 64 предметов за выигрыш.");
            if (delete != null) action("Удаление приза", "Остальные предметы категории останутся.", "Удалить…", true,
                    () -> confirm("Удалить приз?", () -> { delete.run(); client.setScreen(parent); }));
        }
        @Override void validateValue() {
            String id = data.get("itemId").getAsString();
            new RouletteSettings.Prize(id, data.get("count").getAsBigDecimal().intValueExact());
            if (item(id).isEmpty()) throw new IllegalArgumentException("Выберите существующий предмет");
        }
    }

    static final class ItemPicker extends MenuFormScreen {
        private final Consumer<String> selected;
        private final List<Item> items;
        private int page;
        ItemPicker(Screen parent, Consumer<String> selected) {
            super(parent, "Выбрать предмет");
            this.selected = selected;
            items = Registries.ITEM.stream().filter(item -> item != Items.AIR)
                    .sorted(Comparator.comparing(item -> item.getName().getString())).toList();
        }
        @Override protected void content() {
            List<Item> filtered = items.stream().filter(item -> matches(item.getName().getString() + " " + Registries.ITEM.getId(item))).toList();
            int pages = Math.max(1, (filtered.size() + 79) / 80);
            page = Math.max(0, Math.min(page, pages - 1));
            // Paging controls are not filtered by the item query.
            String remembered = query; query = "";
            actions("Страница " + (page + 1) + " из " + pages, "Найдено предметов: " + filtered.size(), new String[]{"← Назад", "Далее →"}, new Runnable[]{
                    () -> { page = Math.max(0, page - 1); scroll = 0; rebuild(); },
                    () -> { page = Math.min(pages - 1, page + 1); scroll = 0; rebuild(); }}, true);
            query = remembered;
            for (Item chosen : filtered.subList(page * 80, Math.min(filtered.size(), (page + 1) * 80))) {
                String id = Registries.ITEM.getId(chosen).toString();
                Row row = row(chosen.getName().getString(), id);
                if (row == null) continue;
                row.icon = new ItemStack(chosen);
                widget(row, button("Выбрать", controlX(), 0, controlWidth(), () -> { selected.accept(id); client.setScreen(parent); }));
            }
        }
    }

    static final class BirdEditor extends MenuFormScreen {
        private final ControlPanelScreen owner;
        private final ControlPanelService.Bird bird;
        private String name, say = "Привет!", trait = "NORMAL";
        BirdEditor(ControlPanelScreen owner, ControlPanelService.Bird bird) {
            super(owner, "Попугай: " + bird.displayName());
            this.owner = owner; this.bird = bird; name = bird.displayName();
        }
        @Override protected void content() {
            info("Владелец", bird.viewerId());
            info("Состояние", bird.state() + " · " + ControlPanelScreen.mode(bird.behavior()));
            info("Плечо", bird.shoulder().isBlank() ? "Не на плече" : bird.shoulder());
            info("Голос", bird.voice().isBlank() ? "Не назначен" : bird.voice());
            info("Шляпа", bird.hat().isBlank() ? "Нет" : bird.hat());
            for (String action : List.of("COME", "FOLLOW", "FREE", "FREEFOLLOW", "VIEW", "DEVIEW", "ARM", "DEARM", "SIT", "DANCE", "SPIN", "SCREAM", "LOVE", "HAT", "DEHAT", "EXP", "ROULETTE")) {
                action(ControlPanelScreen.commandDescription(action), "Меню закроется перед действием.", ControlPanelScreen.commandLabel(action), owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), action, "", false));
            }
            heading("Личные настройки");
            Row rename = row("Новое имя", "Изменить видимое имя этой птицы.");
            if (rename != null) textInput(rename, name, 64, value -> name = value);
            action("Имя попугая", "Применяется сразу в мире.", "Переименовать", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "RENAME", name, false));
            action("Характер", "Выберите характер, затем назначьте его птице.", JsonFormScreen.enumLabel(trait) + " · сменить", true, () -> {
                List<String> traits = List.of("NORMAL", "COWARD", "LAZY", "LOUD"); trait = traits.get((traits.indexOf(trait) + 1) % traits.size()); rebuild();
            });
            action("Выбранный характер", "Применяется к этой птице.", "Назначить", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "TRAIT", trait, false));
            for (JsonElement value : owner.profile().getAsJsonObject("speech").getAsJsonArray("voices")) {
                JsonObject voice = value.getAsJsonObject();
                action("Голос: " + voice.get("name").getAsString(), voice.get("id").getAsString(), "Назначить голос", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "VOICE", voice.get("id").getAsString(), false));
            }
            // Use the same registry as the renderer, so the picker cannot invent a hat ID.
            for (String hat : hatIds()) action("Шляпа: " + hat, "Применить определённую шляпу.", "Надеть", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "HAT_SELECT", hat, false));
            heading("Озвучка");
            Row speech = row("Текст реплики", "Платная озвучка OpenRouter.");
            if (speech != null) textInput(speech, say, 2000, value -> say = value);
            action("Произнести", "Меню закроется. Используется платная озвучка.", "!say (платно)", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "SAY", say, false));
            heading("Текущие интервалы");
            if (bird.cooldownTicks().isEmpty()) info("Все команды", "Готовы к выполнению");
            else bird.cooldownTicks().forEach((action, ticks) -> info(ControlPanelScreen.commandLabel(action), String.format(Locale.ROOT, "%.1f сек.", ticks / 20.0)));
            action("Сброс интервалов", "Снять все текущие задержки этой птицы.", "Сбросить", owner.worldOpen(), () -> owner.worldTask(() -> ControlPanelService.resetCooldowns(bird.viewerId())));
            heading("Восстановление и удаление");
            action("Возродить", "Для умершей птицы, если режим смерти разрешает возрождение.", "Возродить", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "RESPAWN", "", false));
            action("Удалить птицу", "Удаляет запись и попугая владельца.", "Удалить…", owner.worldOpen(), () -> owner.runWorld(bird.viewerId(), "REMOVE", "", true));
        }
    }

    static final class CreateBirdScreen extends MenuFormScreen {
        private final ControlPanelScreen owner;
        private String viewer = "local:test", name = "Тестовый попугай";
        CreateBirdScreen(ControlPanelScreen owner) { super(owner, "Создать птицу вручную"); this.owner = owner; }
        @Override protected void content() {
            Row login = row("Владелец", "Логин или ID владельца птицы. Для теста подойдёт local:test.");
            if (login != null) textInput(login, viewer, 64, value -> viewer = value);
            Row display = row("Имя птицы", "Видимое имя над попугаем.");
            if (display != null) textInput(display, name, 64, value -> name = value);
            action("Создание", "Меню закроется перед появлением птицы.", "Создать попугая", owner.worldOpen(), () -> owner.runWorld(viewer, "CREATE", name, false));
        }
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement entry : array) if (entry.getAsString().equals(value)) return true;
        return false;
    }

    private static ItemStack item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null || !Registries.ITEM.containsId(identifier) ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(identifier));
    }

    private static List<String> hatIds() {
        return ParrotHats.IDS;
    }
}
