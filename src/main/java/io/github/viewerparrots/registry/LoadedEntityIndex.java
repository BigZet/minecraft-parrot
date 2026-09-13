package io.github.viewerparrots.registry;

import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.ViewerParrotEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Индекс загруженных сущностей: viewerId → сущность и UUID → viewerId (раздел 9.2 ТЗ).
 *
 * <p>Ссылки на {@code Entity} держатся только пока сущность загружена и удаляются при
 * unload: раздел 14 требует, чтобы после 100 циклов load/unload число ссылок вернулось
 * к исходному. Долгоживущие структуры хранят UUID, а не объекты.
 *
 * <p>Не потокобезопасен: изменяется только на server thread.
 */
public final class LoadedEntityIndex {
	private final Map<ViewerId, ViewerParrotEntity> byViewerId = new HashMap<>();
	private final Map<UUID, ViewerId> viewerIdByEntityUuid = new HashMap<>();

	/**
	 * Привязывает загруженную сущность.
	 *
	 * @throws IllegalStateException если для этого viewerId уже привязана другая сущность:
	 *         раздел 4.4 допускает не более одной авторизованной загруженной сущности
	 */
	public void bind(ViewerId viewerId, ViewerParrotEntity entity) {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(entity, "entity");

		ViewerParrotEntity existing = this.byViewerId.get(viewerId);
		if (existing != null && !existing.getUuid().equals(entity.getUuid())) {
			throw new IllegalStateException("для " + viewerId + " уже привязана сущность "
					+ existing.getUuid() + ", попытка привязать " + entity.getUuid());
		}

		this.byViewerId.put(viewerId, entity);
		this.viewerIdByEntityUuid.put(entity.getUuid(), viewerId);
	}

	public void unbind(ViewerId viewerId) {
		ViewerParrotEntity removed = this.byViewerId.remove(viewerId);
		if (removed != null) {
			this.viewerIdByEntityUuid.remove(removed.getUuid());
		}
	}

	public void unbindEntity(UUID entityUuid) {
		ViewerId viewerId = this.viewerIdByEntityUuid.remove(entityUuid);
		if (viewerId != null) {
			this.byViewerId.remove(viewerId);
		}
	}

	public Optional<ViewerParrotEntity> find(ViewerId viewerId) {
		return Optional.ofNullable(this.byViewerId.get(viewerId));
	}

	public Optional<ViewerId> findViewerId(UUID entityUuid) {
		return Optional.ofNullable(this.viewerIdByEntityUuid.get(entityUuid));
	}

	public boolean isLoaded(ViewerId viewerId) {
		return this.byViewerId.containsKey(viewerId);
	}

	public int size() {
		return this.byViewerId.size();
	}

	public Set<ViewerId> loadedViewerIds() {
		return Set.copyOf(this.byViewerId.keySet());
	}

	/**
	 * Перенос между измерениями создаёт НОВЫЙ Java-объект при том же UUID — это
	 * подтверждено проверкой M0. Поэтому индекс обязан уметь заменить ссылку.
	 */
	public void rebind(ViewerId viewerId, ViewerParrotEntity replacement) {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(replacement, "replacement");
		unbind(viewerId);
		this.byViewerId.put(viewerId, replacement);
		this.viewerIdByEntityUuid.put(replacement.getUuid(), viewerId);
	}

	public void clear() {
		this.byViewerId.clear();
		this.viewerIdByEntityUuid.clear();
	}
}
