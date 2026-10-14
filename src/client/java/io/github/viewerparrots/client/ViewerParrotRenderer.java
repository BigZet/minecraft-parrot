package io.github.viewerparrots.client;

import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ParrotEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.util.math.RotationAxis;

/**
 * Переиспользует ванильную модель и текстуры попугая. Поза PARTY выбирается
 * {@code ParrotEntityModel.getPose()} по переопределённому
 * {@code ViewerParrotEntity.isSongPlaying()}, то есть по серверному tracked-флагу.
 */
public class ViewerParrotRenderer extends ParrotEntityRenderer {
	public ViewerParrotRenderer(EntityRendererFactory.Context context) {
		super(context);
		addFeature(new HatFeature(this));
	}

	@Override
	public void render(ParrotEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertices, int light) {
		ModelPart feather = getModel().getPart().getChild("head").getChild("feather");
		boolean featherVisible = feather.visible;
		try {
			if (entity instanceof ViewerParrotEntity parrot && HatModels.contains(parrot.hatId())) {
				feather.visible = false;
			}
			super.render(entity, yaw, tickDelta, matrices, vertices, light);
		} finally {
			// The renderer's model is shared by birds; hiding a crest must never leak to the next one.
			feather.visible = featherVisible;
		}
	}

	@Override
	protected void setupTransforms(ParrotEntity entity, MatrixStack matrices, float animationProgress,
			float bodyYaw, float tickDelta, float scale) {
		if (entity instanceof ViewerParrotEntity parrot && parrot.isSpinning()) {
			// LivingEntityRenderer restores this model stack before rendering the name.
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(parrot.getSpinAngle(tickDelta)));
		}
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
	}
}
