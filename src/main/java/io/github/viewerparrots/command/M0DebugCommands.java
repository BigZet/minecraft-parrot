package io.github.viewerparrots.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.viewerparrots.domain.ParrotVariant;
import io.github.viewerparrots.entity.ModEntities;
import io.github.viewerparrots.entity.VanillaVariantMapper;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.DimensionArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Временные команды только для приёмки M0: проверить спавн собственной сущности,
 * танец без jukebox и сохранение UUID. Будут заменены полным набором
 * {@code /viewerparrot ...} из раздела 6 ТЗ на этапах M2–M3.
 *
 * <p>Уровень прав 3 (admin) согласно разделу 12.
 */
public final class M0DebugCommands {
	private static final double SEARCH_RADIUS = 32.0;

	private M0DebugCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("viewerparrots-m0")
						.requires(source -> source.hasPermissionLevel(3))
						.then(CommandManager.literal("spawn")
								.then(CommandManager.argument("variant", StringArgumentType.word())
										.executes(ctx -> spawn(ctx.getSource(),
												StringArgumentType.getString(ctx, "variant")))))
						.then(CommandManager.literal("dance")
								.then(CommandManager.literal("on")
										.executes(ctx -> setDance(ctx.getSource(), true)))
								.then(CommandManager.literal("off")
										.executes(ctx -> setDance(ctx.getSource(), false))))
						.then(CommandManager.literal("info")
								.executes(ctx -> info(ctx.getSource())))
						.then(CommandManager.literal("tp")
								.then(CommandManager.argument("dimension", DimensionArgumentType.dimension())
										.executes(ctx -> teleport(ctx.getSource(),
												DimensionArgumentType.getDimensionArgument(ctx, "dimension")))))));
	}

	private static int spawn(ServerCommandSource source, String variantName) {
		ParrotVariant variant;
		try {
			variant = ParrotVariant.valueOf(variantName.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			source.sendError(Text.literal("Неизвестная расцветка: " + variantName));
			return 0;
		}

		ServerWorld world = source.getWorld();
		Vec3d pos = source.getPosition();
		ViewerParrotEntity parrot = ModEntities.VIEWER_PARROT.create(world);
		if (parrot == null) {
			source.sendError(Text.literal("Не удалось создать сущность"));
			return 0;
		}

		parrot.refreshPositionAndAngles(pos.x, pos.y + 1.0, pos.z, source.getRotation().y, 0.0f);
		parrot.setVariant(VanillaVariantMapper.toVanilla(variant));
		parrot.setPersistent();

		// spawnEntity возвращает false, например когда целевой чанк не загружен:
		// молча считать это успехом нельзя.
		if (!world.spawnEntity(parrot)) {
			source.sendError(Text.literal("Сущность не добавлена в мир: "
					+ "возможно, чанк не загружен в " + world.getRegistryKey().getValue()
					+ String.format(Locale.ROOT, " у %.1f %.1f %.1f", pos.x, pos.y, pos.z)));
			return 0;
		}

		source.sendFeedback(() -> Text.literal("Создан viewer_parrot " + variant
				+ ", UUID " + parrot.getUuid()), false);
		return 1;
	}

	private static int setDance(ServerCommandSource source, boolean dancing) {
		List<ViewerParrotEntity> found = nearby(source);
		for (ViewerParrotEntity parrot : found) {
			parrot.setForcedDancing(dancing);
		}
		int count = found.size();
		source.sendFeedback(() -> Text.literal("Флаг танца " + (dancing ? "включён" : "выключен")
				+ " у " + count + " сущностей"), false);
		return count;
	}

	private static int info(ServerCommandSource source) {
		List<ViewerParrotEntity> found = nearby(source);
		if (found.isEmpty()) {
			source.sendFeedback(() -> Text.literal("Рядом нет viewer_parrot"), false);
			return 0;
		}
		for (ViewerParrotEntity parrot : found) {
			source.sendFeedback(() -> Text.literal("UUID " + parrot.getUuid()
					+ ", variant " + parrot.getVariant()
					+ ", forcedDancing " + parrot.isForcedDancing()
					+ ", dimension " + parrot.getWorld().getRegistryKey().getValue()
					+ String.format(Locale.ROOT, ", pos %.3f %.3f %.3f",
							parrot.getX(), parrot.getY(), parrot.getZ())
					+ ", onGround " + parrot.isOnGround()), false);
		}
		return found.size();
	}

	/**
	 * Проверка M0: {@code Entity.teleportTo} в 1.21.1 возвращает {@link Entity} и может
	 * создать НОВЫЙ Java-объект. Команда печатает UUID до и после, тождественность объекта
	 * и судьбу tracked-флага — это нужно зафиксировать прежде, чем на перенос начнёт
	 * опираться loaded index (раздел 5.3 ТЗ).
	 */
	private static int teleport(ServerCommandSource source, ServerWorld destination) {
		List<ViewerParrotEntity> found = nearby(source);
		if (found.isEmpty()) {
			source.sendFeedback(() -> Text.literal("Рядом нет viewer_parrot"), false);
			return 0;
		}

		for (ViewerParrotEntity parrot : found) {
			UUID uuidBefore = parrot.getUuid();
			boolean dancingBefore = parrot.isForcedDancing();

			TeleportTarget target = new TeleportTarget(
					destination, parrot.getPos(), Vec3d.ZERO,
					parrot.getYaw(), parrot.getPitch(), TeleportTarget.NO_OP);
			Entity after = parrot.teleportTo(target);

			if (after == null) {
				source.sendError(Text.literal("Перенос не выполнен для " + uuidBefore));
				continue;
			}

			boolean sameObject = after == parrot;
			boolean sameUuid = after.getUuid().equals(uuidBefore);
			boolean dancingAfter = after instanceof ViewerParrotEntity viewerParrot
					&& viewerParrot.isForcedDancing();

			source.sendFeedback(() -> Text.literal("Перенос в " + destination.getRegistryKey().getValue()
					+ ": UUID " + (sameUuid ? "сохранён" : "ИЗМЕНЁН на " + after.getUuid())
					+ ", объект " + (sameObject ? "тот же" : "НОВЫЙ")
					+ ", флаг танца " + dancingBefore + " -> " + dancingAfter), false);
		}
		return found.size();
	}

	private static List<ViewerParrotEntity> nearby(ServerCommandSource source) {
		Box box = Box.of(source.getPosition(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
		return source.getWorld().getEntitiesByClass(ViewerParrotEntity.class, box, entity -> true);
	}
}
