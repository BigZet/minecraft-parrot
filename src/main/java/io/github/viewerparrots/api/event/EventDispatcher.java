package io.github.viewerparrots.api.event;

import io.github.viewerparrots.ViewerParrotsMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
		for (Consumer<ParrotEvent> listener : this.listeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				ViewerParrotsMod.LOGGER.error(
						"Слушатель события {} для {} завершился ошибкой; остальные продолжат работу",
						event.type(), event.viewerId(), e);
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
