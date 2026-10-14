package io.github.viewerparrots.api.event;

import io.github.viewerparrots.domain.ViewerId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Внутрипроцессное уведомление о произошедшем изменении (раздел 6.3 ТЗ).
 *
 * <p>Событие публикуется на server thread ПОСЛЕ соответствующего изменения, а для durable
 * операций — после commit. Доставка через рестарт не гарантируется: это уведомление,
 * а не журнал.
 * <p>У глобальных COMMAND_* событий (например SAVE или SETSTREAMER) viewerId отсутствует.
 * События сущности и lifecycle всегда требуют viewerId.
 */
public record ParrotEvent(
		UUID eventId,
		Type type,
		UUID commandId,
		ViewerId viewerId,
		UUID incarnationId,
		long generation,
		Instant timestamp,
		long serverTick,
		String oldState,
		String newState,
		String reason
) {
	public enum Type {
		PARROT_CREATED,
		PARROT_REMOVED,
		PARROT_BOUND,
		PARROT_UNLOADED,
		PARROT_MISSING,
		PARROT_DIED,
		PARROT_RESPAWNED,
		BEHAVIOR_CHANGED,
		COMMAND_ACCEPTED,
		COMMAND_COMPLETED,
		COMMAND_REJECTED
	}

	public ParrotEvent {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(type, "type");
		if (type != Type.COMMAND_ACCEPTED && type != Type.COMMAND_COMPLETED && type != Type.COMMAND_REJECTED)
			Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(timestamp, "timestamp");
	}

	public Optional<UUID> command() {
		return Optional.ofNullable(this.commandId);
	}

	public static ParrotEvent of(Type type, ViewerId viewerId, UUID incarnationId, long generation,
			Instant timestamp, long serverTick, UUID commandId, String oldState, String newState,
			String reason) {
		return new ParrotEvent(UUID.randomUUID(), type, commandId, viewerId, incarnationId,
				generation, timestamp, serverTick, oldState, newState, reason);
	}
}
