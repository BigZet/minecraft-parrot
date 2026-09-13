package io.github.viewerparrots.service;

import io.github.viewerparrots.registry.ViewerParrotRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Назначенный игрок-стример (раздел 6 ТЗ).
 *
 * <p>Все пространственные команды ориентированы на него, включая вызов из консоли.
 * Автоматического назначения первого вошедшего игрока нет: цель выбирается явно.
 *
 * <p>UUID стримера — часть данных мира и хранится в реестре, а не в общем конфиге:
 * в разных мирах стример разный. Операторских прав этот UUID не даёт.
 */
public final class StreamerService {
	private final MinecraftServer server;
	private final ViewerParrotRegistry registry;

	public StreamerService(MinecraftServer server, ViewerParrotRegistry registry) {
		this.server = Objects.requireNonNull(server, "server");
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	public Optional<UUID> configuredUuid() {
		return this.registry.streamerPlayerUuid();
	}

	public boolean isConfigured() {
		return this.registry.streamerPlayerUuid().isPresent();
	}

	public void assign(UUID playerUuid) {
		this.registry.setStreamerPlayerUuid(Objects.requireNonNull(playerUuid, "playerUuid"));
	}

	/**
	 * @return назначенный стример, если он сейчас онлайн и жив; иначе пусто —
	 *         вызывающий возвращает {@code TARGET_UNAVAILABLE}
	 */
	public Optional<ServerPlayerEntity> onlineTarget() {
		return configuredUuid()
				.map(uuid -> this.server.getPlayerManager().getPlayer(uuid))
				.filter(player -> player != null && player.isAlive());
	}

	/** Имя стримера, если он онлайн; для {@code streamer} и диагностики. */
	public Optional<String> onlineName() {
		return onlineTarget().map(player -> player.getGameProfile().getName());
	}
}
