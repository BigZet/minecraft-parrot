package io.github.viewerparrots.entity;

import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.domain.WorldLocation;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственная точка, где мод создаёт, настраивает и удаляет собственные сущности
 * (разделы 9.1 и 9.5 ТЗ). Всё это выполняется на server thread.
 */
public final class ParrotEntityController {
	public static final String NAME_PLACEHOLDER = "{viewer_name}";

	private final ViewerParrotsConfig config;

	public ParrotEntityController(ViewerParrotsConfig config) {
		this.config = Objects.requireNonNull(config, "config");
	}

	/**
	 * Создаёт сущность с заранее выбранным UUID и маркером, но с выключенным AI.
	 *
	 * <p>AI выключен намеренно: до durable COMMIT новая сущность не должна действовать
	 * и принимать команды (раздел 8.2 ТЗ). Включает его {@link #authorize}.
	 *
	 * @return созданная сущность, либо пусто если мир отказался её принять
	 */
	public Optional<ViewerParrotEntity> spawnPrepared(ServerWorld world, Vec3d position, float yaw,
			ViewerParrotRecord record, UUID entityUuid) {
		ViewerParrotEntity parrot = ModEntities.VIEWER_PARROT.create(world);
		if (parrot == null) {
			return Optional.empty();
		}

		parrot.setUuid(entityUuid);
		parrot.refreshPositionAndAngles(position.x, position.y, position.z, yaw, 0.0f);
		parrot.setVariant(VanillaVariantMapper.toVanilla(record.variant()));
		parrot.setMarker(ParrotMarker.of(record));
		parrot.setPersistent();
		parrot.setAiDisabled(true);
		applyName(parrot, record);

		if (!world.spawnEntity(parrot)) {
			// Чанк мог оказаться недоступен между проверкой позиции и спавном.
			parrot.discard();
			return Optional.empty();
		}
		return Optional.of(parrot);
	}

	/** Включает поведение после durable COMMIT. */
	public void authorize(ViewerParrotEntity parrot) {
		parrot.setAiDisabled(false);
	}

	/**
	 * Имя строится подстановкой в шаблон и создаётся как {@code Text.literal}:
	 * пользовательская строка не должна трактоваться как JSON text, selector или команда
	 * (раздел 4.1 ТЗ).
	 */
	public void applyName(ViewerParrotEntity parrot, ViewerParrotRecord record) {
		String name = this.config.nameFormat()
				.replace(NAME_PLACEHOLDER, record.viewer().displayName().value());
		parrot.setCustomName(Text.literal(name));
		parrot.setCustomNameVisible(this.config.nameVisible());
	}

	/**
	 * Удаление собственной сущности без дропа и без вызова обработчика смерти.
	 *
	 * <p>{@code discard} вместо {@code kill}: административное удаление и отвергнутая
	 * копия — не смерть, и записывать её как смерть нельзя (раздел 9.2 ТЗ).
	 */
	public void discard(ViewerParrotEntity parrot) {
		parrot.remove(Entity.RemovalReason.DISCARDED);
	}

	public static WorldLocation locationOf(ViewerParrotEntity parrot) {
		return new WorldLocation(
				parrot.getWorld().getRegistryKey().getValue().toString(),
				parrot.getX(), parrot.getY(), parrot.getZ());
	}
}
