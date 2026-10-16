package io.github.viewerparrots.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.network.RoulettePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.util.UUID;

/** A five-icon world-space reel. The server owns both its result and its world-clock start. */
public final class RouletteRenderer {
	private static final int CELL = 24;
	private static final int HALF_WIDTH = CELL * 5 / 2;
	private static final float PIXEL_SCALE = 1.0F / 60;
	private static final double ABOVE_BIRD = 1.2;
	private static final int GOLD = 0xFFFFD36D;
	private static final int LIGHT = 15728880;
	private static final VertexConsumerProvider.Immediate VERTICES =
			VertexConsumerProvider.immediate(new BufferAllocator(64 * 1024));
	private static ClientWorld receivingWorld;
	private static Reel active;

	private RouletteRenderer() { }

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RoulettePayload.ID, (payload, context) -> receive(context.client(), payload));
		ClientTickEvents.END_CLIENT_TICK.register(RouletteRenderer::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		WorldRenderEvents.AFTER_ENTITIES.register(RouletteRenderer::render);
	}

	private static void receive(MinecraftClient client, RoulettePayload payload) {
		updateWorld(client);
		if (payload.isCancellation()) {
			if (active != null && active.payload.spinId().equals(payload.spinId())) active = null;
			return;
		}
		if (receivingWorld == null || !payload.isValid()) return;
		ItemStack[] icons = new ItemStack[payload.itemIds().size()];
		for (int index = 0; index < icons.length; index++) {
			Identifier id = Identifier.tryParse(payload.itemIds().get(index));
			if (id == null || !Registries.ITEM.containsId(id)) return;
			icons[index] = new ItemStack(Registries.ITEM.get(id));
			if (icons[index].isEmpty()) return;
		}
		// There is one global roulette; a newer server reel replaces an expired or cancelled one.
		active = new Reel(payload, icons);
	}

	private static void tick(MinecraftClient client) {
		updateWorld(client);
		if (active != null && receivingWorld.getTime() - active.payload.startTick()
				>= active.payload.durationTicks() + active.payload.resultTicks()) active = null;
	}

	private static void updateWorld(MinecraftClient client) {
		if (receivingWorld != client.world) {
			clear();
			receivingWorld = client.world;
		}
	}

	private static void clear() { active = null; receivingWorld = null; }

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		updateWorld(client);
		Reel reel = active;
		MatrixStack matrices = context.matrixStack();
		if (reel == null || matrices == null || receivingWorld == null) return;
		float tickDelta = context.tickCounter().getTickDelta(false);
		double elapsed = receivingWorld.getTime() - reel.payload.startTick() + tickDelta;
		if (elapsed < 0 || elapsed >= reel.payload.durationTicks() + reel.payload.resultTicks()) return;
		Vec3d anchor = anchorPosition(receivingWorld, reel.payload.parrotUuid(), tickDelta);
		if (anchor == null || anchor.squaredDistanceTo(context.camera().getPos()) > 128 * 128) return;
		Vec3d relative = anchor.subtract(context.camera().getPos());
		boolean result = elapsed >= reel.payload.durationTicks();
		double progress = MathHelper.clamp(elapsed / reel.payload.durationTicks(), 0, 1);
		double eased = 1 - Math.pow(1 - progress, 3);
		double initialIndex = Math.min(2, reel.payload.prizeIndex());
		double center = initialIndex + (reel.payload.prizeIndex() - initialIndex) * eased;
		matrices.push();
		try {
			matrices.translate(relative.x, relative.y, relative.z);
			matrices.multiply(client.getEntityRenderDispatcher().getRotation());
			matrices.scale(PIXEL_SCALE, -PIXEL_SCALE, PIXEL_SCALE);
			int[] clip = clippingRectangle(client, matrices.peek().getPositionMatrix());
			if (clip == null) return;
			renderPanel(matrices, result && reel.payload.jackpot());
			VERTICES.draw();
			renderIcons(client, matrices, reel, center, clip);
			renderFrame(matrices, result ? 0xFF000000 | reel.payload.rarityColor() : GOLD);
			VERTICES.draw();
		} finally {
			matrices.pop();
		}
	}

	private static void renderPanel(MatrixStack matrices, boolean jackpot) {
		quad(matrices, -HALF_WIDTH - 3, -17, HALF_WIDTH + 3, 17, -12, 0xCB10151D);
		border(matrices, -HALF_WIDTH - 3, -17, HALF_WIDTH + 3, 17, -10, jackpot ? 0xFFFFEDAD : GOLD, 1.5F);
	}

	private static void renderFrame(MatrixStack matrices, int color) {
		border(matrices, -CELL / 2.0F, -15, CELL / 2.0F, 15, 12, color, 1.5F);
		// A small pixel-art arrow points down to the item that will actually be awarded.
		quad(matrices, -4, -25, 4, -23, 12, GOLD);
		quad(matrices, -3, -23, 3, -21, 12, GOLD);
		quad(matrices, -2, -21, 2, -19, 12, GOLD);
		quad(matrices, -1, -19, 1, -17, 12, GOLD);
	}

	private static void renderIcons(MinecraftClient client, MatrixStack matrices, Reel reel, double center, int[] clip) {
		boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		int[] previous = new int[4];
		if (hadScissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previous);
		RenderSystem.enableScissor(clip[0], clip[1], clip[2], clip[3]);
		try {
			int first = Math.max(0, (int) Math.floor(center) - 3);
			int last = Math.min(reel.icons.length - 1, (int) Math.floor(center) + 3);
			for (int index = first; index <= last; index++) {
				float x = (float) ((index - center) * CELL);
				quad(matrices, x - CELL / 2.0F + 1, -13, x + CELL / 2.0F - 1, 13, -11, 0x501F2936);
				matrices.push();
				try {
					matrices.translate(x, 0, 0);
					matrices.scale(18, -18, 18);
					client.getItemRenderer().renderItem(reel.icons[index], ModelTransformationMode.GUI,
							LIGHT, OverlayTexture.DEFAULT_UV, matrices, VERTICES, receivingWorld, index);
				} finally {
					matrices.pop();
				}
			}
			VERTICES.draw();
		} finally {
			if (hadScissor) RenderSystem.enableScissor(previous[0], previous[1], previous[2], previous[3]);
			else RenderSystem.disableScissor();
		}
	}

	/** The billboard is a world object. Scissoring only clips its moving icons inside that object's frame. */
	private static int[] clippingRectangle(MinecraftClient client, Matrix4f matrix) {
		Matrix4f clipMatrix = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).mul(matrix);
		int width = client.getWindow().getFramebufferWidth();
		int height = client.getWindow().getFramebufferHeight();
		float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
		float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
		for (int corner = 0; corner < 4; corner++) {
			Vector4f projected = new Vector4f((corner & 1) == 0 ? -HALF_WIDTH : HALF_WIDTH,
					(corner & 2) == 0 ? -14 : 14, 12, 1).mul(clipMatrix);
			if (projected.w <= 0.001F) return null;
			float x = (projected.x / projected.w * 0.5F + 0.5F) * width;
			float y = (projected.y / projected.w * 0.5F + 0.5F) * height;
			left = Math.min(left, x); right = Math.max(right, x);
			bottom = Math.min(bottom, y); top = Math.max(top, y);
		}
		int x = MathHelper.clamp((int) Math.floor(left), 0, width);
		int y = MathHelper.clamp((int) Math.floor(bottom), 0, height);
		int rightEdge = MathHelper.clamp((int) Math.ceil(right), 0, width);
		int topEdge = MathHelper.clamp((int) Math.ceil(top), 0, height);
		return rightEdge > x && topEdge > y ? new int[]{x, y, rightEdge - x, topEdge - y} : null;
	}

	private static void border(MatrixStack matrices, float left, float top, float right, float bottom, float z, int color, float thickness) {
		quad(matrices, left, top, right, top + thickness, z, color);
		quad(matrices, left, bottom - thickness, right, bottom, z, color);
		quad(matrices, left, top + thickness, left + thickness, bottom - thickness, z, color);
		quad(matrices, right - thickness, top + thickness, right, bottom - thickness, z, color);
	}

	private static void quad(MatrixStack matrices, float left, float top, float right, float bottom, float z, int color) {
		VertexConsumer vertices = VERTICES.getBuffer(RenderLayer.getDebugQuads());
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		vertices.vertex(matrix, left, top, z).color(color);
		vertices.vertex(matrix, left, bottom, z).color(color);
		vertices.vertex(matrix, right, bottom, z).color(color);
		vertices.vertex(matrix, right, top, z).color(color);
	}

	private static Vec3d anchorPosition(ClientWorld world, UUID uuid, float tickDelta) {
		for (Entity entity : world.getEntities()) {
			if (entity instanceof ViewerParrotEntity bird && bird.getUuid().equals(uuid) && bird.isAlive() && !bird.isRemoved()) {
				return interpolatedPosition(bird, tickDelta).add(0, bird.getHeight() + ABOVE_BIRD, 0);
			}
		}
		for (var player : world.getPlayers()) {
			if (!player.isAlive()) continue;
			boolean left;
			if (matchesShoulder(player.getShoulderEntityLeft(), uuid)) left = true;
			else if (matchesShoulder(player.getShoulderEntityRight(), uuid)) left = false;
			else continue;
			Vec3d interpolation = interpolatedPosition(player, tickDelta).subtract(player.getPos());
			return ViewerShoulderFeature.shoulderPosition(player, left).add(interpolation).add(0, 0.35 + ABOVE_BIRD, 0);
		}
		return null;
	}

	private static Vec3d interpolatedPosition(Entity entity, float tickDelta) {
		return new Vec3d(MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
				MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
				MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()));
	}

	private static boolean matchesShoulder(NbtCompound nbt, UUID uuid) {
		return ViewerShoulderFeature.isViewerParrot(nbt) && nbt.containsUuid("UUID") && nbt.getUuid("UUID").equals(uuid);
	}

	private record Reel(RoulettePayload payload, ItemStack[] icons) { }
}
