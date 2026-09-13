package io.github.viewerparrots.storage;

/**
 * Storage fault (раздел 8.3 ТЗ). Бросается там, где молчаливое продолжение работы было бы
 * опаснее отказа: повреждение середины журнала, разрыв sequence, неверная checksum,
 * недоступное восстановление.
 *
 * <p>Мод при этом переходит в режим с запретом изменений и остановкой AI собственных
 * сущностей. Пустой реестр вместо данных не создаётся никогда.
 */
public class StorageFaultException extends RuntimeException {
	public StorageFaultException(String message) {
		super(message);
	}

	public StorageFaultException(String message, Throwable cause) {
		super(message, cause);
	}
}
