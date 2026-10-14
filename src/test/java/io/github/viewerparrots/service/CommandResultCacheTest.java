package io.github.viewerparrots.service;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.time.TestGameClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дедупликация по commandId из раздела 6.2 ТЗ: bounded cache на 5 минут и 1024 записи. */
class CommandResultCacheTest {

	private TestGameClock clock;
	private CommandResultCache cache;

	@BeforeEach
	void setUp() {
		this.clock = new TestGameClock();
		this.cache = new CommandResultCache(this.clock);
	}

	private static CommandResult result(UUID commandId) {
		return CommandResult.succeeded(commandId, "viewerparrots.action.ok", Map.of());
	}

	@Test
	@DisplayName("повторный commandId возвращает сохранённый результат")
	void repeatedCommandIdReturnsCachedResult() {
		UUID commandId = UUID.randomUUID();
		CommandResult first = result(commandId);
		this.cache.remember(first);

		assertEquals(first, this.cache.find(commandId).orElseThrow());
	}

	@Test
	@DisplayName("неизвестный commandId ничего не возвращает")
	void unknownCommandIdIsMiss() {
		assertTrue(this.cache.find(UUID.randomUUID()).isEmpty());
	}

	@Test
	@DisplayName("результат живёт ровно 5 минут и затем исчезает")
	void entryExpiresAfterTtl() {
		UUID commandId = UUID.randomUUID();
		this.cache.remember(result(commandId));

		this.clock.advanceTicks(CommandResultCache.TTL_TICKS - 1);
		assertTrue(this.cache.find(commandId).isPresent(), "за тик до истечения результат ещё жив");

		this.clock.advanceTicks(1);
		assertTrue(this.cache.find(commandId).isEmpty(), "на границе TTL результат исчезает");
	}

	@Test
	@DisplayName("кэш не растёт выше предела: старые записи вытесняются")
	void cacheIsBounded() {
		UUID first = UUID.randomUUID();
		this.cache.remember(result(first));

		for (int i = 0; i < CommandResultCache.MAX_ENTRIES; i++) {
			this.cache.remember(result(UUID.randomUUID()));
		}

		assertAll(
				() -> assertEquals(CommandResultCache.MAX_ENTRIES, this.cache.size()),
				() -> assertTrue(this.cache.find(first).isEmpty(), "самая старая запись вытеснена"));
	}

	@Test
	@DisplayName("отказы кэшируются так же, как успехи: повтор не выполняет действие заново")
	void rejectionsAreCachedToo() {
		UUID commandId = UUID.randomUUID();
		CommandResult rejected = CommandResult.rejected(commandId, ErrorCode.COMMAND_ON_COOLDOWN);
		this.cache.remember(rejected);

		assertEquals(rejected, this.cache.find(commandId).orElseThrow());
	}

	@Test
	void completionWaitsForFinalResultAndDuplicatesSeeThatResult() {
		UUID id = UUID.randomUUID();
		cache.remember(new CommandResult(id, CommandStatus.ACCEPTED, null,
				"viewerparrots.action.accepted", Map.of(), 0));
		var completion = cache.completion(id);
		org.junit.jupiter.api.Assertions.assertFalse(completion.isDone());
		CommandResult finalResult = result(id);
		cache.remember(finalResult);
		assertEquals(finalResult, completion.join());
		assertEquals(finalResult, cache.find(id).orElseThrow());
	}

	@Test
	void clearingCacheReleasesPendingCompletionAndBoundsReferences() {
		UUID id = UUID.randomUUID();
		cache.remember(new CommandResult(id, CommandStatus.ACCEPTED, null,
				"viewerparrots.action.accepted", Map.of(), 0));
		var completion = cache.completion(id);
		cache.clear();
		assertEquals(CommandStatus.CANCELLED, completion.join().status());
		assertEquals(0, cache.size());
	}

	@Test
	void evictingPendingResultReleasesItsCompletionFuture() {
		UUID id = UUID.randomUUID();
		cache.remember(new CommandResult(id, CommandStatus.ACCEPTED, null,
				"viewerparrots.action.accepted", Map.of(), 0));
		var completion = cache.completion(id);
		for (int i = 0; i < CommandResultCache.MAX_ENTRIES; i++) cache.remember(result(UUID.randomUUID()));
		assertEquals(CommandStatus.CANCELLED, completion.join().status());
		assertEquals(CommandResultCache.MAX_ENTRIES, cache.size());
	}
}
