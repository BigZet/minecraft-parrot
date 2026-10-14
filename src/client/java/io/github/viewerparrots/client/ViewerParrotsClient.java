package io.github.viewerparrots.client;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.render.entity.PlayerEntityRenderer;

/**
 * Клиентский entrypoint: renderer и проверка совместимости при входе.
 * Авторитетное поведение считает сервер.
 */
public class ViewerParrotsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCompatibilityReceiver.register();
		SpeechAudioReceiver.register();
		EntityRendererRegistry.register(ModEntities.VIEWER_PARROT, ViewerParrotRenderer::new);
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof PlayerEntityRenderer playerRenderer)
				helper.register(new ViewerShoulderFeature(playerRenderer, context.getModelLoader()));
		});
		ViewerParrotsMod.LOGGER.info("Viewer Parrots: client initialization complete");
	}
}
