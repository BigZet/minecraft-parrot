package io.github.viewerparrots.client;

import io.github.viewerparrots.cosmetic.ParrotEmotes;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.util.math.MathHelper;
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
		ViewerParrotEntity parrot = entity instanceof ViewerParrotEntity viewerParrot ? viewerParrot : null;
		if (parrot != null && parrot.isSpinning()) {
			// LivingEntityRenderer restores this model stack before rendering the name.
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(parrot.getSpinAngle(tickDelta)));
		}
		if (parrot != null && parrot.duelPhase() == 1) {
			// Потасовка: быстрое дрожание по рысканью и подпрыгивание на месте.
			float time = (float) (parrot.getWorld().getTime() % 24000L) + tickDelta;
			matrices.translate(0.0, 0.06 * Math.abs(MathHelper.sin(time * 0.9f)), 0.0);
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(15.0f * MathHelper.sin(time * 1.3f)));
		}
		float emote = parrot == null ? 0.0f : parrot.getEmoteProgress(tickDelta);
		if (parrot != null && emote > 0.0f && ParrotEmotes.SHAKE.equals(parrot.emoteId())) {
			// Рысканье не зависит от поворота тела, поэтому применяется до него.
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(20.0f * MathHelper.sin(emote * MathHelper.TAU * 2.0f)));
		}
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
		if (parrot != null && emote > 0.0f) {
			// После поворота тела ось X — поперечная ось самой птицы: наклон вперёд-назад.
			switch (parrot.emoteId()) {
				case ParrotEmotes.BOW -> matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-35.0f * MathHelper.sin(emote * MathHelper.PI)));
				case ParrotEmotes.NOD -> matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-12.0f * MathHelper.sin(emote * MathHelper.TAU * 2.0f)));
				default -> { }
			}
		}
	}
}
