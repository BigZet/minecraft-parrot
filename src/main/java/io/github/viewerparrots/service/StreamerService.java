package io.github.viewerparrots.service;

import io.github.viewerparrots.registry.ViewerParrotRegistry;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

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
	private final ServerContext context;
	private boolean assignmentPending;
	private final Map<UUID, CachedAssignment> results = new LinkedHashMap<>();
	private record CachedAssignment(long expiresAtTick, CompletableFuture<CommandResult> result) { }

	public StreamerService(MinecraftServer server, ViewerParrotRegistry registry) {
		this.server = Objects.requireNonNull(server, "server");
		this.registry = Objects.requireNonNull(registry, "registry");
		this.context = null;
	}

	public StreamerService(ServerContext context) {
		this.context = Objects.requireNonNull(context, "context");
		this.server = context.server();
		this.registry = context.registry();
	}

	public Optional<UUID> configuredUuid() {
		return this.registry.streamerPlayerUuid();
	}

	public boolean isConfigured() {
		return this.registry.streamerPlayerUuid().isPresent();
	}

	void assign(UUID playerUuid) {
		this.registry.setStreamerPlayerUuid(Objects.requireNonNull(playerUuid, "playerUuid"));
	}

	public boolean operationPending() { return this.assignmentPending; }

	public CompletableFuture<CommandResult> assign(UUID commandId, ActorContext actor, UUID playerUuid) {
		Objects.requireNonNull(this.context, "durable assignment requires server context");
		long started = AuditLog.started();
		UUID previousTarget = this.registry.streamerPlayerUuid().orElse(null);
		if (!this.context.acceptingCommands())
			return rejected(commandId, ErrorCode.INVALID_STATE, started);
		if (!this.context.permissions().isAllowed(actor, ParrotAction.SETSTREAMER))
			return rejected(commandId, ErrorCode.PERMISSION_DENIED, started);
		long now = this.context.clock().currentTick();
		this.results.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now && entry.getValue().result().isDone());
		CachedAssignment cached = this.results.get(commandId);
		if (cached != null) {
			AuditLog.debug("streamer_cached", identity(commandId), "CACHED", null, started, Map.of(), null);
			return cached.result();
		}
		if (!this.context.allowsMutations())
			return rejected(commandId, ErrorCode.STORAGE_UNAVAILABLE, started);
		if (this.assignmentPending)
			return rejected(commandId, ErrorCode.OPERATION_PENDING, started);
		if (this.server.getPlayerManager().getPlayer(playerUuid) == null)
			return rejected(commandId, ErrorCode.TARGET_UNAVAILABLE, started);
		if (this.context.writer().queueDepth() >= this.context.config().limits().queueCapacity())
			return rejected(commandId, ErrorCode.SERVER_BUSY, started);
		this.assignmentPending = true;
		AuditLog.debug("streamer_accepted", identity(commandId), "ACCEPTED", null, started,
				Map.of("targetPlayerUuid", playerUuid), null);
		CompletableFuture<CommandResult> result = this.context.writer().submit(() -> {
			JournalEntryDto entry = new JournalEntryDto(1, this.context.repository().nextJournalSequence(), commandId,
					JournalEntryDto.Phase.COMMIT, "__streamer__", null, 0, null, null, playerUuid, null);
			this.context.repository().appendJournal(entry);
			return entry.sequence();
		}).thenApply(sequence -> {
			this.registry.setStreamerPlayerUuid(playerUuid);
			this.registry.journalApplied(sequence);
			for (var viewerId : this.context.loadedIndex().loadedViewerIds()) {
				var runtime = this.context.runtime(viewerId);
				if (runtime.pendingAction().isPresent() || runtime.behavior() == BehaviorState.RETURNING)
					this.context.commandService().cancelRuntime(viewerId, ErrorCode.TARGET_UNAVAILABLE);
				runtime.cancelAutonomousMovement();
				runtime.setTargetPlayerUuid(playerUuid);
				if (runtime.behavior() != BehaviorState.DANCING)
					this.context.loadedIndex().find(viewerId).ifPresent(parrot -> parrot.getNavigation().stop());
			}
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("previousTargetPlayerUuid", previousTarget);
			details.put("targetPlayerUuid", playerUuid);
			details.put("sequence", sequence);
			AuditLog.info("streamer_committed", identity(commandId), "COMMITTED", null, started, details, null);
			return CommandResult.succeeded(commandId, "viewerparrots.streamer.assigned",
					CommandResult.details("uuid", playerUuid.toString()));
		}).exceptionally(error -> {
			this.context.degrade("streamer assignment not durable: " + error.getMessage());
			AuditLog.error("streamer_persistence_failed", identity(commandId), "FAILED", ErrorCode.STORAGE_UNAVAILABLE.name(), started,
					Map.of("targetPlayerUuid", playerUuid), error);
			return CommandResult.failed(commandId, ErrorCode.STORAGE_UNAVAILABLE, commandId.toString());
		}).whenComplete((completed, error) -> {
			this.assignmentPending = false;
			AuditLog.emit(error == null ? AuditLog.Level.DEBUG : AuditLog.Level.ERROR, "streamer_completed", identity(commandId),
					completed == null ? "FAILED" : completed.status().name(),
					error != null ? ErrorCode.INTERNAL_ERROR.name() : completed == null || completed.code() == null ? null : completed.code().name(),
					started, Map.of("targetPlayerUuid", playerUuid), error);
		});
		if (this.results.size() >= 1024) {
			var oldest = this.results.entrySet().iterator();
			if (oldest.hasNext()) { oldest.next(); oldest.remove(); }
		}
		this.results.put(commandId, new CachedAssignment(now + 6000L, result));
		return result;
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

	private static AuditLog.Context identity(UUID commandId) {
		return AuditLog.Context.viewer(commandId, null, ParrotAction.SETSTREAMER.name());
	}
	private static CompletableFuture<CommandResult> rejected(UUID commandId, ErrorCode reason, long started) {
		CommandResult result = CommandResult.rejected(commandId, reason);
		AuditLog.debug("streamer_rejected", identity(commandId), result.status().name(), reason.name(), started, Map.of(), null);
		return CompletableFuture.completedFuture(result);
	}
}
