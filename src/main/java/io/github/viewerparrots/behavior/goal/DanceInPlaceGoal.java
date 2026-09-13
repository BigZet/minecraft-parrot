package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Танец выполняется на текущем месте: по разделу 6 ТЗ команда {@code dance} не двигает
 * попугая и не выполняет автоматический {@code come}.
 *
 * <p>Goal заявляет контроль над MOVE и JUMP, поэтому {@code GoalSelector} вытесняет
 * ванильные {@code FlyOntoTreeGoal} (приоритет 2) и {@code FollowMobGoal} (приоритет 3),
 * которые иначе уводят попугая в полёт. Взгляд (LOOK) намеренно не захватывается —
 * поворот головы танцу не мешает.
 *
 * <p>Вертикальная скорость не обнуляется: попугай, начавший танец в воздухе, должен
 * опуститься на землю, а не зависнуть.
 */
public class DanceInPlaceGoal extends Goal {
	private final ViewerParrotEntity parrot;

	public DanceInPlaceGoal(ViewerParrotEntity parrot) {
		this.parrot = parrot;
		this.setControls(EnumSet.of(Control.MOVE, Control.JUMP));
	}

	@Override
	public boolean canStart() {
		return this.parrot.isForcedDancing();
	}

	@Override
	public boolean shouldContinue() {
		return this.parrot.isForcedDancing();
	}

	@Override
	public boolean shouldRunEveryTick() {
		return true;
	}

	@Override
	public void start() {
		this.parrot.getNavigation().stop();
	}

	@Override
	public void stop() {
		this.parrot.getNavigation().stop();
	}

	@Override
	public void tick() {
		// Навигация останавливается каждый tick: MoveControl мог сохранить цель,
		// выставленную до начала танца.
		this.parrot.getNavigation().stop();
		this.parrot.setMovementSpeed(0.0f);
		this.parrot.setVelocity(this.parrot.getVelocity().multiply(0.0, 1.0, 0.0));
	}
}
