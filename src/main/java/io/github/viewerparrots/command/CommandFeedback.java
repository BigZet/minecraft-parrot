package io.github.viewerparrots.command;

import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.diagnostics.AuditLog;
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
		if (result.succeeded() || result.status() == CommandStatus.ACCEPTED) {
			source.sendFeedback(() -> render(result), true);
			return;
		}
		source.sendError(render(result));
	}

	public static Text render(CommandResult result) {
		return Text.literal("[" + result.commandId() + "] ").append(
				Text.translatableWithFallback(result.messageKey(), fallbackFor(result), detailValues(result)));
	}

	private static Object[] detailValues(CommandResult result) {
		if (result.code() != null && result.code().carriesRetryAfter()) {
			return new Object[] { toSeconds(result.retryAfterTicks()) };
		}
		return result.details().values().stream().map(AuditLog::safeText).toArray();
	}

	/**
	 * Читаемый текст на случай отсутствующего перевода. Для кодов с retryAfter секунды
	 * подставляются прямо в него, чтобы сообщение оставалось информативным.
	 */
	private static String fallbackFor(CommandResult result) {
		ErrorCode code = result.code();
		if (code == null) {
			return switch (result.messageKey()) {
				case "viewerparrots.create.ok" -> "%s: parrot created, variant %s, trait %s";
				case "viewerparrots.remove.ok" -> "%s: registration removed";
				case "viewerparrots.rename.ok" -> "%s: name updated";
				case "viewerparrots.trait.ok" -> "%s: trait updated";
				case "viewerparrots.respawn.ok" -> "%s: respawned, generation %s";
				case "viewerparrots.action.accepted" -> "%s: command %s accepted; awaiting completion";
				case "viewerparrots.action.ok" -> "%s: command %s completed";
				case "viewerparrots.roulette.queued" -> "%s: roulette queued";
				case "viewerparrots.roulette.ok" -> "%s: won %s ×%s, dropped at the parrot's position";
				case "viewerparrots.action.delayed" -> "%s: command %s delayed by LAZY for %s ticks";
				case "viewerparrots.reload.ok" -> "Configuration reloaded";
				case "viewerparrots.streamer.assigned" -> "Streamer assigned: %s";
				case "viewerparrots.save.ok" -> "Checkpoint %s saved";
				case "viewerparrots.api.accepted" -> "Request accepted; awaiting completion";
				case "viewerparrots.api.ok" -> describeDetails("Request completed", result.details());
				case "viewerparrots.reconcile.loaded" -> "%s is already loaded (%s)";
				case "viewerparrots.reconcile.bound" -> "%s found in dimension %s";
				case "viewerparrots.reconcile.no_entity" -> "%s expects no physical instance (%s)";
				case "viewerparrots.reconcile.searching" -> "%s: limited search started, up to %s ticks";
				case "viewerparrots.reconcile.not_found" -> "%s not found at its known location (%s)";
				default -> describeDetails(result.messageKey(), result.details());
			};
		}
		if (result.status() == CommandStatus.CANCELLED) return "%s: command %s cancelled (%s)";
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
			builder.append(AuditLog.safeText(entry.getKey())).append('=').append(AuditLog.safeText(entry.getValue()));
		}
		return builder.append(')').toString();
	}

	public static long toSeconds(long ticks) {
		return ticks / TICKS_PER_SECOND + (ticks % TICKS_PER_SECOND == 0 ? 0 : 1);
	}
}
