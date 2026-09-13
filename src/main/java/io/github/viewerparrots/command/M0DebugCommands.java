package io.github.viewerparrots.command;

import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Optional;

/**
 * Остаток временных команд приёмки M0: только танец.
 *
 * <p>Команда {@code dance} раздела 6 требует таймера длительности и восстановления
 * прежнего намерения, а это часть очереди действий этапа M3. До тех пор флаг переключается
 * вручную — иначе проверить танец было бы нечем.
 *
 * <p>Прежние подкоманды {@code spawn}, {@code info} и {@code tp} удалены: их заменили
 * {@code /viewerparrot create}, {@code info} и {@code status}. В частности, {@code spawn}
 * создавал сущность без маркера реестра, и теперь такую сущность отвергает
 * {@code GenerationValidator} при следующей загрузке чанка.
 *
 * <p>Уровень прав 3 (admin) согласно разделу 12.
 */
public final class M0DebugCommands {

	private M0DebugCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("viewerparrots-m0")
						.requires(source -> source.hasPermissionLevel(3))
						.then(CommandManager.literal("dance")
								.then(CommandManager.argument("viewerId",
												com.mojang.brigadier.arguments.StringArgumentType.word())
										.then(CommandManager.literal("on")
												.executes(ctx -> setDance(ctx.getSource(),
														com.mojang.brigadier.arguments.StringArgumentType
																.getString(ctx, "viewerId"), true)))
										.then(CommandManager.literal("off")
												.executes(ctx -> setDance(ctx.getSource(),
														com.mojang.brigadier.arguments.StringArgumentType
																.getString(ctx, "viewerId"), false)))))));
	}

	private static int setDance(ServerCommandSource source, String rawViewerId, boolean dancing) {
		Optional<ServerContext> maybeContext = ServerContext.current();
		if (maybeContext.isEmpty()) {
			source.sendError(Text.literal("Мод не инициализирован"));
			return 0;
		}

		ViewerId viewerId;
		try {
			viewerId = ViewerId.of(rawViewerId);
		} catch (IllegalArgumentException e) {
			source.sendError(Text.literal("Некорректный viewerId: " + e.getMessage()));
			return 0;
		}

		Optional<ViewerParrotEntity> parrot = maybeContext.get().loadedIndex().find(viewerId);
		if (parrot.isEmpty()) {
			source.sendError(Text.literal("Попугай " + viewerId + " не загружен"));
			return 0;
		}

		parrot.get().setForcedDancing(dancing);
		source.sendFeedback(() -> Text.literal("Флаг танца у " + viewerId + ": " + dancing), false);
		return 1;
	}
}
