package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.ViewerId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Clock-keyed global work limits, shared by scheduler teleports and durable spawns. */
public final class BehaviorWorkBudget {
	public static final int MAX_PATHS = 2;
	public static final int MAX_DANGER_CHECKS = 5;
	public static final int MAX_TELEPORTS = 2;
	private static final int PATH_COOLDOWN_TICKS = 20;
	// Identity keys only: deadlines survive runtime resets without retaining entities.
	private final Map<ViewerId, Long> nextPathTicks = new HashMap<>();
	private long tick = Long.MIN_VALUE;
	private int paths, dangers, teleports, cursor;

	/** Viewer admission never spends global capacity while its 20-tick deadline is pending. */
	public boolean tryPath(ViewerId viewerId, long now) {
		Objects.requireNonNull(viewerId, "viewerId");
		begin(now);
		Long nextAllowedTick = nextPathTicks.get(viewerId);
		if (nextAllowedTick != null && now < nextAllowedTick) return false;
		if (!tryPath(now)) return false;
		nextPathTicks.put(viewerId, now + PATH_COOLDOWN_TICKS);
		return true;
	}

	/** Compatibility for callers that have no viewer identity; shares the global path limit. */
	public boolean tryPath(long now) { begin(now); if (paths >= MAX_PATHS) return false; paths++; return true; }
	public boolean tryDanger(long now) { begin(now); if (dangers >= MAX_DANGER_CHECKS) return false; dangers++; return true; }
	public boolean tryTeleport(long now) { begin(now); if (teleports >= MAX_TELEPORTS) return false; teleports++; return true; }

	private void begin(long now) {
		if (tick != now) {
			tick = now;
			paths = 0; dangers = 0; teleports = 0;
			// At most two admissions per tick over twenty ticks: at most forty live keys.
			nextPathTicks.values().removeIf(deadline -> deadline <= now);
		}
	}

	/** Only loaded runtimes are supplied. No dormant records or retained entity references. */
	public <T> List<T> roundRobin(Collection<T> viewers) {
		List<T> ids = new ArrayList<>(viewers);
		if (ids.isEmpty()) { cursor = 0; return ids; }
		int start = Math.floorMod(cursor, ids.size());
		List<T> order = new ArrayList<>(ids.size());
		for (int i = 0; i < ids.size(); i++) order.add(ids.get((start + i) % ids.size()));
		cursor = (start + MAX_PATHS) % ids.size();
		return order;
	}

	/** Server shutdown discards all budgets; runtime/admin resets do not call this method. */
	public void clear() { tick = Long.MIN_VALUE; paths = dangers = teleports = cursor = 0; nextPathTicks.clear(); }
	public int pathsUsed() { return paths; }
	public int dangersUsed() { return dangers; }
	public int teleportsUsed() { return teleports; }
	public int trackedPathViewers() { return nextPathTicks.size(); }
}
