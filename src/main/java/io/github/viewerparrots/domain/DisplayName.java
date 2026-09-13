package io.github.viewerparrots.domain;

import java.util.Objects;

/**
 * Отображаемое имя зрителя: 1–32 Unicode code points после trim, без управляющих символов,
 * переводов строк и символа форматирования {@code §} (раздел 4.1 ТЗ).
 *
 * <p>Разные зрители могут носить одинаковые имена — различает их только {@link ViewerId}.
 * Имя никогда не трактуется как JSON text, selector или команда.
 */
public final class DisplayName {
	public static final int MAX_CODE_POINTS = 32;
	private static final int SECTION_SIGN = 0x00a7;

	private final String value;

	private DisplayName(String value) {
		this.value = value;
	}

	/**
	 * @throws InvalidDisplayNameException если имя пусто, длиннее 32 code points или содержит
	 *         управляющий символ либо {@code §}
	 */
	public static DisplayName of(String raw) {
		if (raw == null) {
			throw new InvalidDisplayNameException("имя не задано");
		}

		// strip(), а не trim(): trim() срезает всё до U+0020 включительно и потому молча
		// съел бы управляющие символы по краям вместо того, чтобы отвергнуть имя.
		String trimmed = raw.strip();
		if (trimmed.isEmpty()) {
			throw new InvalidDisplayNameException("имя пусто после удаления пробелов");
		}

		int codePoints = trimmed.codePointCount(0, trimmed.length());
		if (codePoints > MAX_CODE_POINTS) {
			throw new InvalidDisplayNameException(
					"имя длиннее " + MAX_CODE_POINTS + " символов: " + codePoints);
		}

		for (int i = 0; i < trimmed.length(); ) {
			int codePoint = trimmed.codePointAt(i);
			if (codePoint == SECTION_SIGN) {
				throw new InvalidDisplayNameException("имя содержит символ форматирования §");
			}
			// Отсекает и переводы строк, и прочие управляющие символы.
			if (Character.getType(codePoint) == Character.CONTROL) {
				throw new InvalidDisplayNameException(
						"имя содержит управляющий символ U+" + String.format("%04X", codePoint));
			}
			i += Character.charCount(codePoint);
		}

		return new DisplayName(trimmed);
	}

	public static boolean isValid(String raw) {
		try {
			of(raw);
			return true;
		} catch (InvalidDisplayNameException e) {
			return false;
		}
	}

	public String value() {
		return this.value;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof DisplayName other && this.value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.value);
	}

	@Override
	public String toString() {
		return this.value;
	}

	public static final class InvalidDisplayNameException extends IllegalArgumentException {
		public InvalidDisplayNameException(String message) {
			super(message);
		}
	}
}
