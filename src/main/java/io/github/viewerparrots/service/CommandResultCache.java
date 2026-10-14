package io.github.viewerparrots.service;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.time.GameClock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Дедупликация по {@code commandId} (раздел 6.2 ТЗ).
 *
 * <p>Повторный запрос с тем же идентификатором не выполняет действие ещё раз, а возвращает
 * сохранённый результат. Кэш ограничен по размеру и времени; через рестарт дедупликация
 * не гарантируется, потому что кэш не сохраняется.
 *
 * <p>Это защита от повторной доставки одного запроса, а не от новых команд: обычный
 * повторный {@code create} с новым commandId отвергается по viewerId.
 */
public final class CommandResultCache {
	public static final int MAX_ENTRIES = 1024;
	public static final long TTL_TICKS = 5 * 60 * 20L;

	private final GameClock clock;
	private final Map<UUID, Entry> entries;

	public CommandResultCache(GameClock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.entries = new LinkedHashMap<>(16, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
				// Ограничение по размеру: очередь futures не должна расти без предела.
				if (size() > MAX_ENTRIES) cancel(eldest.getValue());
				return size() > MAX_ENTRIES;
			}
		};
	}

	private record Entry(CommandResult result, long expiresAtTick, CompletableFuture<CommandResult> completion) {
	}

	public Optional<CommandResult> find(UUID commandId) {
		Entry entry = this.entries.get(commandId);
		if (entry == null) {
			return Optional.empty();
		}
		if (this.clock.currentTick() >= entry.expiresAtTick()) {
			this.entries.remove(commandId);
			cancel(entry);
			return Optional.empty();
		}
		return Optional.of(entry.result());
	}

	public void remember(CommandResult result) {
		Objects.requireNonNull(result, "result");
		Entry previous = this.entries.get(result.commandId());
		CompletableFuture<CommandResult> completion = previous == null
				? new CompletableFuture<>() : previous.completion();
		this.entries.put(result.commandId(),
				new Entry(result, this.clock.currentTick() + TTL_TICKS, completion));
		if (result.status().isTerminal()) completion.complete(result);
	}

	/** Final feedback shares the bounded cache entry and cannot outlive its eviction. */
	public CompletableFuture<CommandResult> completion(UUID commandId) {
		if (find(commandId).isEmpty()) {
			return CompletableFuture.completedFuture(CommandResult.rejected(commandId, ErrorCode.INVALID_STATE));
		}
		return this.entries.get(commandId).completion();
	}

	private static void cancel(Entry entry) {
		entry.completion().complete(new CommandResult(entry.result().commandId(), CommandStatus.CANCELLED,
				ErrorCode.INVALID_STATE, "viewerparrots.action.cancelled",
				CommandResult.details("viewerId", entry.result().details().getOrDefault("viewerId", ""),
						"action", entry.result().details().getOrDefault("action", ""),
						"reason", "completion expired or server stopped"), 0));
	}

	public int size() {
		return this.entries.size();
	}

	public void clear() {
		for (Entry entry : this.entries.values()) cancel(entry);
		this.entries.clear();
	}
}
