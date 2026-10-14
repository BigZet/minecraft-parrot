package io.github.viewerparrots.client;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.behavior.ViewTrackingPolicy;
import io.github.viewerparrots.entity.ModEntities;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.ParrotEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EntityModelLoader;
import net.minecraft.client.render.entity.model.ParrotEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.List;

/** Vanilla shoulder pose for our entity type, with its saved name, hat and view flag. */
final class ViewerShoulderFeature extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier WHITE_TEXTURE = ViewerParrotsMod.id("hats/white.png");
    private final ParrotEntityModel model;

    ViewerShoulderFeature(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context,
                          EntityModelLoader loader) {
        super(context);
        model = new ParrotEntityModel(loader.getModelPart(EntityModelLayers.PARROT));
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vertices, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        if (player.isInvisible()) return;
        renderShoulder(matrices, vertices, light, player, limbAngle, limbDistance, headYaw, headPitch, true);
        renderShoulder(matrices, vertices, light, player, limbAngle, limbDistance, headYaw, headPitch, false);
    }

    private void renderShoulder(MatrixStack matrices, VertexConsumerProvider vertices, int light, PlayerEntity player,
                                float limbAngle, float limbDistance, float headYaw, float headPitch, boolean left) {
        NbtCompound nbt = left ? player.getShoulderEntityLeft() : player.getShoulderEntityRight();
        if (!isViewerParrot(nbt)) return;
        NbtCompound marker = nbt.getCompound(ViewerParrotsMod.MOD_ID);
        List<HatModels.ColoredPart> hat = HatModels.get(marker.getString("hatId"));
        if (marker.getBoolean("viewingStreamer")) {
            Vec3d direction = player.getEyePos().subtract(shoulderPosition(player, left).add(0, 0.35, 0));
            float targetYaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
            headYaw = MathHelper.clamp(MathHelper.wrapDegrees(targetYaw - player.bodyYaw),
                    -ViewTrackingPolicy.MAX_HEAD_YAW, ViewTrackingPolicy.MAX_HEAD_YAW);
            headPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalLength())),
                    -ViewTrackingPolicy.MAX_HEAD_PITCH, ViewTrackingPolicy.MAX_HEAD_PITCH);
        }
        ModelPart feather = model.getPart().getChild("head").getChild("feather");
        boolean featherVisible = feather.visible;
        matrices.push();
        try {
            matrices.translate(left ? 0.4F : -0.4F, player.isInSneakingPose() ? -1.3F : -1.5F, 0);
            feather.visible = hat == null;
            var texture = ParrotEntityRenderer.getTexture(ParrotEntity.Variant.byIndex(nbt.getInt("Variant")));
            model.poseOnShoulder(matrices, vertices.getBuffer(model.getLayer(texture)), light, OverlayTexture.DEFAULT_UV,
                    limbAngle, limbDistance, headYaw, headPitch, player.age);
            if (hat != null) renderHat(matrices, vertices, light, hat);
            renderName(matrices, vertices, light, player, nbt);
        } finally {
            feather.visible = featherVisible;
            matrices.pop();
        }
    }

    private void renderHat(MatrixStack matrices, VertexConsumerProvider vertices, int light, List<HatModels.ColoredPart> hat) {
        matrices.push();
        try {
            model.getPart().getChild("head").rotate(matrices);
            matrices.translate(0, -2.5F / 16, -1.0F / 16);
            VertexConsumer consumer = vertices.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE_TEXTURE));
            for (HatModels.ColoredPart part : hat) part.part().render(matrices, consumer, light, OverlayTexture.DEFAULT_UV, part.color());
        } finally {
            matrices.pop();
        }
    }

    private static void renderName(MatrixStack matrices, VertexConsumerProvider vertices, int light, PlayerEntity player, NbtCompound nbt) {
        if (!nbt.getBoolean("CustomNameVisible") || !nbt.contains("CustomName")) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getEntityRenderDispatcher().getSquaredDistanceToCamera(player) > 4096) return;
        Text name = Text.Serialization.fromJson(nbt.getString("CustomName"), player.getRegistryManager());
        if (name == null) return;
        matrices.push();
        try {
            matrices.translate(0, 0.45, -0.17);
            // Undo the player's model rotation before applying the same camera billboard as entity names.
            matrices.multiply(new Quaternionf().setFromUnnormalized(matrices.peek().getNormalMatrix()).conjugate());
            matrices.multiply(client.getEntityRenderDispatcher().getRotation());
            matrices.scale(0.025F, -0.025F, 0.025F);
            float x = -client.textRenderer.getWidth(name) / 2.0F;
            int background = (int) (client.options.getTextBackgroundOpacity(0.25F) * 255) << 24;
            client.textRenderer.draw(name, x, 0, 0x20FFFFFF, false, matrices.peek().getPositionMatrix(), vertices,
                    TextRenderer.TextLayerType.SEE_THROUGH, background, light);
            client.textRenderer.draw(name, x, 0, 0xFFFFFFFF, false, matrices.peek().getPositionMatrix(), vertices,
                    TextRenderer.TextLayerType.NORMAL, 0, light);
        } finally {
            matrices.pop();
        }
    }

    static boolean isViewerParrot(NbtCompound nbt) {
        return EntityType.get(nbt.getString("id")).filter(type -> type == ModEntities.VIEWER_PARROT).isPresent();
    }

    static Vec3d shoulderPosition(PlayerEntity player, boolean left) {
        double side = left ? 0.4 : -0.4;
        double yaw = Math.toRadians(player.bodyYaw);
        return player.getPos().add(Math.cos(yaw) * side, player.isInSneakingPose() ? 1.3 : 1.5, Math.sin(yaw) * side);
    }
}
