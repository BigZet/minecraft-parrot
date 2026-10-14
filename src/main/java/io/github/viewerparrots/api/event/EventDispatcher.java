package io.github.viewerparrots.api.event;

import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.diagnostics.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Рассылка событий слушателям (раздел 6.3 ТЗ).
 *
 * <p>Исключение одного слушателя логируется и изолируется: чужая ошибка не должна
 * ронять тик сервера и не должна мешать остальным слушателям получить событие.
 *
 * <p>Слушатель не должен блокировать тик — вызовы синхронные и выполняются на server thread.
 */
public final class EventDispatcher {
	private final List<Consumer<ParrotEvent>> listeners = new ArrayList<>();

	public void subscribe(Consumer<ParrotEvent> listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	public void unsubscribe(Consumer<ParrotEvent> listener) {
		this.listeners.remove(listener);
	}

	public void publish(ParrotEvent event) {
		Objects.requireNonNull(event, "event");
		for (Consumer<ParrotEvent> listener : List.copyOf(this.listeners)) {
			long started = AuditLog.started();
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				String action = event.type() == ParrotEvent.Type.COMMAND_ACCEPTED || event.type() == ParrotEvent.Type.COMMAND_REJECTED
						? event.newState() : null;
				AuditLog.error("listener_failed", new AuditLog.Context(event.commandId(),
						event.viewerId() == null ? null : event.viewerId().value(), event.incarnationId(),
						event.incarnationId() == null ? null : event.generation(), null, action),
						"FAILED", ErrorCode.INTERNAL_ERROR.name(), started,
						Map.of("eventId", event.eventId(), "eventType", event.type().name()), e);
			}
		}
	}

	public int listenerCount() {
		return this.listeners.size();
	}

	public void clear() {
		this.listeners.clear();
	}
}
