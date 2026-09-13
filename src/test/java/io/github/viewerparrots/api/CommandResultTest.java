package io.github.viewerparrots.api;

import io.github.viewerparrots.diagnostics.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandResultTest {

	private static final UUID COMMAND_ID = UUID.randomUUID();

	@Test
	@DisplayName("порядок details сохраняется: значения подставляются в сообщение позиционно")
	void detailsPreserveInsertionOrder() {
		// Регрессия: раньше здесь использовался Map.of, у которого порядок обхода
		// не определён, и аргументы сообщения приходили перемешанными —
		// «Parrot created: LAZY, variant viewer1, trait YELLOW_BLUE».
		Map<String, String> details = CommandResult.details(
				"viewerId", "viewer1", "variant", "YELLOW_BLUE", "trait", "LAZY");

		CommandResult result = CommandResult.succeeded(COMMAND_ID, "viewerparrots.create.ok", details);

		assertAll(
				() -> assertEquals(List.of("viewerId", "variant", "trait"),
						List.copyOf(result.details().keySet())),
				() -> assertEquals(List.of("viewer1", "YELLOW_BLUE", "LAZY"),
						List.copyOf(result.details().values())));
	}

	@Test
	@DisplayName("порядок сохраняется и при большом числе полей")
	void orderSurvivesManyEntries() {
		Map<String, String> details = CommandResult.details(
				"a", "1", "b", "2", "c", "3", "d", "4", "e", "5", "f", "6", "g", "7", "h", "8");

		CommandResult result = CommandResult.succeeded(COMMAND_ID, "key", details);

		assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h"),
				List.copyOf(result.details().keySet()));
	}

	@Test
	@DisplayName("непарные аргументы details — ошибка")
	void oddNumberOfDetailArgumentsIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> CommandResult.details("viewerId"));
	}

	@Test
	@DisplayName("успешный результат не несёт кода ошибки")
	void successCannotCarryErrorCode() {
		assertThrows(IllegalArgumentException.class, () -> new CommandResult(
				COMMAND_ID, CommandStatus.SUCCEEDED, ErrorCode.INVALID_STATE, "key", Map.of(), 0L));
	}

	@Test
	@DisplayName("неуспешный результат обязан нести код ошибки")
	void failureRequiresErrorCode() {
		assertThrows(IllegalArgumentException.class, () -> new CommandResult(
				COMMAND_ID, CommandStatus.FAILED, null, "key", Map.of(), 0L));
	}

	@Test
	@DisplayName("retryAfter разрешён только кодам лимитов")
	void retryAfterOnlyForLimitCodes() {
		assertAll(
				() -> assertEquals(120L, CommandResult
						.retryLater(COMMAND_ID, ErrorCode.COMMAND_ON_COOLDOWN, 120L).retryAfterTicks()),
				() -> assertThrows(IllegalArgumentException.class, () -> CommandResult
						.retryLater(COMMAND_ID, ErrorCode.INVALID_STATE, 10L)));
	}

	@Test
	@DisplayName("messageKey каждого кода выводится из его имени")
	void messageKeysFollowCodeNames() {
		assertAll(
				() -> assertEquals("viewerparrots.error.viewer_not_found",
						ErrorCode.VIEWER_NOT_FOUND.messageKey()),
				() -> assertEquals("viewerparrots.error.no_safe_position",
						ErrorCode.NO_SAFE_POSITION.messageKey()));
	}
}
