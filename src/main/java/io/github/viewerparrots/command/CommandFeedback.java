package io.github.viewerparrots.command;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.diagnostics.ErrorCode;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * Превращение {@link CommandResult} в сообщение отправителю (раздел 13 ТЗ).
 *
 * <p>Используется {@code translatableWithFallback}: на dedicated server консоль не имеет
 * загруженных переводов, и без fallback она увидела бы сырой ключ.
 *
 * <p>Секунды округляются вверх из тиков: «доступен через 0 с» при остатке в один тик
 * выглядело бы как ошибка.
 */
public final class CommandFeedback {
	private static final int TICKS_PER_SECOND = 20;

	private CommandFeedback() {
	}

	public static void send(ServerCommandSource source, CommandResult result) {
		if (result.succeeded()) {
			source.sendFeedback(() -> Text.translatableWithFallback(
					result.messageKey(), fallbackFor(result), detailValues(result)), true);
			return;
		}
		source.sendError(Text.translatableWithFallback(
				result.messageKey(), fallbackFor(result), detailValues(result)));
	}

	private static Object[] detailValues(CommandResult result) {
		return result.details().values().toArray();
	}

	/**
	 * Читаемый текст на случай отсутствующего перевода. Для кодов с retryAfter секунды
	 * подставляются прямо в него, чтобы сообщение оставалось информативным.
	 */
	private static String fallbackFor(CommandResult result) {
		ErrorCode code = result.code();
		if (code == null) {
			return describeDetails(result.messageKey(), result.details());
		}
		if (code.carriesRetryAfter()) {
			return code.name() + ": повторите через " + toSeconds(result.retryAfterTicks()) + " с";
		}
		return code.name() + describeDetailsSuffix(result.details());
	}

	private static String describeDetails(String messageKey, Map<String, String> details) {
		return messageKey + describeDetailsSuffix(details);
	}

	private static String describeDetailsSuffix(Map<String, String> details) {
		if (details.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(" (");
		boolean first = true;
		for (Map.Entry<String, String> entry : details.entrySet()) {
			if (!first) {
				builder.append(", ");
			}
			first = false;
			builder.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return builder.append(')').toString();
	}

	public static long toSeconds(long ticks) {
		return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
	}
}
