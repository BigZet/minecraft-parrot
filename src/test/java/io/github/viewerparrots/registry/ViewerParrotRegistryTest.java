package io.github.viewerparrots.registry;

import io.github.viewerparrots.domain.DesiredBehavior;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.Tombstone;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздел 15.1 ТЗ: ёмкость учитывает UNLOADED и MISSING и освобождается только по правилам;
 * конкурирующие create не превышают лимит. Плюс инварианты индексов из раздела 4.4.
 */
class ViewerParrotRegistryTest {

	private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
	private static final int MAX_ACTIVE = 50;

	private ViewerParrotRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new ViewerParrotRegistry();
	}

	private static ViewerParrotRecord record(String id) {
		return record(id, UUID.randomUUID());
	}

	private static ViewerParrotRecord record(String id, UUID entityUuid) {
		Viewer viewer = new Viewer(ViewerId.of(id), DisplayName.of("Имя " + id), NOW);
		return ViewerParrotRecord.created(viewer, UUID.randomUUID(), entityUuid,
				ParrotVariant.BLUE, ParrotTrait.NORMAL, null, NOW);
	}

	@Test
	void indexesByViewerIdAndEntityUuid() {
		UUID entityUuid = UUID.randomUUID();
		ViewerParrotRecord record = record("viewer1", entityUuid);
		this.registry.put(record);

		assertAll(
				() -> assertEquals(Optional.of(record), this.registry.find(ViewerId.of("viewer1"))),
				() -> assertEquals(Optional.of(record), this.registry.findByEntityUuid(entityUuid)),
				() -> assertTrue(this.registry.findByEntityUuid(UUID.randomUUID()).isEmpty()));
	}

	@Test
	void rejectsSecondRegistrationForSameViewerId() {
		this.registry.put(record("viewer1"));
		assertThrows(IllegalStateException.class, () -> this.registry.put(record("viewer1")));
	}

	@Test
	void rejectsEntityUuidOwnedByAnotherRegistration() {
		UUID shared = UUID.randomUUID();
		this.registry.put(record("viewer1", shared));
		assertThrows(IllegalStateException.class, () -> this.registry.put(record("viewer2", shared)));
	}

	@Test
	void replaceKeepsUuidIndexConsistent() {
		UUID oldUuid = UUID.randomUUID();
		ViewerParrotRecord original = record("viewer1", oldUuid);
		this.registry.put(original);

		UUID newUuid = UUID.randomUUID();
		this.registry.replace(original.asRespawned(newUuid, NOW));

		assertAll(
				() -> assertTrue(this.registry.findByEntityUuid(oldUuid).isEmpty(), "старый UUID обязан выйти из индекса"),
				() -> assertTrue(this.registry.findByEntityUuid(newUuid).isPresent()));
	}

	@Test
	void replaceRejectsStaleRevision() {
		ViewerParrotRecord original = record("viewer1");
		this.registry.put(original);
		this.registry.replace(original.withTrait(ParrotTrait.LOUD, NOW));

		// Устаревший результат I/O с прежним revision применяться не должен.
		assertThrows(IllegalStateException.class, () -> this.registry.replace(original.withTrait(ParrotTrait.LAZY, NOW)));
	}

	@Test
	void replaceCannotChangeIncarnation() {
		ViewerParrotRecord original = record("viewer1");
		this.registry.put(original);

		ViewerParrotRecord foreign = new ViewerParrotRecord(original.viewer(), UUID.randomUUID(),
				original.generation(), original.entityUuid(), original.variant(), original.trait(),
				original.desiredBehavior(), original.physicalStatus(), null, NOW, NOW,
				original.revision() + 1);

		assertThrows(IllegalStateException.class, () -> this.registry.replace(foreign));
	}

	@Test
	void unloadedStillOccupiesItsSlot() {
		// UNLOADED — это runtime-состояние; на диске запись остаётся PRESENT.
		this.registry.put(record("viewer1"));
		assertEquals(1, this.registry.reservedSlots());
	}

	@Test
	void deadFreesTheSlotButMissingWithUuidDoesNot() {
		ViewerParrotRecord alive = record("viewer1");
		this.registry.put(alive);
		assertEquals(1, this.registry.reservedSlots());

		this.registry.replace(alive.asMissing(NOW));
		assertEquals(1, this.registry.reservedSlots(), "MISSING с ожидаемым UUID слот занимает");

		ViewerParrotRecord missing = this.registry.find(ViewerId.of("viewer1")).orElseThrow();
		this.registry.replace(missing.asDead(NOW));
		assertEquals(0, this.registry.reservedSlots(), "DEAD слот освобождает");
	}

	@Test
	void missingWithoutExpectedUuidFreesSlot() {
		ViewerParrotRecord alive = record("viewer1");
		this.registry.put(alive);

		ViewerParrotRecord forgotten = new ViewerParrotRecord(alive.viewer(), alive.incarnationId(),
				alive.generation(), null, alive.variant(), alive.trait(), alive.desiredBehavior(),
				PhysicalStatus.MISSING, null, NOW, NOW, alive.revision() + 1);
		this.registry.replace(forgotten);

		assertEquals(0, this.registry.reservedSlots());
	}

	@Test
	void reservationsCountTowardCapacitySoConcurrentCreatesCannotExceedIt() {
		for (int i = 0; i < MAX_ACTIVE; i++) {
			assertTrue(this.registry.reserveSlot(MAX_ACTIVE), "резерв " + i);
		}

		// 51-й резерв обязан быть отвергнут ещё до какой-либо записи на диск.
		assertAll(
				() -> assertFalse(this.registry.reserveSlot(MAX_ACTIVE)),
				() -> assertEquals(MAX_ACTIVE, this.registry.reservedSlots()),
				() -> assertEquals(0, this.registry.size(), "отказ не должен создавать записей"));
	}

	@Test
	void abortedReservationReleasesCapacity() {
		assertTrue(this.registry.reserveSlot(1));
		assertFalse(this.registry.reserveSlot(1));

		this.registry.releaseReservation();
		assertTrue(this.registry.reserveSlot(1));
	}

	@Test
	void releasingAbsentReservationIsAnError() {
		assertThrows(IllegalStateException.class, () -> this.registry.releaseReservation());
	}

	@Test
	void loweringLimitBelowCurrentCountRemovesNothingButBlocksNewSpawns() {
		this.registry.put(record("viewer1"));
		this.registry.put(record("viewer2"));

		assertAll(
				() -> assertEquals(2, this.registry.size()),
				() -> assertFalse(this.registry.hasFreeSlot(1)),
				() -> assertFalse(this.registry.reserveSlot(1)));
	}

	@Test
	void removeLeavesTombstoneThatRejectsOnlyThatIncarnation() {
		ViewerParrotRecord record = record("viewer1");
		this.registry.put(record);

		Tombstone tombstone = this.registry.remove(ViewerId.of("viewer1"), NOW).orElseThrow();

		assertAll(
				() -> assertFalse(this.registry.contains(ViewerId.of("viewer1"))),
				() -> assertTrue(this.registry.isTombstoned(record.incarnationId())),
				() -> assertFalse(this.registry.isTombstoned(UUID.randomUUID())),
				() -> assertEquals(record.generation(), tombstone.lastGeneration()),
				// Слот освобождается вместе с записью.
				() -> assertEquals(0, this.registry.reservedSlots()),
				// UUID выходит из индекса, иначе старая сущность привяжется обратно.
				() -> assertTrue(this.registry.findByEntityUuid(record.entityUuid()).isEmpty()));
	}

	@Test
	void repeatedRemoveIsANoOp() {
		this.registry.put(record("viewer1"));
		assertTrue(this.registry.remove(ViewerId.of("viewer1"), NOW).isPresent());
		assertTrue(this.registry.remove(ViewerId.of("viewer1"), NOW).isEmpty());
	}

	@Test
	void recreateAfterRemoveIsNotRejectedByOldTombstone() {
		ViewerParrotRecord first = record("viewer1");
		this.registry.put(first);
		this.registry.remove(ViewerId.of("viewer1"), NOW);

		ViewerParrotRecord second = record("viewer1");
		this.registry.put(second);

		assertAll(
				() -> assertTrue(this.registry.isTombstoned(first.incarnationId())),
				() -> assertFalse(this.registry.isTombstoned(second.incarnationId())),
				() -> assertEquals(1, this.registry.tombstonesFor(ViewerId.of("viewer1")).size()));
	}

	@Test
	void listIsSortedByViewerId() {
		this.registry.put(record("viewer3"));
		this.registry.put(record("viewer1"));
		this.registry.put(record("viewer2"));

		List<String> ids = this.registry.allSorted().stream()
				.map(r -> r.viewerId().value())
				.toList();

		assertEquals(List.of("viewer1", "viewer2", "viewer3"), ids);
	}

	@Test
	void replaceAllRebuildsIndexesAndClearsReservations() {
		this.registry.reserveSlot(MAX_ACTIVE);
		this.registry.put(record("stale"));

		ViewerParrotRecord loaded = record("viewer1");
		UUID streamer = UUID.randomUUID();
		this.registry.replaceAll(List.of(loaded), List.of(), streamer);

		assertAll(
				() -> assertFalse(this.registry.contains(ViewerId.of("stale"))),
				() -> assertTrue(this.registry.contains(ViewerId.of("viewer1"))),
				() -> assertEquals(0, this.registry.pendingReservations()),
				() -> assertEquals(Optional.of(streamer), this.registry.streamerPlayerUuid()),
				() -> assertTrue(this.registry.findByEntityUuid(loaded.entityUuid()).isPresent()));
	}
}
