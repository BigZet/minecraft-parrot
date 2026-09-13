package io.github.viewerparrots.behavior;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.ParrotEntityController;
import io.github.viewerparrots.entity.SafePositionFinder;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Тиковая часть поведения: таймеры танца, дедлайны движения, прибытие, LAZY-отсрочки
 * и перенос к цели (разделы 5.2, 6.1 и 10 ТЗ).
 *
 * <p>Бюджеты соблюдаются буквально: не более {@value #MAX_TELEPORTS_PER_TICK} переносов
 * за тик глобально, чтобы 50 попугаев не телепортировались одновременно (раздел 14).
 *
 * <p>Ошибка поведения одного попугая изолируется: она не должна останавливать остальных.
 * После трёх подряд ошибок автономное поведение этого зрителя отключается.
 */
public final class BehaviorScheduler {
	public static final int MAX_TELEPORTS_PER_TICK = 2;

	private final ServerContext context;

	public BehaviorScheduler(ServerContext context) {
		this.context = Objects.requireNonNull(context, "context");
	}

	public void tick() {
		if (!this.context.allowsMutations()) {
			// В degraded автономное поведение остановлено (раздел 8.3 ТЗ).
			return;
		}

		long now = this.context.clock().currentTick();
		int teleportsUsed = 0;

		// Копия: обработчик может изменить состав runtime-карты.
		List<ViewerId> viewerIds = new ArrayList<>(this.context.runtimes().keySet());
		for (ViewerId viewerId : viewerIds) {
			ParrotRuntime runtime = this.context.runtimes().get(viewerId);
			if (runtime == null || runtime.isBehaviorDisabledByErrors()) {
				continue;
			}

			try {
				teleportsUsed += tickOne(viewerId, runtime, now, teleportsUsed);
				runtime.recordBehaviorSuccess();
			} catch (RuntimeException e) {
				runtime.recordBehaviorError();
				ViewerParrotsMod.LOGGER.warn("Ошибка поведения viewerId={} (подряд {}): {}",
						viewerId, runtime.consecutiveBehaviorErrors(), e.toString());
				if (runtime.isBehaviorDisabledByErrors()) {
					ViewerParrotsMod.LOGGER.error(
							"Автономное поведение viewerId={} отключено до reload или рестарта", viewerId);
				}
			}
		}
	}

	/** @return сколько переносов израсходовано этим попугаем */
	private int tickOne(ViewerId viewerId, ParrotRuntime runtime, long now, int teleportsUsed) {
		Optional<ViewerParrotEntity> maybeParrot = this.context.loadedIndex().find(viewerId);
		if (maybeParrot.isEmpty()) {
			// На выгруженном попугае сопровождение приостановлено до загрузки.
			return 0;
		}
		ViewerParrotEntity parrot = maybeParrot.get();

		// Проверяется раньше всего: падающий из мира попугай не доживёт до остальных шагов.
		if (rescueFromVoid(viewerId, parrot)) {
			return 1;
		}

		if (runPendingLazyAction(viewerId, runtime, parrot, now)) {
			return 0;
		}

		if (runtime.isDanceExpired(now)) {
			finishDance(viewerId, runtime, parrot);
			return 0;
		}

		if (runtime.behavior() == BehaviorState.RETURNING) {
			return tickReturning(viewerId, runtime, parrot, now, teleportsUsed);
		}

		if (runtime.behavior() == BehaviorState.FOLLOWING) {
			return tickFollowing(runtime, parrot, now, teleportsUsed);
		}
		return 0;
	}

	/**
	 * Спасение из пустоты.
	 *
	 * <p>Раздел 9.6 разрешает не защищать от void, но попугай, следующий за стримером мимо
	 * обрыва, гиб в проверках раз за разом — а зритель должен быть постоянным. Поэтому
	 * упавший ниже дна мира переносится к стримеру, пока режим смерти блокирует обычный урон.
	 *
	 * @return {@code true} если попугай был спасён и его тик на этом закончен
	 */
	private boolean rescueFromVoid(ViewerId viewerId, ViewerParrotEntity parrot) {
		if (!this.context.config().deathMode().blocksOrdinaryDamage()) {
			return false;
		}
		if (parrot.getY() >= parrot.getWorld().getBottomY()) {
			return false;
		}

		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty() || !teleportNear(parrot, target.get())) {
			return false;
		}

		ViewerParrotsMod.LOGGER.warn("viewerId={} выпал из мира и перенесён к стримеру", viewerId);
		return true;
	}

	private boolean runPendingLazyAction(ViewerId viewerId, ParrotRuntime runtime,
			ViewerParrotEntity parrot, long now) {
		Optional<ParrotRuntime.PendingAction> maybePending = runtime.pendingAction();
		if (maybePending.isEmpty() || now < maybePending.get().executeAtTick()) {
			return false;
		}

		ParrotRuntime.PendingAction pending = maybePending.get();
		runtime.clearPendingAction();

		// Повторная валидация после задержки: за это время попугая могли заменить.
		Optional<Long> generation = this.context.registry().find(viewerId)
				.map(record -> record.generation());
		if (generation.isEmpty() || generation.get() != pending.generationAtSchedule()) {
			ViewerParrotsMod.LOGGER.debug("LAZY-задание viewerId={} отменено: сменилась generation", viewerId);
			return true;
		}

		BehaviorStateMachine.Result result = BehaviorStateMachine.apply(
				runtime.behavior(), runtime.desiredBehavior(), pending.event());
		if (!(result instanceof BehaviorStateMachine.Accepted accepted)) {
			return true;
		}

		// Cooldown второй раз не списывается: он был израсходован при приёме команды.
		ParrotAction action = pending.event() == BehaviorStateMachine.Event.COMMAND_COME
				? ParrotAction.COME
				: ParrotAction.FOLLOW;
		this.context.commandService().execute(pending.commandId(), viewerId, runtime, parrot, accepted, action);
		return true;
	}

	private void finishDance(ViewerId viewerId, ParrotRuntime runtime, ViewerParrotEntity parrot) {
		BehaviorStateMachine.Result result = BehaviorStateMachine.apply(
				runtime.behavior(), runtime.desiredBehavior(), BehaviorStateMachine.Event.DANCE_EXPIRED);
		if (result instanceof BehaviorStateMachine.Accepted accepted) {
			runtime.apply(accepted, this.context.clock().currentTick());
		}
		parrot.setForcedDancing(false);
		ViewerParrotsMod.LOGGER.debug("dance finished viewerId={} restored={}", viewerId, runtime.behavior());
	}

	private int tickReturning(ViewerId viewerId, ParrotRuntime runtime, ViewerParrotEntity parrot,
			long now, int teleportsUsed) {
		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			// Незавершённый come отменяется: цели больше нет (раздел 5.3 ТЗ).
			applyEvent(runtime, BehaviorStateMachine.Event.TARGET_LOST);
			parrot.getNavigation().stop();
			return 0;
		}

		double arrivalRadius = this.context.config().come().arrivalRadius();
		if (parrot.squaredDistanceTo(target.get()) <= arrivalRadius * arrivalRadius) {
			applyEvent(runtime, BehaviorStateMachine.Event.ARRIVAL_REACHED);
			parrot.getNavigation().stop();
			ViewerParrotsMod.LOGGER.debug("come arrived viewerId={}", viewerId);
			return 0;
		}

		long deadline = runtime.movementDeadlineTick();
		if (deadline > 0 && now >= deadline) {
			return finishMovementByTimeout(viewerId, runtime, parrot, target.get(), teleportsUsed);
		}

		moveToward(runtime, parrot, target.get(), now);
		return 0;
	}

	private int finishMovementByTimeout(ViewerId viewerId, ParrotRuntime runtime,
			ViewerParrotEntity parrot, ServerPlayerEntity target, int teleportsUsed) {
		boolean mayTeleport = this.context.config().come().teleportOnPathFailure()
				&& teleportsUsed < MAX_TELEPORTS_PER_TICK;

		if (mayTeleport && teleportNear(parrot, target)) {
			applyEvent(runtime, BehaviorStateMachine.Event.ARRIVAL_REACHED);
			ViewerParrotsMod.LOGGER.debug("come teleported viewerId={}", viewerId);
			return 1;
		}

		applyEvent(runtime, BehaviorStateMachine.Event.MOVEMENT_TIMEOUT);
		parrot.getNavigation().stop();
		ViewerParrotsMod.LOGGER.debug("come timeout viewerId={} reason=PATH_NOT_FOUND", viewerId);
		return 0;
	}

	private int tickFollowing(ParrotRuntime runtime, ViewerParrotEntity parrot, long now,
			int teleportsUsed) {
		Optional<ServerPlayerEntity> target = this.context.streamerService().onlineTarget();
		if (target.isEmpty()) {
			// Следование прекращается, но намерение FOLLOWING сохраняется.
			parrot.getNavigation().stop();
			return 0;
		}

		double distanceSquared = parrot.squaredDistanceTo(target.get());
		double start = this.context.config().follow().startDistance();
		double stop = this.context.config().follow().stopDistance();

		if (distanceSquared <= stop * stop) {
			parrot.getNavigation().stop();
			return 0;
		}

		// Раздел 6.1 требует для follow ту же teleport policy, что и для come: дальше
		// maxPathfindDistance навигация бессмысленна, попугай просто отстанет навсегда.
		double maxPathfind = this.context.config().come().maxPathfindDistance();
		if (distanceSquared > maxPathfind * maxPathfind) {
			boolean mayTeleport = this.context.config().come().teleportIfFar()
					&& teleportsUsed < MAX_TELEPORTS_PER_TICK
					&& runtime.canRepath(now);
			if (mayTeleport) {
				runtime.markRepathed(now, this.context.config().follow().repathTicks());
				if (teleportNear(parrot, target.get())) {
					return 1;
				}
			}
			return 0;
		}

		if (distanceSquared < start * start) {
			return 0;
		}
		moveToward(runtime, parrot, target.get(), now);
		return 0;
	}

	/** Пересчёт маршрута не чаще, чем раз в {@code repathTicks} на попугая (раздел 14 ТЗ). */
	private void moveToward(ParrotRuntime runtime, ViewerParrotEntity parrot,
			ServerPlayerEntity target, long now) {
		if (!runtime.canRepath(now)) {
			return;
		}
		runtime.markRepathed(now, this.context.config().follow().repathTicks());
		parrot.getNavigation().startMovingTo(target, 1.0);
	}

	private boolean teleportNear(ViewerParrotEntity parrot, ServerPlayerEntity target) {
		ServerWorld targetWorld = target.getServerWorld();
		if (!parrot.getWorld().getRegistryKey().equals(targetWorld.getRegistryKey())
				&& !this.context.config().come().crossDimensionTeleport()) {
			return false;
		}

		Optional<Vec3d> safe = this.context.safePositionFinder().findNear(targetWorld, target.getPos());
		if (safe.isEmpty()) {
			// Запасной вариант — позиция самого стримера: он на чём-то стоит, а поиск
			// вокруг мог ничего не найти именно у обрыва, где перенос нужнее всего.
			Vec3d fallback = target.getPos();
			if (!this.context.safePositionFinder().isSafe(targetWorld, fallback)) {
				ViewerParrotsMod.LOGGER.debug("Перенос отменён: нет безопасной позиции у стримера");
				return false;
			}
			safe = Optional.of(fallback);
		}

		Vec3d position = safe.get();
		parrot.getNavigation().stop();
		return parrot.teleport(targetWorld, position.x, position.y, position.z,
				Set.<PositionFlag>of(), parrot.getYaw(), parrot.getPitch());
	}

	private void applyEvent(ParrotRuntime runtime, BehaviorStateMachine.Event event) {
		BehaviorStateMachine.Result result =
				BehaviorStateMachine.apply(runtime.behavior(), runtime.desiredBehavior(), event);
		if (result instanceof BehaviorStateMachine.Accepted accepted) {
			runtime.apply(accepted, this.context.clock().currentTick());
		}
	}

	/** Для диагностики: позиция попугая как подсказка реестру. */
	public static void refreshLocationHint(ServerContext context, ViewerId viewerId,
			ViewerParrotEntity parrot) {
		context.registry().find(viewerId).ifPresent(record ->
				context.registry().replace(record.withLocation(
						ParrotEntityController.locationOf(parrot), context.clock().now())));
	}

	/** Общая проверка опоры — та же, что при спавне. */
	public static boolean hasSupport(ViewerParrotEntity parrot) {
		return SafePositionFinder.hasSupportBelow(parrot.getWorld(), parrot.getBlockPos(),
				SafePositionFinder.SUPPORT_SCAN_DEPTH);
	}
}
