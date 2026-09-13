package io.github.viewerparrots.registry;

import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Реестр регистраций в памяти. Владеет записями, надгробиями и индексами; о диске
 * ничего не знает — сохранение выполняет storage-слой.
 *
 * <p>Обеспечивает инварианты раздела 4.4: не более одной живой регистрации на viewerId
 * и не более одного ожидаемого entityUuid на регистрацию. Индексы viewerId → запись и
 * entityUuid → viewerId поддерживаются вместе, чтобы обычная команда обходилась
 * O(1) поиском (раздел 14).
 *
 * <p>Не потокобезопасен: по разделу 3.1 реестр изменяется только на server thread.
 */
public final class ViewerParrotRegistry {
	private final Map<ViewerId, ViewerParrotRecord> records = new TreeMap<>();
	private final Map<UUID, ViewerId> byEntityUuid = new HashMap<>();
	private final Map<UUID, Tombstone> tombstonesByIncarnation = new LinkedHashMap<>();

	private UUID streamerPlayerUuid;

	/** Слоты, зарезервированные под ещё не завершённый spawn (раздел 9.5 ТЗ). */
	private int pendingReservations;

	/**
	 * Есть ли изменения, не попавшие в снимок. Раздел 8.2 требует писать checkpoint
	 * каждые 30 секунд именно при dirty, а не безусловно.
	 */
	private boolean dirty;

	public Optional<ViewerParrotRecord> find(ViewerId viewerId) {
		return Optional.ofNullable(this.records.get(viewerId));
	}

	public Optional<ViewerParrotRecord> findByEntityUuid(UUID entityUuid) {
		ViewerId viewerId = this.byEntityUuid.get(entityUuid);
		return viewerId == null ? Optional.empty() : find(viewerId);
	}

	public boolean contains(ViewerId viewerId) {
		return this.records.containsKey(viewerId);
	}

	/** Записи в порядке viewerId — раздел 6 требует такой сортировки для {@code list}. */
	public List<ViewerParrotRecord> allSorted() {
		return List.copyOf(this.records.values());
	}

	public int size() {
		return this.records.size();
	}

	/**
	 * Добавляет новую регистрацию.
	 *
	 * @throws IllegalStateException если viewerId уже зарегистрирован или entityUuid занят
	 */
	public void put(ViewerParrotRecord record) {
		Objects.requireNonNull(record, "record");
		ViewerId viewerId = record.viewerId();
		if (this.records.containsKey(viewerId)) {
			throw new IllegalStateException("viewerId уже зарегистрирован: " + viewerId);
		}
		checkEntityUuidFree(record, viewerId);
		this.records.put(viewerId, record);
		record.expectedEntityUuid().ifPresent(uuid -> this.byEntityUuid.put(uuid, viewerId));
		this.dirty = true;
	}

	/**
	 * Заменяет существующую запись, поддерживая индекс UUID.
	 *
	 * @throws IllegalStateException если записи нет, сменилась incarnation или revision не вырос
	 */
	public void replace(ViewerParrotRecord updated) {
		Objects.requireNonNull(updated, "updated");
		ViewerId viewerId = updated.viewerId();
		ViewerParrotRecord existing = this.records.get(viewerId);
		if (existing == null) {
			throw new IllegalStateException("нечего заменять, viewerId не зарегистрирован: " + viewerId);
		}
		if (!existing.incarnationId().equals(updated.incarnationId())) {
			throw new IllegalStateException("замена не может сменить incarnation для " + viewerId);
		}
		if (updated.revision() <= existing.revision()) {
			// Защита от применения устаревшего результата I/O (раздел 3.1 ТЗ).
			throw new IllegalStateException("revision не вырос для " + viewerId
					+ ": было " + existing.revision() + ", получено " + updated.revision());
		}
		checkEntityUuidFree(updated, viewerId);

		existing.expectedEntityUuid().ifPresent(this.byEntityUuid::remove);
		this.records.put(viewerId, updated);
		updated.expectedEntityUuid().ifPresent(uuid -> this.byEntityUuid.put(uuid, viewerId));
		this.dirty = true;
	}

	/**
	 * Удаляет регистрацию и оставляет надгробие.
	 *
	 * @return надгробие, либо пусто если регистрации не было
	 */
	public Optional<Tombstone> remove(ViewerId viewerId, java.time.Instant removedAt) {
		ViewerParrotRecord existing = this.records.remove(viewerId);
		if (existing == null) {
			return Optional.empty();
		}
		existing.expectedEntityUuid().ifPresent(this.byEntityUuid::remove);
		Tombstone tombstone = Tombstone.of(existing, removedAt);
		this.dirty = true;
		this.tombstonesByIncarnation.put(tombstone.incarnationId(), tombstone);
		return Optional.of(tombstone);
	}

