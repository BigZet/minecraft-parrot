package io.github.viewerparrots.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Кто именно подал команду. Создаётся только доверенным adapter: по разделу 12 ТЗ сервер
 * не принимает права, trait или UUID из клиентских пакетов.
 *
 * <p>{@code viewerId} сам по себе прав не даёт, поэтому его здесь нет вовсе — он является
 * частью {@link CommandRequest}, то есть данными, а не полномочиями.
 */
public record ActorContext(SourceType sourceType, UUID senderUuid, int permissionLevel, String sourceKey, java.util.Set<io.github.viewerparrots.settings.TwitchRole> twitchRoles) {

	public enum SourceType {
		PLAYER,
		CONSOLE,
		RCON,
		COMMAND_BLOCK,
		/** Другой Java-мод в том же процессе. Не является security sandbox (раздел 12 ТЗ). */
		INTERNAL_API,
        TWITCH
	}

	public ActorContext(SourceType sourceType, UUID senderUuid, int permissionLevel, String sourceKey) {
        this(sourceType, senderUuid, permissionLevel, sourceKey, java.util.Set.of());
    }
    public static ActorContext twitch(String viewerId, java.util.Set<io.github.viewerparrots.settings.TwitchRole> roles) {
        return new ActorContext(SourceType.TWITCH, null, 4, viewerId, roles);
    }
    public boolean isTwitch() { return sourceType == SourceType.TWITCH; }
    public ActorContext {
        twitchRoles = java.util.Set.copyOf(twitchRoles);
		Objects.requireNonNull(sourceType, "sourceType");
		Objects.requireNonNull(sourceKey, "sourceKey");
		if (permissionLevel < 0 || permissionLevel > 4) {
			throw new IllegalArgumentException("permissionLevel вне диапазона 0..4: " + permissionLevel);
		}
		if (sourceType == SourceType.PLAYER && senderUuid == null) {
			throw new IllegalArgumentException("у источника PLAYER обязателен senderUuid");
		}
	}

	public Optional<UUID> sender() {
		return Optional.ofNullable(this.senderUuid);
	}

	public static ActorContext player(UUID playerUuid, int permissionLevel) {
		return new ActorContext(SourceType.PLAYER, playerUuid, permissionLevel, playerUuid.toString());
	}

	public static ActorContext console(int permissionLevel) {
		return new ActorContext(SourceType.CONSOLE, null, permissionLevel, "console");
	}
}
