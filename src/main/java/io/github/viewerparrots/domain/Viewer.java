package io.github.viewerparrots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Зритель: постоянный {@link ViewerId}, отображаемое имя и момент создания.
 *
 * <p>{@code nickname} — кличка птицы из {@code !name}; {@code null} означает «показывать
 * имя зрителя». Кличка не заменяет {@code displayName}: списки, рулетка и панель
 * продолжают называть зрителя его именем.
 */
public record Viewer(ViewerId viewerId, DisplayName displayName, Instant createdAt, DisplayName nickname) {
	public Viewer {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(createdAt, "createdAt");
	}

	public Viewer(ViewerId viewerId, DisplayName displayName, Instant createdAt) {
		this(viewerId, displayName, createdAt, null);
	}

	public Viewer withDisplayName(DisplayName newName) {
		return new Viewer(this.viewerId, newName, this.createdAt, this.nickname);
	}

	public Viewer withNickname(DisplayName newNickname) {
		return new Viewer(this.viewerId, this.displayName, this.createdAt, newNickname);
	}

	public Optional<DisplayName> nicknameValue() {
		return Optional.ofNullable(this.nickname);
	}

	/** Имя над птицей: кличка, если задана, иначе имя зрителя. */
	public DisplayName shownName() {
		return this.nickname != null ? this.nickname : this.displayName;
	}
}
