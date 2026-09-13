package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.behavior.ParrotRuntime;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.SafePositionFinder;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Удержание попугая на месте в состоянии {@code SITTING} (раздел 5.2 ТЗ).
 *
 * <p>Без этого goal команда {@code sit} не работала: остановки навигации на один тик
 * ванильному AI недостаточно, {@code FlyOntoTreeGoal} тут же уводил попугая обратно
 * в полёт. Это выяснилось при проверке в игре.
 *
 * <p>Ванильная поза сидения выставляется здесь же: {@code ParrotEntityModel.getPose()}
 * читает {@code isInSittingPose()}, поэтому иначе попугай сидел бы «стоя».
 *
 * <p>Как и в танце, снижение разрешено только при наличии опоры под попугаем —
 * сидеть над пустотой значит упасть из мира.
 */
public class SitStillGoal extends Goal {
	private final ViewerParrotEntity parrot;

	public SitStillGoal(ViewerParrotEntity parrot) {
		this.parrot = parrot;
		this.setControls(EnumSet.of(Control.MOVE, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		return isSitting();
	}

	@Override
	public boolean shouldContinue() {
		return isSitting();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.parrot.getNavigation().stop();
		this.parrot.setInSittingPose(true);
	}

	@Override
	public void stop() {
		this.parrot.setInSittingPose(false);
		this.parrot.getNavigation().stop();
	}

	@Override
	public void tick() {
		this.parrot.getNavigation().stop();
		this.parrot.setMovementSpeed(0.0f);

		double verticalFactor = SafePositionFinder.hasSupportBelow(this.parrot.getWorld(),
				this.parrot.getBlockPos(), SafePositionFinder.SUPPORT_SCAN_DEPTH) ? 1.0 : 0.0;
		this.parrot.setVelocity(this.parrot.getVelocity().multiply(0.0, verticalFactor, 0.0));
		if (verticalFactor == 0.0) {
			this.parrot.fallDistance = 0.0f;
		}
	}

	/**
	 * Источник истины — серверное runtime-состояние, а не ванильный флаг: команду
	 * отдаёт сервер, и ванильные пути включения сидения для попугая зрителя отключены.
	 */
	private boolean isSitting() {
		Optional<ServerContext> context = ServerContext.current();
		if (context.isEmpty()) {
			return false;
		}
		Optional<ViewerId> viewerId = context.get().loadedIndex().findViewerId(this.parrot.getUuid());
		if (viewerId.isEmpty()) {
			return false;
		}
		ParrotRuntime runtime = context.get().runtimes().get(viewerId.get());
		return runtime != null && runtime.behavior() == BehaviorState.SITTING;
	}
}
