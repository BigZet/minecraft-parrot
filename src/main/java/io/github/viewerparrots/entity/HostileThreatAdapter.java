package io.github.viewerparrots.entity;

import io.github.viewerparrots.behavior.BehaviorPolicy;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 1.21.1 Monster marker includes hostile, slime, ghast, phantom and dragon classes, never players. */
public final class HostileThreatAdapter {
	public Optional<MobEntity> nearest(ViewerParrotEntity parrot, int radius) {
		if (!(parrot.getWorld() instanceof ServerWorld world)) return Optional.empty();
		List<MobEntity> candidates = new ArrayList<>(BehaviorPolicy.MAX_HOSTILE_CANDIDATES);
		world.collectEntitiesByType(TypeFilter.instanceOf(MobEntity.class),
				parrot.getBoundingBox().expand(radius),
				mob -> mob instanceof Monster && mob.isAlive() && !mob.isSpectator()
						&& parrot.squaredDistanceTo(mob) <= (double) radius * radius,
				candidates, BehaviorPolicy.MAX_HOSTILE_CANDIDATES);
		return candidates.stream().min(Comparator.comparingDouble(parrot::squaredDistanceTo));
	}
}
