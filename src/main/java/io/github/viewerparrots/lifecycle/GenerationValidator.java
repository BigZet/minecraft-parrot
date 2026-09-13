package io.github.viewerparrots.lifecycle;

import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ParrotMarker;
import io.github.viewerparrots.registry.ViewerParrotRegistry;

import java.util.Optional;
import java.util.UUID;

/**
 * Решает судьбу загрузившейся собственной сущности до включения AI (разделы 4.3 и 9.4 ТЗ).
 *
 * <p>Это единственное место, где отвергается «поздняя старая копия»: сервер не сканирует
 * мир целиком, поэтому после respawn прежняя сущность может лежать в выгруженном чанке
 * сколько угодно долго и однажды загрузиться. Без этой проверки в мире оказались бы два
 * действующих попугая одного зрителя.
 */
public final class GenerationValidator {

	private GenerationValidator() {
	}

	/** Что делать с загрузившейся сущностью. */
	public enum Verdict {
		/** Маркер совпал с действующей записью — привязать и включить поведение. */
		BIND,
		/** Отозванная generation, чужая incarnation или надгробие — удалить без дропа. */
		DISCARD,
		/**
		 * Реестр ещё не готов. Сущность оставить без AI и без изменений identity:
		 * удалять её до загрузки реестра нельзя, это уничтожило бы живого попугая.
		 */
		DEFER
	}

	public record Decision(Verdict verdict, String reason) {
		public boolean isBind() {
			return this.verdict == Verdict.BIND;
		}
	}

	/**
	 * @param marker        маркер сущности; пусто если его нет или он повреждён
	 * @param entityUuid    UUID загрузившейся сущности
	 * @param registryReady готов ли реестр после чтения снимка и replay журнала
	 */
	public static Decision decide(Optional<ParrotMarker> marker, UUID entityUuid,
			ViewerParrotRegistry registry, boolean registryReady) {
		if (!registryReady) {
			return new Decision(Verdict.DEFER, "реестр ещё не загружен");
		}

		if (marker.isEmpty()) {
			// Собственный тип сущности без маркера: /summon без записи реестра
			// зрителя не создаёт (раздел 2.2 ТЗ).
			return new Decision(Verdict.DISCARD, "нет маркера реестра");
		}

		ParrotMarker actual = marker.get();

		if (registry.isTombstoned(actual.incarnationId())) {
			return new Decision(Verdict.DISCARD, "incarnation удалена, действует надгробие");
		}

		Optional<ViewerParrotRecord> record = registry.find(actual.viewerId());
		if (record.isEmpty()) {
			return new Decision(Verdict.DISCARD, "в реестре нет записи " + actual.viewerId());
		}

		ViewerParrotRecord current = record.get();

		if (!actual.matches(current)) {
			return new Decision(Verdict.DISCARD, "маркер не совпадает с реестром: generation "
					+ actual.generation() + " против " + current.generation()
					+ ", incarnation " + actual.incarnationId() + " против " + current.incarnationId());
		}

		Optional<UUID> expected = current.expectedEntityUuid();
		if (expected.isEmpty()) {
			// Запись мертва или потеряла ожидаемый UUID: привязывать нечего.
			return new Decision(Verdict.DISCARD, "запись не ожидает физического экземпляра");
		}
		if (!expected.get().equals(entityUuid)) {
			return new Decision(Verdict.DISCARD, "ожидался UUID " + expected.get()
					+ ", загрузилась сущность " + entityUuid);
		}

		return new Decision(Verdict.BIND, "маркер совпал");
	}
}
