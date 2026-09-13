package io.github.viewerparrots.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Неизменяемый ключ зрителя. Регистрозависимый: {@code Viewer1} и {@code viewer1} — разные
 * зрители (раздел 4.1 ТЗ). Имя зрителя ключом не является.
 */
public final class ViewerId implements Comparable<ViewerId> {
	public static final int MAX_LENGTH = 128;
	private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_LENGTH + "}");

	private final String value;

	private ViewerId(String value) {
		this.value = value;
	}

	/**
	 * @throws InvalidViewerIdException если значение не соответствует {@code [A-Za-z0-9_.:-]{1,128}}
	 */
	public static ViewerId of(String value) {
		if (value == null) {
			throw new InvalidViewerIdException("viewerId не задан");
		}
		if (value.isEmpty()) {
			throw new InvalidViewerIdException("viewerId пуст");
		}
		if (value.length() > MAX_LENGTH) {
			throw new InvalidViewerIdException("viewerId длиннее " + MAX_LENGTH + " символов: " + value.length());
		}
		if (!PATTERN.matcher(value).matches()) {
			throw new InvalidViewerIdException("viewerId содержит недопустимые символы; разрешены A-Z a-z 0-9 _ . : -");
		}
		return new ViewerId(value);
	}

	public static boolean isValid(String value) {
		return value != null && !value.isEmpty() && value.length() <= MAX_LENGTH && PATTERN.matcher(value).matches();
	}

	public String value() {
		return this.value;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ViewerId other && this.value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(this.value);
	}

	@Override
	public int compareTo(ViewerId other) {
		return this.value.compareTo(other.value);
	}

	@Override
	public String toString() {
		return this.value;
	}

	public static final class InvalidViewerIdException extends IllegalArgumentException {
		public InvalidViewerIdException(String message) {
			super(message);
		}
	}
}
