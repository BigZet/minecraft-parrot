package io.github.viewerparrots.service;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.ParrotAction;

import java.util.Objects;

/**
 * Проверка прав по разделу 12 ТЗ.
 *
 * <p>Brigadier фильтрует узлы команд по правам, но сервис обязан проверить их повторно:
 * внутренний Java API и сторонние моды в том же процессе не являются security sandbox,
 * и фильтрация дерева команд их не касается.
 *
 * <p>Уровень 0 по умолчанию не имеет доступа ни к чему. UUID стримера операторских прав
 * не даёт: это две независимые вещи.
 */
public final class PermissionService {
	private final int controlLevel;
	private final int adminLevel;

	public PermissionService(int controlLevel, int adminLevel) {
		if (controlLevel < 0 || controlLevel > 4) {
			throw new IllegalArgumentException("controlLevel вне диапазона 0..4: " + controlLevel);
		}
		if (adminLevel < 0 || adminLevel > 4) {
			throw new IllegalArgumentException("adminLevel вне диапазона 0..4: " + adminLevel);
		}
		if (adminLevel < controlLevel) {
			throw new IllegalArgumentException(
					"adminLevel (" + adminLevel + ") не может быть ниже controlLevel (" + controlLevel + ")");
		}
		this.controlLevel = controlLevel;
		this.adminLevel = adminLevel;
	}

	public int requiredLevel(ParrotAction action) {
		return action.requiresAdmin() ? this.adminLevel : this.controlLevel;
	}

	public boolean isAllowed(ActorContext actor, ParrotAction action) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(action, "action");
		int required = requiredLevel(action);
		// Уровень 0 не получает доступа даже если controlLevel настроен в 0:
		// иначе любой игрок управлял бы попугаями.
		if (actor.permissionLevel() == 0) {
			return false;
		}
		return actor.permissionLevel() >= required;
	}

	public int controlLevel() {
		return this.controlLevel;
	}

	public int adminLevel() {
		return this.adminLevel;
	}
}
