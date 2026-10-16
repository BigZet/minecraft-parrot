package io.github.viewerparrots.entity;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.cosmetic.ParrotHats;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.behavior.goal.DanceInPlaceGoal;
import io.github.viewerparrots.behavior.goal.SitStillGoal;
import io.github.viewerparrots.behavior.goal.IdleHoldGoal;
import io.github.viewerparrots.behavior.goal.FreeFlightGoal;
import io.github.viewerparrots.behavior.goal.LookAtStreamerGoal;
import io.github.viewerparrots.behavior.BehaviorPolicy;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.control.ControlPanelService;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.control.BodyControl;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;

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
	private static final TrackedData<Long> SPIN_STARTED_AT =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.LONG);
	private static final TrackedData<String> HAT_ID =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<String> EMOTE_ID =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<Long> EMOTE_STARTED_AT =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.LONG);
	private static final TrackedData<Integer> DUEL_PHASE =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> VIEWING_STREAMER =
			DataTracker.registerData(ViewerParrotEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	/** Собственный namespace в NBT, чтобы не пересекаться с ванильными полями попугая. */
	private static final String MARKER_KEY = ViewerParrotsMod.MOD_ID;
	private static final String MARKER_SCHEMA_VERSION = "schemaVersion";
	private static final String MARKER_VIEWER_ID = "viewerId";
	private static final String MARKER_INCARNATION_ID = "incarnationId";
	private static final String MARKER_GENERATION = "generation";
	private static final String MARKER_HAT_ID = "hatId";
	private static final String MARKER_VIEWING_STREAMER = "viewingStreamer";

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
	private transient boolean boundedManeuverMovement;

	public ViewerParrotEntity(EntityType<? extends ViewerParrotEntity> entityType, World world) {
		super(entityType, world);
		this.lookControl = new ViewingLookControl(this);
	}

	public static DefaultAttributeContainer.Builder createViewerParrotAttributes() {
		return ParrotEntity.createParrotAttributes();
	}

	/** Command movement and vanilla free flight share navigation only in their respective modes. */
	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new DanceInPlaceGoal(this));
		this.goalSelector.add(0, new SitStillGoal(this));
		this.goalSelector.add(1, new SwimGoal(this));
		this.goalSelector.add(2, new IdleHoldGoal(this));
		this.goalSelector.add(2, new LookAtStreamerGoal(this));
		this.goalSelector.add(3, new FreeFlightGoal(this, 1.0));
	}

	@Override protected BodyControl createBodyControl() { return new ViewingBodyControl(this); }

	public boolean isFreeFlightEnabled() {
		if (getWorld().isClient() || isForcedDancing() || isSpinning() || isEmoting() || duelPhase() != 0) return false;
		ServerContext context = ServerContext.current().orElse(null);
		ViewerId id = context == null ? null : context.loadedIndex().findViewerId(getUuid()).orElse(null);
		var runtime = id == null ? null : context.runtimes().get(id);
		return runtime != null && runtime.pendingAction().isEmpty() && !runtime.isFreeFollowCatchingUp()
				&& (runtime.behavior() == BehaviorState.FREE || runtime.behavior() == BehaviorState.FREEFOLLOW);
	}

	/** ParrotEntity.tickMovement's static imitation checks isSilent before its local mob query.
	 * Explicit sounds use World.playSound, so this also blocks every vanilla sound bypass. */
	@Override
	public boolean isSilent() { return true; }

	@Override
	public SoundEvent getAmbientSound() { return null; }

	@Override
	protected EntityNavigation createNavigation(World world) { return new ScheduledBirdNavigation(this, world); }

	/** The scheduler's 3/4-block maneuver limit applies before movement, including path-grid rounding. */
	@Override
	public void move(MovementType type, Vec3d movement) {
		boolean bounded = false;
		if (!getWorld().isClient()) {
			ServerContext context = ServerContext.current().orElse(null);
			if (context != null) {
				ViewerId id = context.loadedIndex().findViewerId(getUuid()).orElse(null);
				var runtime = id == null ? null : context.runtimes().get(id);
				var maneuver = runtime == null ? null : runtime.maneuver().orElse(null);
				if (maneuver != null) {
					bounded = true;
					var clipped = BehaviorPolicy.limitMovement(new BehaviorPolicy.Offset(getX() - maneuver.originX(),
							getY() - maneuver.originY(), getZ() - maneuver.originZ()),
							new BehaviorPolicy.Offset(movement.x, movement.y, movement.z), maneuver.maxDistance());
					Vec3d limited = new Vec3d(clipped.x(), clipped.y(), clipped.z());
					if (limited.squaredDistanceTo(movement) > 0.0000000001) {
						getNavigation().stop();
						setVelocity(Vec3d.ZERO);
					}
					movement = limited;
				}
			}
		}
		boolean previous = this.boundedManeuverMovement;
		this.boundedManeuverMovement = bounded;
		try { super.move(type, movement); } finally { this.boundedManeuverMovement = previous; }
	}

	/** Stepping is an extra collision movement; autonomous flight may only use its bounded planned vector. */
	@Override public float getStepHeight() { return this.boundedManeuverMovement ? 0 : super.getStepHeight(); }

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(FORCED_DANCING, false);
		builder.add(SPIN_STARTED_AT, -1L);
		builder.add(HAT_ID, "");
		builder.add(VIEWING_STREAMER, false);
		builder.add(EMOTE_ID, "");
		builder.add(EMOTE_STARTED_AT, -1L);
		builder.add(DUEL_PHASE, 0);
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
		markerNbt.putString(MARKER_HAT_ID, hatId());
		markerNbt.putBoolean(MARKER_VIEWING_STREAMER, isViewingStreamer());
		nbt.put(MARKER_KEY, markerNbt);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		long started = AuditLog.started();
		super.readCustomDataFromNbt(nbt);
		this.marker = null;
		this.dataTracker.set(HAT_ID, "");
		this.dataTracker.set(VIEWING_STREAMER, false);
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
			this.dataTracker.set(HAT_ID, ParrotHats.normalize(markerNbt.getString(MARKER_HAT_ID)));
			this.dataTracker.set(VIEWING_STREAMER, markerNbt.getBoolean(MARKER_VIEWING_STREAMER));
		} catch (RuntimeException e) {
			// Испорченный маркер не должен ронять загрузку чанка: сущность просто
			// останется неопознанной и будет отвергнута валидатором.
			AuditLog.warn("entity_marker_invalid",
					new AuditLog.Context(null, null, null, null, getUuid(), "LOAD"),
					"REJECTED", "INVALID_MARKER", started, java.util.Map.of(), e);
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

	public String hatId() { return this.dataTracker.get(HAT_ID); }

	public boolean isViewingStreamer() { return this.dataTracker.get(VIEWING_STREAMER); }
	public void setViewingStreamer(boolean viewing) {
		if (getWorld().isClient()) throw new IllegalStateException("Viewing is selected on the server");
		this.dataTracker.set(VIEWING_STREAMER, viewing);
	}

	/** Cosmetic identity is synchronized with the bird and saved in its existing NBT. */
	public void setHatId(String hatId) {
		if (this.getWorld().isClient()) throw new IllegalStateException("Hats are selected on the server");
		if (hatId == null || (!hatId.isEmpty() && !ParrotHats.isKnown(hatId))) {
			throw new IllegalArgumentException("Unknown parrot hat");
		}
		this.dataTracker.set(HAT_ID, hatId);
	}

	/** Only the start is synchronized; the client interpolates from its world clock. */
	public void startSpin() {
		if (this.getWorld().isClient()) throw new IllegalStateException("Spin starts on the server");
		this.dataTracker.set(SPIN_STARTED_AT, this.getWorld().getTime());
	}

	public void stopSpin() {
		if (this.getWorld().isClient()) throw new IllegalStateException("Spin stops on the server");
		this.dataTracker.set(SPIN_STARTED_AT, -1L);
	}

	public boolean isSpinning() {
		long started = this.dataTracker.get(SPIN_STARTED_AT);
		long elapsed = this.getWorld().getTime() - started;
		return started >= 0 && elapsed >= 0 && elapsed < ControlPanelService.settings().active().gameplay().spinDurationTicks();
	}

	/** Ten turns per second; thirty complete turns end in the normal orientation. */
	public float getSpinAngle(float tickDelta) {
		if (!isSpinning()) return 0.0f;
		return (float) ((this.getWorld().getTime() - this.dataTracker.get(SPIN_STARTED_AT) + tickDelta)
				* ControlPanelService.settings().active().gameplay().spinDegreesPerTick());
	}

	// --- Эмоции !emote: как спин, синхронизируется только старт, клиент интерполирует по своим часам ---

	public void startEmote(String emoteId) {
		if (this.getWorld().isClient()) throw new IllegalStateException("Emote starts on the server");
		if (!io.github.viewerparrots.cosmetic.ParrotEmotes.isKnown(emoteId)) throw new IllegalArgumentException("Unknown parrot emote");
		this.dataTracker.set(EMOTE_ID, emoteId);
		this.dataTracker.set(EMOTE_STARTED_AT, this.getWorld().getTime());
	}

	public String emoteId() { return this.dataTracker.get(EMOTE_ID); }

	public boolean isEmoting() {
		long started = this.dataTracker.get(EMOTE_STARTED_AT);
		long elapsed = this.getWorld().getTime() - started;
		return started >= 0 && elapsed >= 0 && elapsed < io.github.viewerparrots.cosmetic.ParrotEmotes.DURATION_TICKS;
	}

	/** 0 в начале эмоции, 1 в конце; вне эмоции — 0. */
	public float getEmoteProgress(float tickDelta) {
		if (!isEmoting()) return 0.0f;
		float elapsed = (float) (this.getWorld().getTime() - this.dataTracker.get(EMOTE_STARTED_AT)) + tickDelta;
		return Math.min(1.0f, elapsed / io.github.viewerparrots.cosmetic.ParrotEmotes.DURATION_TICKS);
	}

	/** Фаза дуэли {@code !fight} (см. {@code DuelService.PHASE_*}); не сохраняется в NBT. */
	public int duelPhase() { return this.dataTracker.get(DUEL_PHASE); }

	public void setDuelPhase(int phase) {
		if (this.getWorld().isClient()) throw new IllegalStateException("Duel phase changes on the server");
		this.dataTracker.set(DUEL_PHASE, phase);
	}

	@Override public int getMaxHeadRotation() {
		return Math.round(ControlPanelService.settings().active().gameplay().headMaxAngle());
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

	/** Viewer ownership comes from Twitch commands, not vanilla feeding or taming. */
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

	/** Shoulder storage removes the physical entity; every entry point must preserve registry authority. */
	@Override
	public boolean mountOnto(ServerPlayerEntity player) {
		return false;
	}

	/** Only the validated chat-command service may transfer this bird to vanilla shoulder storage. */
	public boolean mountOnStreamerShoulder(ServerPlayerEntity player) {
		if (getWorld().isClient()) return false;
		setForcedDancing(false);
		stopSpin();
		getNavigation().stop();
		return super.mountOnto(player);
	}

	@Override
	public boolean isReadyToSitOnPlayer() {
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
