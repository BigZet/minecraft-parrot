package io.github.viewerparrots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Неизменяемая запись реестра (раздел 4.1 ТЗ). Каждое изменение создаёт новый экземпляр
 * с увеличенным {@code revision}, чтобы операции применялись в известном порядке.
 *
 * <p>Инварианты, проверяемые здесь (раздел 4.4):
 * <ul>
 *   <li>{@code generation} ≥ 1;</li>
 *   <li>{@code DEAD} не содержит действующего {@code entityUuid};</li>
 *   <li>{@code PRESENT} обязан содержать {@code entityUuid} — иначе физический слот
 *       занят неизвестно чем.</li>
 * </ul>
 */
public record ViewerParrotRecord(
		Viewer viewer,
		UUID incarnationId,
		long generation,
		UUID entityUuid,
		ParrotVariant variant,
		ParrotTrait trait,
		DesiredBehavior desiredBehavior,
		PhysicalStatus physicalStatus,
		WorldLocation lastKnownLocation,
		Instant lastSpawnedAt,
		Instant updatedAt,
		long revision
) {
	public ViewerParrotRecord {
		Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(incarnationId, "incarnationId");
		Objects.requireNonNull(variant, "variant");
		Objects.requireNonNull(trait, "trait");
		Objects.requireNonNull(desiredBehavior, "desiredBehavior");
		Objects.requireNonNull(physicalStatus, "physicalStatus");
		Objects.requireNonNull(updatedAt, "updatedAt");

		if (generation < 1) {
			throw new IllegalArgumentException("generation должен быть ≥ 1, получено " + generation);
		}
		if (revision < 0) {
			throw new IllegalArgumentException("revision не может быть отрицательным, получено " + revision);
		}
		if (physicalStatus == PhysicalStatus.DEAD && entityUuid != null) {
			throw new IllegalArgumentException("DEAD-запись не может содержать действующий entityUuid");
		}
		if (physicalStatus == PhysicalStatus.PRESENT && entityUuid == null) {
			throw new IllegalArgumentException("PRESENT-запись обязана содержать entityUuid");
		}
	}

	public ViewerId viewerId() {
		return this.viewer.viewerId();
	}

	public Optional<UUID> expectedEntityUuid() {
		return Optional.ofNullable(this.entityUuid);
	}

	public Optional<WorldLocation> location() {
		return Optional.ofNullable(this.lastKnownLocation);
	}

	public Optional<Instant> spawnedAt() {
		return Optional.ofNullable(this.lastSpawnedAt);
	}

	/**
	 * Занимает ли запись зарезервированный физический слот (раздел 9.5 ТЗ).
	 *
	 * <p>{@code PRESENT} занимает слот всегда — выгрузка его не освобождает, потому что
	 * старая сущность продолжает существовать в выгруженном чанке. {@code MISSING} занимает
	 * слот только пока сохраняется ожидаемый UUID: именно он может внезапно загрузиться.
	 * {@code DEAD} слот освобождает.
	 */
	public boolean occupiesCapacitySlot() {
		return switch (this.physicalStatus) {
			case PRESENT -> true;
			case MISSING -> this.entityUuid != null;
			case DEAD -> false;
		};
	}

	public ViewerParrotRecord withDisplayName(DisplayName newName, Instant now) {
		return bump(now).withViewer(this.viewer.withDisplayName(newName));
	}

	public ViewerParrotRecord withTrait(ParrotTrait newTrait, Instant now) {
		ViewerParrotRecord bumped = bump(now);
		return new ViewerParrotRecord(bumped.viewer, bumped.incarnationId, bumped.generation,
				bumped.entityUuid, bumped.variant, newTrait, bumped.desiredBehavior,
				bumped.physicalStatus, bumped.lastKnownLocation, bumped.lastSpawnedAt,
				bumped.updatedAt, bumped.revision);
	}

	public ViewerParrotRecord withDesiredBehavior(DesiredBehavior behavior, Instant now) {
		ViewerParrotRecord bumped = bump(now);
		return new ViewerParrotRecord(bumped.viewer, bumped.incarnationId, bumped.generation,
				bumped.entityUuid, bumped.variant, bumped.trait, behavior,
				bumped.physicalStatus, bumped.lastKnownLocation, bumped.lastSpawnedAt,
				bumped.updatedAt, bumped.revision);
	}

	public ViewerParrotRecord withLocation(WorldLocation location, Instant now) {
		ViewerParrotRecord bumped = bump(now);
		return new ViewerParrotRecord(bumped.viewer, bumped.incarnationId, bumped.generation,
				bumped.entityUuid, bumped.variant, bumped.trait, bumped.desiredBehavior,
				bumped.physicalStatus, location, bumped.lastSpawnedAt,
				bumped.updatedAt, bumped.revision);
	}

	/** Смерть снимает ожидаемый UUID: раздел 4.4 запрещает их сочетание. */
	public ViewerParrotRecord asDead(Instant now) {
		ViewerParrotRecord bumped = bump(now);
		return new ViewerParrotRecord(bumped.viewer, bumped.incarnationId, bumped.generation,
				null, bumped.variant, bumped.trait, bumped.desiredBehavior,
				PhysicalStatus.DEAD, bumped.lastKnownLocation, bumped.lastSpawnedAt,
				bumped.updatedAt, bumped.revision);
	}

	public ViewerParrotRecord asMissing(Instant now) {
		ViewerParrotRecord bumped = bump(now);
		return new ViewerParrotRecord(bumped.viewer, bumped.incarnationId, bumped.generation,
				bumped.entityUuid, bumped.variant, bumped.trait, bumped.desiredBehavior,
				PhysicalStatus.MISSING, bumped.lastKnownLocation, bumped.lastSpawnedAt,
				bumped.updatedAt, bumped.revision);
	}

	/**
	 * Замена физического экземпляра: новая generation и новый UUID при сохранении
	 * identity, trait и variant; намерение сбрасывается в IDLE (раздел 9.4 ТЗ).
	 */
	public ViewerParrotRecord asRespawned(UUID newEntityUuid, Instant now) {
		Objects.requireNonNull(newEntityUuid, "newEntityUuid");
		return new ViewerParrotRecord(this.viewer, this.incarnationId, this.generation + 1,
				newEntityUuid, this.variant, this.trait, DesiredBehavior.IDLE,
				PhysicalStatus.PRESENT, this.lastKnownLocation, now,
				now, this.revision + 1);
	}

	private ViewerParrotRecord withViewer(Viewer newViewer) {
		return new ViewerParrotRecord(newViewer, this.incarnationId, this.generation,
				this.entityUuid, this.variant, this.trait, this.desiredBehavior,
				this.physicalStatus, this.lastKnownLocation, this.lastSpawnedAt,
				this.updatedAt, this.revision);
	}

	private ViewerParrotRecord bump(Instant now) {
		Objects.requireNonNull(now, "now");
		return new ViewerParrotRecord(this.viewer, this.incarnationId, this.generation,
				this.entityUuid, this.variant, this.trait, this.desiredBehavior,
				this.physicalStatus, this.lastKnownLocation, this.lastSpawnedAt,
				now, this.revision + 1);
	}

	/** Первая регистрация: generation 1, revision 0, намерение IDLE. */
	public static ViewerParrotRecord created(Viewer viewer, UUID incarnationId, UUID entityUuid,
			ParrotVariant variant, ParrotTrait trait, WorldLocation location, Instant now) {
		return new ViewerParrotRecord(viewer, incarnationId, 1L, entityUuid, variant, trait,
				DesiredBehavior.IDLE, PhysicalStatus.PRESENT, location, now, now, 0L);
	}
}
