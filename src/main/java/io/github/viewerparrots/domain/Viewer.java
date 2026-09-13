package io.github.viewerparrots.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Идентичность зрителя. Имя хранится здесь один раз и не дублируется
 * в {@link ViewerParrotRecord} (раздел 4.1 ТЗ).
 */
public record Viewer(ViewerId viewerId, DisplayName displayName, Instant createdAt) {
	public Viewer {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(createdAt, "createdAt");
	}

	public Viewer withDisplayName(DisplayName newName) {
		return new Viewer(this.viewerId, newName, this.createdAt);
	}
}
