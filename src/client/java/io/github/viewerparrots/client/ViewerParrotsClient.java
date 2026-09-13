package io.github.viewerparrots.client;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

/**
 * Клиентский entrypoint: только визуализация. Авторитетное поведение считает сервер.
 */
public class ViewerParrotsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.VIEWER_PARROT, ViewerParrotRenderer::new);
		ViewerParrotsMod.LOGGER.info("Viewer Parrots: client initialization complete");
	}
}
