package io.github.viewerparrots.storage;

import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.storage.dto.SnapshotDto;

/**
 * Правила версий дисковой схемы (раздел 8.3 ТЗ).
 *
 * <p>Более новая {@code schemaVersion} запрещает не только запись, но и чтение: старый код
 * не знает, какие поля появились, и молча потерял бы их при первом же checkpoint. Понижение
 * версии с потерей данных не выполняется никогда.
 *
 * <p>Пока существует единственная версия 1, поэтому преобразований нет. Класс существует,
 * чтобы место для них было определено заранее, а проверка версии — единой для снимка и журнала.
 */
public final class StorageMigrator {

	private StorageMigrator() {
	}

	/**
	 * @throws JsonSchemaException  если версия новее поддерживаемой
	 * @throws StorageFaultException если версия старше и миграции для неё нет
	 */
	public static void requireReadable(int schemaVersion, String path) {
		if (schemaVersion == SnapshotDto.CURRENT_SCHEMA_VERSION) {
			return;
		}
		if (schemaVersion > SnapshotDto.CURRENT_SCHEMA_VERSION) {
			throw new UnsupportedSchemaException(path + ": файл записан более новой версией схемы ("
					+ schemaVersion + " > " + SnapshotDto.CURRENT_SCHEMA_VERSION
					+ "); обновите мод, запись запрещена");
		}
		if (schemaVersion < 1) {
			throw new JsonSchemaException(path, "недопустимая версия схемы: " + schemaVersion);
		}
		throw new StorageFaultException("нет миграции со схемы " + schemaVersion
				+ " на " + SnapshotDto.CURRENT_SCHEMA_VERSION);
	}

	/** A newer format must never be treated as ordinary corruption and downgraded via backup. */
	public static final class UnsupportedSchemaException extends StorageFaultException {
		public UnsupportedSchemaException(String message) {
			super(message);
		}
	}

	/** @return разрешена ли запись файлов этой версии */
	public static boolean isWritable(int schemaVersion) {
		return schemaVersion == SnapshotDto.CURRENT_SCHEMA_VERSION;
	}
}
