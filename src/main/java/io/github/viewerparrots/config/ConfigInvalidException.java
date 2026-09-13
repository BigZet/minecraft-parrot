package io.github.viewerparrots.config;

import java.util.List;

/**
 * Конфиг отвергнут целиком. Частичное применение запрещено разделом 11 ТЗ, поэтому
 * исключение несёт сразу все нарушения: администратор чинит их за один проход,
 * а не по одному за перезапуск.
 */
public class ConfigInvalidException extends RuntimeException {
	private final transient List<Violation> violations;

	public ConfigInvalidException(List<Violation> violations) {
		super(format(violations));
		this.violations = List.copyOf(violations);
	}

	/** @param path JSON path до поля, например {@code $.follow.stopDistance} */
	public record Violation(String path, String message) {
		@Override
		public String toString() {
			return this.path + ": " + this.message;
		}
	}

	public List<Violation> violations() {
		return this.violations;
	}

	private static String format(List<Violation> violations) {
		StringBuilder builder = new StringBuilder("конфигурация отвергнута, нарушений: ")
				.append(violations.size());
		for (Violation violation : violations) {
			builder.append(System.lineSeparator()).append("  ").append(violation);
		}
		return builder.toString();
	}
}
