package io.github.viewerparrots.entity;

import io.github.viewerparrots.api.ParrotAction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;

/**
 * Совместимость с модом Parrot Cages. Тот помечает птицу в клетке тегом
 * {@link #CAGED_TAG}; пока мод установлен, команды перемещения не уводят её наружу.
 * Без мода тег игнорируется, поэтому его удаление никогда не запирает птицу.
 */
public final class CageCompat {
	public static final String MOD_ID = "parrotcages";
	public static final String CAGED_TAG = "parrotcages.caged";
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded(MOD_ID);

	private CageCompat() { }

	public static boolean isCaged(Entity entity) {
		return LOADED && entity.getCommandTags().contains(CAGED_TAG);
	}

	/** Команды, которые унесли бы птицу из клетки: к стримеру или на его плечо. */
	public static boolean leavesCage(ParrotAction action) {
		return switch (action) { case COME, FOLLOW, FREEFOLLOW, ARM -> true; default -> false; };
	}
}
