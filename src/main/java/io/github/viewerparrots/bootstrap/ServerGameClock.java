package io.github.viewerparrots.bootstrap;

import io.github.viewerparrots.time.GameClock;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.Objects;

/**
 * Часы, привязанные к конкретному экземпляру сервера.
 *
 * <p>Счётчик тиков берётся у сервера, а не считается самостоятельно: при остановленном
 * сервере он не растёт, и cooldown не «протухает» за время простоя.
 */
public final class ServerGameClock implements GameClock {
	private final MinecraftServer server;

	public ServerGameClock(MinecraftServer server) {
		this.server = Objects.requireNonNull(server, "server");
	}

	@Override
	public long currentTick() {
		return this.server.getTicks();
	}

	@Override
	public Instant now() {
		return Instant.now();
	}
}
