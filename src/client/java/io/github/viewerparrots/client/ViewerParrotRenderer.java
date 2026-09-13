package io.github.viewerparrots.client;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ParrotEntityRenderer;

/**
 * Переиспользует ванильную модель и текстуры попугая. Поза PARTY выбирается
 * {@code ParrotEntityModel.getPose()} по переопределённому
 * {@code ViewerParrotEntity.isSongPlaying()}, то есть по серверному tracked-флагу.
 */
public class ViewerParrotRenderer extends ParrotEntityRenderer {
	public ViewerParrotRenderer(EntityRendererFactory.Context context) {
		super(context);
	}
}
