package io.github.viewerparrots.entity;

import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;

/** The scheduler calculates route replacements within its global budget, including after block changes. */
public final class ScheduledBirdNavigation extends BirdNavigation {
	public ScheduledBirdNavigation(MobEntity entity, World world) {
		super(entity, world);
		setCanPathThroughDoors(false);
		setCanSwim(true);
		setCanEnterOpenDoors(true);
	}
	@Override public void recalculatePath() { stop(); }
}
