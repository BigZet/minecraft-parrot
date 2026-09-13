package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

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
 * <p>Снижение разрешено только когда под попугаем есть опора в пределах
 * {@value #SUPPORT_SCAN_DEPTH} блоков: начавший танец в воздухе должен приземлиться.
 * Над пустотой снижение запрещено — иначе танец над пропастью или в void-дыре убивает
 * попугая, что и произошло при первой проверке в игре.
 */
public class DanceInPlaceGoal extends Goal {
	/** Насколько глубоко искать опору под попугаем. */
	public static final int SUPPORT_SCAN_DEPTH = 8;

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

		// Горизонтальное смещение запрещено всегда; вертикальное — только пока под
		// попугаем есть на что приземлиться.
		double verticalFactor = hasSupportBelow() ? 1.0 : 0.0;
		this.parrot.setVelocity(this.parrot.getVelocity().multiply(0.0, verticalFactor, 0.0));
		if (verticalFactor == 0.0) {
			// Накопленное падение сбрасывается: иначе после окончания танца попугай
			// получил бы урон за высоту, которую фактически не пролетел.
			this.parrot.fallDistance = 0.0f;
		}
	}

	private boolean hasSupportBelow() {
		BlockPos from = this.parrot.getBlockPos();
		int bottom = this.parrot.getWorld().getBottomY();

		for (int offset = 1; offset <= SUPPORT_SCAN_DEPTH; offset++) {
			BlockPos pos = from.down(offset);
			if (pos.getY() < bottom) {
				return false;
			}
			BlockState state = this.parrot.getWorld().getBlockState(pos);
			if (state.isSolidBlock(this.parrot.getWorld(), pos)) {
				return true;
			}
		}
		return false;
	}
}
