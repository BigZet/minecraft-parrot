package io.github.viewerparrots.client.menu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small native-widget form. Scrolling clips both drawing and hit testing. */
abstract class MenuFormScreen extends Screen {
    protected final Screen parent;
    protected int left, top, right, bottom;
    protected double scroll;
    protected String query = "";
    protected String message = "";
    protected boolean messageError;
    private final List<Row> rows = new ArrayList<>();
    private final List<ClickableWidget> fixed = new ArrayList<>();
    private boolean rebuilding;

    protected static final class Row {
        final int offset;
        final String label, help;
        final List<ClickableWidget> widgets = new ArrayList<>();
        String value;
        ItemStack icon;
        boolean heading;
        Row(int offset, String label, String help) {
            this.offset = offset;
            this.label = label;
            this.help = help;
        }
    }

    protected MenuFormScreen(Screen parent, String title) {
        super(Text.literal(title));
        this.parent = parent;
    }

    @Override protected void init() { rebuild(); }

    protected void bounds() {
        left = Math.max(10, (width - 660) / 2);
        right = width - left;
        top = 61;
        bottom = height - 43;
    }

    protected final void rebuild() {
        if (client == null || rebuilding) return;
        rebuilding = true;
        clearChildren();
        rows.clear();
        fixed.clear();
        bounds();
        chrome();
        content();
        layoutRows();
        rebuilding = false;
    }

    protected void chrome() {
        search(left, 33, right - left);
        fixed(button("Готово", right - 110, height - 29, 110, this::close));
    }

    protected abstract void content();

