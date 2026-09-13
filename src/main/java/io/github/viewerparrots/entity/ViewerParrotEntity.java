package io.github.viewerparrots.entity;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.behavior.goal.DanceInPlaceGoal;
import io.github.viewerparrots.domain.ViewerId;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Попугай-зритель. Наследует полёт, навигацию, модель и текстуры ванильного попугая,
 * но управляется только сервером через контроллер.
 *
 * <p>Танец не использует ванильный jukebox-механизм: ванильное поле {@code songPlaying}
 * сбрасывается в {@code tick()}, если рядом нет проигрывающего jukebox. Вместо него здесь
 * заведён синхронизируемый (tracked) флаг {@code FORCED_DANCING}, а {@link #isSongPlaying()}
 * отвечает исключительно по нему. Ванильный {@code ParrotEntityModel.getPose()} вызывает
 * {@code isSongPlaying()} виртуально, поэтому поза PARTY выбирается без mixin и без
 * access widener.
 */
public class ViewerParrotEntity extends ParrotEntity {
	private static final TrackedData<Boolean> FORCED_DANCING =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	/** Собственный namespace в NBT, чтобы не пересекаться с ванильными полями попугая. */
	private static final String MARKER_KEY = ViewerParrotsMod.MOD_ID;
	private static final String MARKER_SCHEMA_VERSION = "schemaVersion";
	private static final String MARKER_VIEWER_ID = "viewerId";
	private static final String MARKER_INCARNATION_ID = "incarnationId";
	private static final String MARKER_GENERATION = "generation";

	/** {@code null} у сущности без маркера: она не является попугаем зрителя. */
	private ParrotMarker marker;

	/**
	 * Сущность создана контроллером и ждёт durable COMMIT.
	 *
	 * <p>Поле намеренно не сохраняется в NBT и живёт только в памяти. Событие загрузки
	 * сущности срабатывает внутри {@code spawnEntity}, то есть раньше, чем запись попадает
	 * в реестр; без этого признака валидатор отверг бы только что созданного попугая.
	 * А после рестарта признака нет — и подготовленная, но не подтверждённая сущность
	 * будет отвергнута, как и требует откат незавершённого PREPARE.
	 */
	private transient boolean awaitingAuthorization;

	public ViewerParrotEntity(EntityType<? extends ViewerParrotEntity> entityType, World world) {
		super(entityType, world);
	}

	public static DefaultAttributeContainer.Builder createViewerParrotAttributes() {
		return ParrotEntity.createParrotAttributes();
	}

	/**
	 * Ванильный набор goals пока сохраняется (полёт и навигация переиспользуются),
	 * поверх него добавляется удержание места во время танца. Собственный полный набор
	 * goals появится на этапах M3–M4 вместе с follow/come/sit.
	 */
	@Override
	protected void initGoals() {
		super.initGoals();
		this.goalSelector.add(0, new DanceInPlaceGoal(this));
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(FORCED_DANCING, false);
	}

	// --- Маркер реестра (раздел 4.3 ТЗ) --------------------------------------

	/**
	 * Маркер сохраняется в собственном namespace, чтобы не пересекаться с ванильными
	 * полями попугая и не потеряться при их изменении между версиями игры.
	 */
	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		if (this.marker == null) {
			return;
		}
		NbtCompound markerNbt = new NbtCompound();
		markerNbt.putInt(MARKER_SCHEMA_VERSION, this.marker.schemaVersion());
		markerNbt.putString(MARKER_VIEWER_ID, this.marker.viewerId().value());
		markerNbt.putUuid(MARKER_INCARNATION_ID, this.marker.incarnationId());
		markerNbt.putLong(MARKER_GENERATION, this.marker.generation());
		nbt.put(MARKER_KEY, markerNbt);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		this.marker = null;
		if (!nbt.contains(MARKER_KEY, NbtElement.COMPOUND_TYPE)) {
			return;
		}
		NbtCompound markerNbt = nbt.getCompound(MARKER_KEY);
		try {
			this.marker = new ParrotMarker(
					markerNbt.getInt(MARKER_SCHEMA_VERSION),
					ViewerId.of(markerNbt.getString(MARKER_VIEWER_ID)),
					markerNbt.getUuid(MARKER_INCARNATION_ID),
					markerNbt.getLong(MARKER_GENERATION));
		} catch (RuntimeException e) {
			// Испорченный маркер не должен ронять загрузку чанка: сущность просто
			// останется неопознанной и будет отвергнута валидатором.
			ViewerParrotsMod.LOGGER.warn("Повреждённый маркер у сущности {}: {}", getUuid(), e.toString());
			this.marker = null;
		}
	}

	public Optional<ParrotMarker> marker() {
		return Optional.ofNullable(this.marker);
	}

	/** Проставляется контроллером при создании и замене экземпляра. */
	public void setMarker(ParrotMarker marker) {
		this.marker = marker;
	}

	public boolean isAwaitingAuthorization() {
		return this.awaitingAuthorization;
	}

	public void setAwaitingAuthorization(boolean awaiting) {
		this.awaitingAuthorization = awaiting;
	}

	/** Сервер — единственный источник истины для флага танца. */
	public void setForcedDancing(boolean dancing) {
		if (this.getWorld().isClient()) {
			throw new IllegalStateException("Флаг танца выставляется только на сервере");
		}
		this.dataTracker.set(FORCED_DANCING, dancing);
	}

	public boolean isForcedDancing() {
		return this.dataTracker.get(FORCED_DANCING);
	}

	/**
	 * Сознательно игнорирует ванильное состояние jukebox: близость музыки не должна
	 * запускать танец в обход команды.
	 */
	@Override
	public boolean isSongPlaying() {
		return this.isForcedDancing();
	}

	// --- Ограничения MVP (раздел 3.3 ТЗ) -------------------------------------

	/** Ни приручения, ни посадки на плечо, ни смерти от печенья, ни кормления. */
	@Override
	public ActionResult interactMob(PlayerEntity player, Hand hand) {
		return ActionResult.PASS;
	}

	@Override
	public boolean isBreedingItem(ItemStack stack) {
		return false;
	}

	@Override
	public PassiveEntity createChild(ServerWorld world, PassiveEntity other) {
		return null;
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	/** Межпространственный перенос выполняет только контроллер, не портал. */
	@Override
	public boolean canUsePortals(boolean allowVehicles) {
		return false;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	protected int getXpToDrop() {
		return 0;
	}

	@Override
	protected void dropLoot(DamageSource damageSource, boolean causedByPlayer) {
		// Дроп у собственной сущности отключён.
	}
}
