package io.github.viewerparrots.client.menu;

import io.github.viewerparrots.client.SpeechAudioReceiver;
import io.github.viewerparrots.control.ControlPanelService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Entry points use Fabric ScreenEvents, keeping the native menus and key settings. */
public final class ControlPanelClient {
    private static boolean stopped;
    private static Screen previewReturn;
    private ControlPanelClient() { }

    public static void register() {
        ControlPanelService.initialize(FabricLoader.getInstance().getConfigDir());
        ControlPanelService.setPreviewAudio(SpeechAudioReceiver::preview);
        KeyBinding open = KeyBindingHelper.registerKeyBinding(new KeyBinding("Панель попугаев", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "Viewer Parrots"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ControlPanelService.setPaused(client.isPaused());
            while (open.wasPressed()) {
                if (client.currentScreen instanceof MenuFormScreen panel) panel.close();
                else if (previewReturn != null) { Screen resume = previewReturn; previewReturn = null; client.setScreen(resume); }
                else client.setScreen(new ControlPanelScreen(client.currentScreen));
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen) && !(screen instanceof GameMenuScreen)) return;
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Viewer Parrots"), button -> {
                if (previewReturn != null) { Screen resume = previewReturn; previewReturn = null; client.setScreen(resume); }
                else client.setScreen(new ControlPanelScreen(screen));
            }).dimensions(width - 128, 8, 120, 20).build());
        });
    }

    static boolean chatStopped() { return stopped; }
    static void rememberPreview(Screen editor) { previewReturn = editor; }
    static void toggleChatStopped() { stopped = !stopped; ControlPanelService.setChatStopped(stopped); }
}
