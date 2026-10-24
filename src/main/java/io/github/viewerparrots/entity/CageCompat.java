package io.github.viewerparrots.entity;

import io.github.viewerparrots.api.ParrotAction;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Function;

/**
 * Совместимость с модом Parrot Cages. Тот помечает птицу в клетке тегом
 * {@link #CAGED_TAG}; пока мод установлен, команды перемещения не уводят её наружу.
 * Без мода тег игнорируется, поэтому его удаление никогда не запирает птицу.
 * Птица-зритель в клетке — узник со сроком ({@link #JAILED_TAG}); срок и тюремщика знает Parrot Cages,
 * спросить его можно через общий объект {@link #API} без общего кода.
 */
public final class CageCompat {
	public static final String MOD_ID = "parrotcages";
	public static final String CAGED_TAG = "parrotcages.caged";
	public static final String JAILED_TAG = "parrotcages.jailed";
	/** {@code Function<Map<String,Object>,Object>} Parrot Cages: op cage/release/extend/info/list/settings/effect. */
	public static final String API = "parrotcages:jail";
	/** {@code List<Consumer<Map<String,Object>>>}: события jailed/extended/released. */
	public static final String LISTENERS = "parrotcages:jail_listeners";
	/** {@code UnaryOperator<String>} — настройки «Клетка» и узники по протоколу mobtalk-admin. */
	public static final String ADMIN = "parrotcages:admin";
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded(MOD_ID);

	private CageCompat() { }

	public static boolean loaded() { return LOADED; }

	public static boolean isCaged(Entity entity) {
		return LOADED && entity.getCommandTags().contains(CAGED_TAG);
	}

	public static boolean isJailed(Entity entity) {
		return LOADED && entity.getCommandTags().contains(JAILED_TAG);
	}

	/** Команды, которые унесли бы птицу из клетки: к стримеру, на его плечо, на дуэль, к колесу удачи, в новое тело. */
	public static boolean leavesCage(ParrotAction action) {
		return switch (action) { case COME, FOLLOW, FREEFOLLOW, ARM, FIGHT, ACCEPT, ROULETTE, RESPAWN -> true; default -> false; };
	}

	/** Вызов Parrot Cages на потоке сервера; null, если мода нет. */
	@SuppressWarnings("unchecked")
	@Nullable public static Object call(Map<String, Object> query) {
		if (!LOADED) return null;
		Object api = FabricLoader.getInstance().getObjectShare().get(API);
		if (!(api instanceof Function<?, ?> function)) return null;
		return ((Function<Map<String, Object>, Object>) function).apply(query);
	}

	/** Отказ команды узнику: цепь звякнула, перья. */
	public static void refused(Entity parrot) {
		call(Map.of("op", "effect", "name", "refused", "world", parrot.getWorld(), "at", parrot.getPos()));
	}
}
