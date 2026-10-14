package io.github.viewerparrots.client;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.ParrotEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.util.Identifier;

import java.util.List;

/** Decorative hats follow the vanilla head transform, including its dance and flight poses. */
final class HatFeature extends FeatureRenderer<ParrotEntity, ParrotEntityModel> {
    private static final Identifier WHITE_TEXTURE = ViewerParrotsMod.id("hats/white.png");

    HatFeature(FeatureRendererContext<ParrotEntity, ParrotEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vertices, int light, ParrotEntity entity,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress,
                       float headYaw, float headPitch) {
        if (!(entity instanceof ViewerParrotEntity parrot) || entity.isInvisible()) return;
        List<HatModels.ColoredPart> hat = HatModels.get(parrot.hatId());
        if (hat == null) return;
        matrices.push();
        try {
            getContextModel().getPart().getChild("head").rotate(matrices);
            // Center of the upper skull surface, measured in vanilla model pixels.
            matrices.translate(0, -2.5F / 16.0F, -1.0F / 16.0F);
            VertexConsumer consumer = vertices.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEXTURE));
            int overlay = LivingEntityRenderer.getOverlay(entity, 0.0F);
            for (HatModels.ColoredPart part : hat) {
                part.part().render(matrices, consumer, light, overlay, part.color());
            }
        } finally {
            matrices.pop();
        }
    }
}
