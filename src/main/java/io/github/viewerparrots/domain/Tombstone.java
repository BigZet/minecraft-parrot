package io.github.viewerparrots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Надгробие удалённой регистрации (раздел 8.3 ТЗ). В MVP не удаляется автоматически:
 * чанк со старой копией сущности может оставаться выгруженным неограниченно долго,
 * а надгробие — единственное доказательство того, что её нельзя оживлять.
 */
public record Tombstone(ViewerId viewerId, UUID incarnationId, long lastGeneration, Instant removedAt) {
	public Tombstone {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(incarnationId, "incarnationId");
		Objects.requireNonNull(removedAt, "removedAt");
		if (lastGeneration < 1) {
			throw new IllegalArgumentException("lastGeneration должен быть ≥ 1, получено " + lastGeneration);
		}
	}

	public static Tombstone of(ViewerParrotRecord record, Instant removedAt) {
		return new Tombstone(record.viewerId(), record.incarnationId(), record.generation(), removedAt);
	}

	/**
	 * Сущность отвергается, если её маркер указывает на эту же incarnation. Новый
	 * {@code create} получает новую incarnation, поэтому к новой регистрации старая
	 * копия не привяжется (раздел 8.3).
	 */
	public boolean rejects(UUID markerIncarnationId) {
		return this.incarnationId.equals(markerIncarnationId);
	}
}
