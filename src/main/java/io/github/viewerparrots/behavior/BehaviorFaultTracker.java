package io.github.viewerparrots.behavior;

import io.github.viewerparrots.domain.ViewerId;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/** Identity-only autonomous fault latches; no world, entity or runtime references. */
public final class BehaviorFaultTracker {
	private final Map<ViewerId, UUID> disabled = new HashMap<>();
	public void disable(ViewerId id, UUID incarnation) { disabled.put(id, incarnation); }
	public boolean isDisabled(ViewerId id, UUID incarnation) {
		UUID recorded = disabled.get(id);
		if (recorded == null) return false;
		if (recorded.equals(incarnation)) return true;
		disabled.remove(id);
		return false;
	}
	public void forget(ViewerId id) { disabled.remove(id); }
	public void clear() { disabled.clear(); }
}
