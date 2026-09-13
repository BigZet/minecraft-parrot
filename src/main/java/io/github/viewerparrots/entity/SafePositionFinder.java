package io.github.viewerparrots.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.Random;

/**
 * Поиск безопасной позиции для spawn и teleport (раздел 6.1 ТЗ).
 *
 * <p>Ограничения намеренно жёсткие: не более {@value #MAX_CANDIDATES} кандидатов в радиусе
 * {@value #MIN_RADIUS}–{@value #MAX_RADIUS} блоков от цели, только уже загруженные чанки,
 * без генерации территории. Поиск не должен превращаться в обход мира — раздел 14 отводит
 * ему долю тика.
 *
 * <p>Попугая нельзя поместить внутрь блока: при отсутствии места возвращается пусто,
 * и вызывающий отвечает {@code NO_SAFE_POSITION}.
 */
public final class SafePositionFinder {
	public static final int MAX_CANDIDATES = 32;
	public static final int MIN_RADIUS = 2;
	public static final int MAX_RADIUS = 6;

	/** Габариты попугая; совпадают с зарегистрированными в {@link ModEntities}. */
	private static final double WIDTH = 0.5;
	private static final double HEIGHT = 0.9;

	private final Random random;

	public SafePositionFinder(Random random) {
		this.random = random;
	}

	public Optional<Vec3d> findNear(ServerWorld world, Vec3d target) {
		for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
			Vec3d candidate = randomCandidate(target);
			if (isSafe(world, candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private Vec3d randomCandidate(Vec3d target) {
		double angle = this.random.nextDouble() * Math.PI * 2;
		double distance = MIN_RADIUS + this.random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
		double verticalOffset = this.random.nextInt(MAX_RADIUS + 1) - 2;

		return new Vec3d(
				target.x + Math.cos(angle) * distance,
				target.y + verticalOffset,
				target.z + Math.sin(angle) * distance);
	}

	public boolean isSafe(ServerWorld world, Vec3d position) {
		BlockPos pos = BlockPos.ofFloored(position);

		if (position.y < world.getBottomY() || position.y >= world.getTopY()) {
			return false;
		}
		if (!world.getWorldBorder().contains(pos)) {
			return false;
		}
		// Только уже загруженные чанки: генерировать территорию ради спавна нельзя.
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return false;
		}

		Box boundingBox = new Box(
				position.x - WIDTH / 2, position.y, position.z - WIDTH / 2,
				position.x + WIDTH / 2, position.y + HEIGHT, position.z + WIDTH / 2);
		if (!world.isSpaceEmpty(boundingBox)) {
			return false;
		}

		if (isHazardous(world.getBlockState(pos))) {
			return false;
		}
		// Опора под ногами тоже не должна быть опасной: попугай на неё сядет.
		return !isHazardous(world.getBlockState(pos.down()));
	}

	private static boolean isHazardous(BlockState state) {
		if (!state.getFluidState().isEmpty()) {
			return true;
		}
		return state.isOf(Blocks.LAVA)
				|| state.isOf(Blocks.FIRE)
				|| state.isOf(Blocks.SOUL_FIRE)
				|| state.isOf(Blocks.CAMPFIRE)
				|| state.isOf(Blocks.SOUL_CAMPFIRE)
				|| state.isOf(Blocks.MAGMA_BLOCK)
				|| state.isOf(Blocks.CACTUS)
				|| state.isOf(Blocks.SWEET_BERRY_BUSH)
				|| state.isOf(Blocks.WITHER_ROSE)
				|| state.isOf(Blocks.POWDER_SNOW);
	}
}
