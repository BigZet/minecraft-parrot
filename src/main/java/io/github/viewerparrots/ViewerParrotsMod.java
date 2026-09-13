package io.github.viewerparrots;

import io.github.viewerparrots.bootstrap.FabricLifecycleAdapter;
import io.github.viewerparrots.command.M0DebugCommands;
import io.github.viewerparrots.command.ViewerParrotCommands;
import io.github.viewerparrots.entity.ModEntities;
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
		ModEntities.register();
		FabricLifecycleAdapter.register();
		ViewerParrotCommands.register();
		M0DebugCommands.register();
		LOGGER.info("Viewer Parrots: common initialization complete");
	}
}
