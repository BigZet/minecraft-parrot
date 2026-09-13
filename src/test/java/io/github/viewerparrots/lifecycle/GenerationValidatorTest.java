package io.github.viewerparrots.lifecycle;

import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.domain.PhysicalStatus;
import io.github.viewerparrots.domain.Viewer;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.registry.ViewerParrotRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздел 15.1 ТЗ: арбитраж generation и tombstone. Именно здесь предотвращается
 * появление двух действующих попугаев одного зрителя.
 *
 * <p>Аннотация {@code @DisplayName} не используется: её простое имя совпадает
 * с доменным классом {@link DisplayName}.
 */
class GenerationValidatorTest {

	private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
	private static final ViewerId VIEWER = ViewerId.of("viewer1");

	private ViewerParrotRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new ViewerParrotRegistry();
	}

	private static ViewerParrotRecord record(UUID incarnationId, UUID entityUuid) {
		Viewer viewer = new Viewer(VIEWER, DisplayName.of("Дмитрий"), NOW);
		return ViewerParrotRecord.created(viewer, incarnationId, entityUuid,
				ParrotVariant.BLUE, ParrotTrait.NORMAL, null, NOW);
	}

	@Test
	void bindsWhenMarkerMatchesRecord() {
		UUID incarnation = UUID.randomUUID();
		UUID entityUuid = UUID.randomUUID();
		ViewerParrotRecord stored = record(incarnation, entityUuid);
		this.registry.put(stored);

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(ParrotMarker.of(stored)), entityUuid, this.registry, true);

		assertTrue(decision.isBind(), decision.reason());
	}

	@Test
	void defersWhileRegistryIsNotReady() {
		UUID incarnation = UUID.randomUUID();
		UUID entityUuid = UUID.randomUUID();

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(new ParrotMarker(1, VIEWER, incarnation, 1L)), entityUuid, this.registry, false);

		// Удалять живого попугая до загрузки реестра нельзя.
		assertEquals(GenerationValidator.Verdict.DEFER, decision.verdict());
	}

	@Test
	void discardsEntityWithoutMarker() {
		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.empty(), UUID.randomUUID(), this.registry, true);

		assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict());
	}

	@Test
	void discardsWhenRegistryHasNoSuchViewer() {
		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(new ParrotMarker(1, VIEWER, UUID.randomUUID(), 1L)),
				UUID.randomUUID(), this.registry, true);

		assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict());
	}

	@Test
	void discardsTombstonedIncarnationEvenIfViewerWasRecreated() {
		UUID oldIncarnation = UUID.randomUUID();
		UUID oldEntityUuid = UUID.randomUUID();
		ViewerParrotRecord old = record(oldIncarnation, oldEntityUuid);
		this.registry.put(old);
		this.registry.remove(VIEWER, NOW);

		// Тот же viewerId зарегистрирован заново, но старая копия принадлежит
		// удалённой incarnation и оживать не должна.
		ViewerParrotRecord recreated = record(UUID.randomUUID(), UUID.randomUUID());
		this.registry.put(recreated);

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(ParrotMarker.of(old)), oldEntityUuid, this.registry, true);

		assertAll(
				() -> assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict()),
				() -> assertTrue(decision.reason().contains("надгробие"), decision.reason()));
	}

	@Test
	void discardsRevokedGenerationAfterRespawn() {
		UUID incarnation = UUID.randomUUID();
		UUID oldEntityUuid = UUID.randomUUID();
		ViewerParrotRecord original = record(incarnation, oldEntityUuid);
		this.registry.put(original);

		ParrotMarker oldMarker = ParrotMarker.of(original);

		// Замена: generation 2, новый UUID. Старая копия в выгруженном чанке
		// когда-нибудь загрузится — и должна быть отвергнута.
		this.registry.replace(original.asRespawned(UUID.randomUUID(), NOW));

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(oldMarker), oldEntityUuid, this.registry, true);

		assertAll(
				() -> assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict()),
				() -> assertTrue(decision.reason().contains("generation"), decision.reason()));
	}

	@Test
	void discardsWhenUuidDiffersFromExpected() {
		UUID incarnation = UUID.randomUUID();
		ViewerParrotRecord stored = record(incarnation, UUID.randomUUID());
		this.registry.put(stored);

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(ParrotMarker.of(stored)), UUID.randomUUID(), this.registry, true);

		assertAll(
				() -> assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict()),
				() -> assertTrue(decision.reason().contains("ожидался UUID"), decision.reason()));
	}

	@Test
	void discardsWhenRecordExpectsNoInstance() {
		UUID incarnation = UUID.randomUUID();
		UUID entityUuid = UUID.randomUUID();
		ViewerParrotRecord stored = record(incarnation, entityUuid);
		this.registry.put(stored);
		ParrotMarker marker = ParrotMarker.of(stored);

		this.registry.replace(stored.asDead(NOW));

		GenerationValidator.Decision decision = GenerationValidator.decide(
				Optional.of(marker), entityUuid, this.registry, true);

		assertAll(
				() -> assertEquals(PhysicalStatus.DEAD,
						this.registry.find(VIEWER).orElseThrow().physicalStatus()),
				() -> assertEquals(GenerationValidator.Verdict.DISCARD, decision.verdict()));
	}

	@Test
	void markerMatchesRequireAllThreeParts() {
		UUID incarnation = UUID.randomUUID();
		ViewerParrotRecord stored = record(incarnation, UUID.randomUUID());

		assertAll(
				() -> assertTrue(ParrotMarker.of(stored).matches(stored)),
				() -> assertTrue(!new ParrotMarker(1, ViewerId.of("other"), incarnation, 1L).matches(stored)),
				() -> assertTrue(!new ParrotMarker(1, VIEWER, UUID.randomUUID(), 1L).matches(stored)),
				() -> assertTrue(!new ParrotMarker(1, VIEWER, incarnation, 2L).matches(stored)));
	}
}