    protected final void search(int x, int y, int size) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, size, 20, Text.literal("Поиск"));
        field.setPlaceholder(Text.literal("Поиск в этом разделе…"));
        field.setText(query);
        field.setChangedListener(value -> {
            // TextFieldWidget also notifies when only its cursor changes during focus restoration.
            if (query.equals(value)) return;
            query = value;
            scroll = 0;
            rebuild();
            // Rebuilding is deferred neither to a world tick nor to the server.
            for (ClickableWidget widget : fixed) {
                if (widget instanceof TextFieldWidget text && text.getMessage().getString().equals("Поиск")) {
                    setFocused(text);
                    text.setCursorToEnd(false);
                    break;
                }
            }
        });
        fixed(field);
    }

    protected final boolean matches(String text) {
        return query.isBlank() || text.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }

    protected final Row row(String label, String help) {
        if (!matches(label + " " + help)) return null;
        Row row = new Row(rows.size() * 30, label, help);
        rows.add(row);
        return row;
    }

    protected final void heading(String label) {
        if (!query.isBlank()) return;
        Row row = row(label, "");
        if (row != null) row.heading = true;
    }

    protected final void info(String label, String value) {
        Row row = row(label, value);
        if (row != null) row.value = value;
    }

    protected final int controlX() { return left + Math.min(230, Math.max(90, (right - left) * 42 / 100)); }
    protected final int controlWidth() { return Math.max(50, right - controlX() - 10); }

    protected final <T extends ClickableWidget> T fixed(T widget) {
        fixed.add(widget);
        return addDrawableChild(widget);
    }

    protected final <T extends ClickableWidget> T widget(Row row, T widget) {
        row.widgets.add(widget);
        if (!row.help.isBlank()) widget.setTooltip(Tooltip.of(Text.literal(row.label + "\n" + row.help)));
        return addDrawableChild(widget);
    }

    protected final ButtonWidget button(String label, int x, int y, int size, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), pressed -> action.run()).dimensions(x, y, Math.max(18, size), 20).build();
    }

    protected final void action(String label, String help, String caption, boolean enabled, Runnable action) {
        Row row = row(label, help + " " + caption);
        if (row == null) return;
        ButtonWidget button = widget(row, button(caption, controlX(), 0, controlWidth(), action));
        button.active = enabled;
    }

    protected final void actions(String label, String help, String[] captions, Runnable[] callbacks, boolean enabled) {
        Row row = row(label, help + " " + String.join(" ", captions));
        if (row == null) return;
        int size = (controlWidth() - (captions.length - 1) * 3) / captions.length;
        for (int i = 0; i < captions.length; i++) {
            ButtonWidget button = widget(row, button(captions[i], controlX() + i * (size + 3), 0, size, callbacks[i]));
            button.active = enabled;
        }
    }

    protected final TextFieldWidget textInput(Row row, String value, int maxLength, Consumer<String> changed) {
        TextFieldWidget field = widget(row, new TextFieldWidget(textRenderer, controlX(), 0, controlWidth(), 20, Text.literal(row.label)));
        field.setMaxLength(maxLength);
        field.setText(value);
        field.setChangedListener(changed);
        return field;
    }

    protected final void notify(String value, boolean error) {
        message = value == null ? "" : value;
        messageError = error;
    }

    private void layoutRows() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * 30 - (bottom - top))));
        for (Row row : rows) {
            int y = top + row.offset - (int) scroll;
            for (ClickableWidget widget : row.widgets) {
                widget.setY(y + 3);
                widget.visible = y >= top && y + 26 <= bottom;
            }
        }
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            scroll -= verticalAmount * 30;
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xDD11151C);
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(title.getString(), right - left), left, 14, 0xF6CE6B);
        context.fill(left - 3, top - 3, right + 3, bottom + 3, 0xB019202A);
        layoutRows();
        context.enableScissor(left, top, right, bottom);
        Row hovered = null;
        for (Row row : rows) {
            int y = top + row.offset - (int) scroll;
            if (y + 30 < top || y > bottom) continue;
            if (row.heading) {
                context.drawTextWithShadow(textRenderer, row.label, left + 5, y + 10, 0xF6CE6B);
                context.fill(left + 5, y + 24, right - 6, y + 25, 0x665D6570);
            } else {
                context.fill(left, y + 29, right - 5, y + 30, 0x303F4857);
                int labelX = left + 5;
                if (row.icon != null) { context.drawItem(row.icon, labelX, y + 6); labelX += 21; }
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.label, Math.max(20, controlX() - labelX - 7)), labelX, y + 10, 0xE4E8EF);
                if (row.value != null) context.drawTextWithShadow(textRenderer,
                        textRenderer.trimToWidth(row.value, controlWidth()), controlX(), y + 10, 0xAFC0D5);
                if (mouseX >= left && mouseX < controlX() && mouseY >= Math.max(y, top) && mouseY < Math.min(y + 30, bottom)) hovered = row;
            }
            for (ClickableWidget widget : row.widgets) if (widget.visible) widget.render(context, mouseX, mouseY, delta);
        }
        context.disableScissor();
        for (ClickableWidget widget : fixed) widget.render(context, mouseX, mouseY, delta);
        if (rows.size() * 30 > bottom - top) {
            int track = bottom - top;
            int thumb = Math.max(12, track * track / (rows.size() * 30));
            int y = top + (int) (scroll / Math.max(1, rows.size() * 30 - track) * (track - thumb));
            context.fill(right, top, right + 2, bottom, 0x884A5362);
            context.fill(right, y, right + 2, y + thumb, 0xFFF6CE6B);
        }
        if (!message.isBlank()) {
            int firstFooterButton = fixed.stream().filter(widget -> widget.getY() >= height - 35)
                    .mapToInt(ClickableWidget::getX).min().orElse(width - 10);
            int max = Math.max(24, firstFooterButton - 18);
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(message, max), 10, height - 20, messageError ? 0xFF8F86 : 0xB6E9AE);
            if (mouseY > height - 32 && mouseX < max + 10) context.drawTooltip(textRenderer, Text.literal(message), mouseX, mouseY);
        }
        if (hovered != null) context.drawTooltip(textRenderer, Text.literal(hovered.label + (hovered.help.isBlank() ? "" : "\n" + hovered.help)), mouseX, mouseY);
    }

    @Override public boolean shouldPause() { return true; }
    @Override public void close() { if (client != null) client.setScreen(parent); }
}
