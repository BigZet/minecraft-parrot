package io.github.viewerparrots.api;

import io.github.viewerparrots.diagnostics.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Итог команды (раздел 6.2 ТЗ). Ожидаемые ошибки приходят кодом и локализуемым ключом,
 * без исключения в бизнес-ветке.
 */
public record CommandResult(
		UUID commandId,
		CommandStatus status,
		ErrorCode code,
		String messageKey,
		Map<String, String> details,
		long retryAfterTicks
) {
	public CommandResult {
		Objects.requireNonNull(commandId, "commandId");
		Objects.requireNonNull(status, "status");
		// Порядок важен: значения подставляются в локализованное сообщение позиционно,
		// а Map.copyOf порядок обхода не сохраняет и перемешал бы аргументы.
		details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
		if (status == CommandStatus.SUCCEEDED && code != null) {
			throw new IllegalArgumentException("успешный результат не может нести код ошибки " + code);
		}
		if (status != CommandStatus.SUCCEEDED && status != CommandStatus.ACCEPTED && code == null) {
			throw new IllegalArgumentException("неуспешный результат обязан нести код ошибки");
		}
		if (retryAfterTicks < 0) {
			throw new IllegalArgumentException("retryAfterTicks не может быть отрицательным");
		}
	}

	public Optional<ErrorCode> error() {
		return Optional.ofNullable(this.code);
	}

	public boolean succeeded() {
		return this.status == CommandStatus.SUCCEEDED;
	}

	public static CommandResult succeeded(UUID commandId, String messageKey, Map<String, String> details) {
		return new CommandResult(commandId, CommandStatus.SUCCEEDED, null, messageKey, details, 0L);
	}

	/**
	 * Детали с гарантированным порядком. Вызывающие не должны использовать {@code Map.of}:
	 * его порядок обхода не определён, а значения подставляются в сообщение позиционно.
	 *
	 * @param keyValuePairs чередующиеся ключ и значение
	 */
	public static Map<String, String> details(String... keyValuePairs) {
		if (keyValuePairs.length % 2 != 0) {
			throw new IllegalArgumentException("ожидались пары ключ-значение, получено "
					+ keyValuePairs.length + " элементов");
		}
		Map<String, String> details = new LinkedHashMap<>();
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			details.put(keyValuePairs[i], keyValuePairs[i + 1]);
		}
		return details;
	}

	public static CommandResult rejected(UUID commandId, ErrorCode code) {
		return rejected(commandId, code, Map.of());
	}

	public static CommandResult rejected(UUID commandId, ErrorCode code, Map<String, String> details) {
		return new CommandResult(commandId, CommandStatus.REJECTED, code, code.messageKey(), details, 0L);
	}

	/** Отказ по лимиту: раздел 13 требует сообщать, через сколько повторять. */
	public static CommandResult retryLater(UUID commandId, ErrorCode code, long retryAfterTicks) {
		if (!code.carriesRetryAfter()) {
			throw new IllegalArgumentException("код " + code + " не предполагает retryAfterTicks");
		}
		return new CommandResult(commandId, CommandStatus.REJECTED, code, code.messageKey(),
				Map.of("retryAfterTicks", Long.toString(retryAfterTicks)), retryAfterTicks);
	}

	public static CommandResult failed(UUID commandId, ErrorCode code, String detail) {
		Map<String, String> details = new LinkedHashMap<>();
		if (detail != null) {
			details.put("detail", detail);
		}
		return new CommandResult(commandId, CommandStatus.FAILED, code, code.messageKey(), details, 0L);
	}
}
