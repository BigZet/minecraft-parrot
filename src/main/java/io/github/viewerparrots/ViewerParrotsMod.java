package io.github.viewerparrots;

import io.github.viewerparrots.bootstrap.FabricLifecycleAdapter;
import io.github.viewerparrots.command.ViewerParrotCommands;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.network.ClientCompatibility;
import io.github.viewerparrots.network.SpeechAudioPayload;
import io.github.viewerparrots.network.RoulettePayload;
import io.github.viewerparrots.service.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common/server entrypoint. Не должен ссылаться на клиентские классы:
 * клиентский код живёт в отдельном source set {@code src/client}.
 */
public class ViewerParrotsMod implements ModInitializer {
	public static final String MOD_ID = "viewerparrots";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		long started = AuditLog.started();
		io.github.viewerparrots.control.ControlPanelService.initialize(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
		ModEntities.register();
		ModSounds.register();
		SpeechAudioPayload.register();
		RoulettePayload.register();
		ClientCompatibility.register();
		FabricLifecycleAdapter.register();
		ViewerParrotCommands.register();
		io.github.viewerparrots.cage.CageJail.install();
		AuditLog.info("common_initialized", null, "READY", null, started,
				java.util.Map.of("minecraft", "1.21.1"), null);
	}
}
