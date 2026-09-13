package io.github.viewerparrots.domain;

import java.util.Objects;

/**
 * Последняя известная позиция — подсказка для recovery, а не доказательство
 * присутствия или отсутствия сущности (раздел 4.1 ТЗ).
 *
 * <p>Измерение хранится строкой, чтобы домен не зависел от Minecraft. Координаты обязаны
 * быть конечными: раздел 8.3 требует отвергать нечисловые и не-finite значения, а не
 * подставлять их молча.
 */
public record WorldLocation(String dimension, double x, double y, double z) {
	public WorldLocation {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.isBlank()) {
			throw new IllegalArgumentException("dimension пуст");
		}
		requireFinite(x, "x");
		requireFinite(y, "y");
		requireFinite(z, "z");
	}

	private static void requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("координата " + field + " не является конечным числом: " + value);
		}
	}
}
