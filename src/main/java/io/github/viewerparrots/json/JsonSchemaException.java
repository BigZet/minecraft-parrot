package io.github.viewerparrots.json;

/**
 * Ошибка разбора или валидации JSON с указанием пути до поля.
 *
 * <p>Путь обязателен: разделы 8.3 и 11 ТЗ требуют показывать JSON path, чтобы опечатка
 * в конфиге или повреждение реестра не выглядели как «файл неверен».
 */
public class JsonSchemaException extends RuntimeException {
	private final String path;

	public JsonSchemaException(String path, String message) {
		super(path + ": " + message);
		this.path = path;
	}

	public JsonSchemaException(String path, String message, Throwable cause) {
		super(path + ": " + message, cause);
		this.path = path;
	}

	public String path() {
		return this.path;
	}
}