	public void addTombstone(Tombstone tombstone) {
		Objects.requireNonNull(tombstone, "tombstone");
		this.tombstonesByIncarnation.put(tombstone.incarnationId(), tombstone);
		this.dirty = true;
	}

	/**
	 * Проверка при загрузке сущности: не принадлежит ли её маркер удалённой incarnation.
	 * Надгробия не очищаются автоматически именно ради этой проверки (раздел 8.3 ТЗ).
	 */
	public boolean isTombstoned(UUID incarnationId) {
		return this.tombstonesByIncarnation.containsKey(incarnationId);
	}

	public List<Tombstone> tombstones() {
		return List.copyOf(this.tombstonesByIncarnation.values());
	}

	public List<Tombstone> tombstonesFor(ViewerId viewerId) {
		List<Tombstone> result = new ArrayList<>();
		for (Tombstone tombstone : this.tombstonesByIncarnation.values()) {
			if (tombstone.viewerId().equals(viewerId)) {
				result.add(tombstone);
			}
		}
		return Collections.unmodifiableList(result);
	}

	// --- Ёмкость (раздел 9.5 ТЗ) --------------------------------------------

	/** Committed-записи, занимающие слот, плюс незавершённые резервы. */
	public int reservedSlots() {
		int committed = 0;
		for (ViewerParrotRecord record : this.records.values()) {
			if (record.occupiesCapacitySlot()) {
				committed++;
			}
		}
		return committed + this.pendingReservations;
	}

	public boolean hasFreeSlot(int maxActiveParrots) {
		return reservedSlots() < maxActiveParrots;
	}

	/**
	 * Резервирует слот под создание или замену.
	 *
	 * @return {@code false} если свободных слотов нет — вызывающий возвращает
	 *         {@code MAX_ACTIVE_PARROTS_REACHED} и ничего не записывает
	 */
	public boolean reserveSlot(int maxActiveParrots) {
		if (!hasFreeSlot(maxActiveParrots)) {
			return false;
		}
		this.pendingReservations++;
		return true;
	}

	/** Освобождает резерв при ABORT или после того, как запись стала committed. */
	public void releaseReservation() {
		if (this.pendingReservations == 0) {
			throw new IllegalStateException("освобождается несуществующий резерв");
		}
		this.pendingReservations--;
	}

	public int pendingReservations() {
		return this.pendingReservations;
	}

	// --- Отслеживание изменений ---------------------------------------------

	public boolean isDirty() {
		return this.dirty;
	}

	/** Вызывается после успешного checkpoint: снимок теперь отражает реестр. */
	public void markClean() {
		this.dirty = false;
	}

	// --- Целевой стример ----------------------------------------------------

	public Optional<UUID> streamerPlayerUuid() {
		return Optional.ofNullable(this.streamerPlayerUuid);
	}

	public void setStreamerPlayerUuid(UUID playerUuid) {
		this.streamerPlayerUuid = playerUuid;
		this.dirty = true;
	}

	/** Полная замена содержимого после загрузки снимка и replay журнала. */
	public void replaceAll(List<ViewerParrotRecord> loadedRecords, List<Tombstone> loadedTombstones,
			UUID streamerUuid) {
		this.records.clear();
		this.byEntityUuid.clear();
		this.tombstonesByIncarnation.clear();
		this.pendingReservations = 0;
		this.streamerPlayerUuid = streamerUuid;

		for (ViewerParrotRecord record : loadedRecords) {
			put(record);
		}
		for (Tombstone tombstone : loadedTombstones) {
			addTombstone(tombstone);
		}
		// Только что загруженный реестр совпадает со снимком.
		this.dirty = false;
	}

	private void checkEntityUuidFree(ViewerParrotRecord record, ViewerId viewerId) {
		Optional<UUID> uuid = record.expectedEntityUuid();
		if (uuid.isEmpty()) {
			return;
		}
		ViewerId owner = this.byEntityUuid.get(uuid.get());
		if (owner != null && !owner.equals(viewerId)) {
			throw new IllegalStateException("entityUuid " + uuid.get()
					+ " уже принадлежит регистрации " + owner);
		}
	}
}
