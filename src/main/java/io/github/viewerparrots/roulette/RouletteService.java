package io.github.viewerparrots.roulette;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.api.CommandResult;
import io.github.viewerparrots.api.CommandStatus;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import io.github.viewerparrots.network.RoulettePayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** All admission, random selection and reward creation run on the existing game thread. */
public final class RouletteService {
    private static final int REEL_LENGTH = 48;
    private static final int PRIZE_INDEX = 42;
    private final ServerContext context;
    private final RouletteQueue<Request> queue = new RouletteQueue<>();
    private final Random random = new Random();
    private RouletteSettings settings = RouletteSettings.defaults();
    private boolean ready;
    private boolean closed;
    private Active active;

    private record Request(UUID id, ViewerParrotRecord identity, UUID birdUuid, UUID targetUuid,
                           RouletteSettings settings, WeightedLoot.Selection prize, List<String> reel) { }
    private static final class Active {
        final Request request;
        final long startTick;
        boolean rewarded;
        long lastClick = -10;
        int lastSlot = -1;
        Active(Request request, long startTick) { this.request = request; this.startTick = startTick; }
    }

    public RouletteService(ServerContext context) {
        this.context = context;

    }

    public Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(RouletteSettingsLoader.FILE_NAME);
    }
    public RouletteSettingsLoader configReader() {
        Set<String> ids = Registries.ITEM.getIds().stream().map(Object::toString).collect(Collectors.toUnmodifiableSet());
        return new RouletteSettingsLoader(ids::contains);
    }
    public void installSettings(RouletteSettings loaded) { settings = loaded; ready = true; }
    public int pendingCount() { return queue.waitingCount() + (active == null ? 0 : 1); }
    public int cooldownTicks() { return settings.cooldownTicks(); }

    /** A rejected/full/duplicate request consumes neither the viewer cooldown nor the global budget. */
    public ErrorCode enqueue(UUID commandId, ViewerParrotRecord identity, ViewerParrotEntity bird) {
        if (!ready) return ErrorCode.CONFIG_INVALID;
        if (closed || !context.acceptingCommands()) return ErrorCode.INVALID_STATE;
        var player = context.streamerService().onlineTarget().orElse(null);
        if (player == null || player.getWorld() != bird.getWorld()) return ErrorCode.TARGET_UNAVAILABLE;
        String key = identity.viewerId().value();
        if (queue.contains(key)) return ErrorCode.OPERATION_PENDING;
        if (queue.waitingCount() >= settings.queueCapacity()) return ErrorCode.SERVER_BUSY;
        WeightedLoot.Selection selected = WeightedLoot.pick(settings, random);
        List<String> reel = new ArrayList<>(REEL_LENGTH);
        for (int i = 0; i < REEL_LENGTH; i++) reel.add(WeightedLoot.pick(settings, random).prize().itemId());
        reel.set(PRIZE_INDEX, selected.prize().itemId());
        Request request = new Request(commandId, identity, bird.getUuid(), player.getUuid(),
                settings, selected, List.copyOf(reel));
        if (!queue.offer(key, request, settings.queueCapacity())) return ErrorCode.SERVER_BUSY;
        return null;
    }

    public void tick() {
        if (closed || !context.allowsMutations()) return;
        if (active == null) startNext();
        if (active == null) return;
        Request request = active.request;
        ViewerParrotEntity bird = resolve(request);
        ServerPlayerEntity player = context.streamerService().onlineTarget().orElse(null);
        if (bird == null || player == null || !player.getUuid().equals(request.targetUuid())
                || bird.getWorld() != player.getWorld()) {
            cancel(request.identity().viewerId());
            return;
        }
        long elapsed = context.clock().currentTick() - active.startTick;
        if (elapsed < request.settings().durationTicks()) {
            double t = Math.max(0.0, (double) elapsed / request.settings().durationTicks());
            int slot = (int) (2 + (PRIZE_INDEX - 2) * (1.0 - Math.pow(1.0 - t, 3)));
            if (slot != active.lastSlot && elapsed - active.lastClick >= 5) {
                bird.getWorld().playSound(null, bird.getX(), bird.getY(), bird.getZ(),
                        SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.NEUTRAL, 0.18f, 1.25f);
                active.lastSlot = slot;
                active.lastClick = elapsed;
            }
        } else if (!active.rewarded) {
            // Mark before emitting particles/messages: the next tick can never pay the same spin twice.
            active.rewarded = true;
            award(player, bird, request);
        }
        if (elapsed >= request.settings().durationTicks() + request.settings().resultTicks()) {
            active = null;
            queue.complete();
        }
    }

    private void startNext() {
        for (var next = queue.startNext(); next.isPresent(); next = queue.startNext()) {
            Request request = next.get().value();
            ViewerParrotEntity bird = resolve(request);
            ServerPlayerEntity player = context.streamerService().onlineTarget().orElse(null);
            if (bird == null || player == null || !player.getUuid().equals(request.targetUuid())
                    || bird.getWorld() != player.getWorld()) {
                finishCancelled(request);
                queue.complete();
                continue;
            }
            active = new Active(request, context.clock().currentTick());
            RoulettePayload.send(player, new RoulettePayload(request.id(), request.birdUuid(),
                    request.identity().viewer().displayName().value(), bird.getWorld().getTime(),
                    request.settings().durationTicks(), request.settings().resultTicks(), request.reel(),
                    PRIZE_INDEX, request.prize().prize().count(), request.prize().color(), request.prize().jackpot()));
            return;
        }
    }

    private ViewerParrotEntity resolve(Request request) {
        var current = context.registry().find(request.identity().viewerId()).orElse(null);
        if (current == null || !current.incarnationId().equals(request.identity().incarnationId())
                || current.generation() != request.identity().generation()) return null;
        ViewerParrotEntity bird = context.loadedIndex().find(current.viewerId())
                .or(() -> context.shoulderService().find(current.viewerId())).orElse(null);
        return bird != null && bird.isAlive() && bird.getUuid().equals(request.birdUuid()) ? bird : null;
    }

    private void award(ServerPlayerEntity player, ViewerParrotEntity bird, Request request) {
        var prize = request.prize().prize();
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of(prize.itemId())), prize.count());
        var world = player.getServerWorld();
        ItemEntity drop = new ItemEntity(world, bird.getX(), bird.getY(), bird.getZ(), stack);
        // Keep the drop within the bird's height, just like !exp, rather than tossing it in an arc.
        drop.setPosition(bird.getX(), bird.getY() + Math.max(0.0, bird.getHeight() - drop.getHeight()), bird.getZ());
        drop.setVelocity(0.0, -0.05, 0.0);
        drop.setPickupDelay(15);
        if (!world.spawnEntity(drop)) {
            ViewerParrotsMod.LOGGER.warn("Roulette prize could not be spawned ({})", request.id());
            context.commandService().finishRoulette(request.identity().viewerId(),
                    CommandResult.failed(request.id(), ErrorCode.INTERNAL_ERROR, "prize could not be spawned"));
            return;
        }
        if (bird.getWorld() instanceof ServerWorld birdWorld) {
            birdWorld.spawnParticles(request.prize().jackpot() ? ParticleTypes.FIREWORK : ParticleTypes.HAPPY_VILLAGER,
                    bird.getX(), bird.getY() + bird.getHeight() + 0.7, bird.getZ(),
                    request.prize().jackpot() ? 24 : 8, 0.35, 0.15, 0.35, 0.025);
        }
        world.playSound(null, bird.getX(), bird.getY(), bird.getZ(),
                request.prize().jackpot() ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.NEUTRAL, 0.6f, request.prize().jackpot() ? 0.8f : 1.0f);
        player.sendMessage(Text.translatable("viewerparrots.roulette.ok",
                request.identity().viewer().displayName().value(), stack.getName(), prize.count()), false);
        context.commandService().finishRoulette(request.identity().viewerId(), CommandResult.succeeded(request.id(),
                "viewerparrots.action.ok", CommandResult.details("viewerId", request.identity().viewerId().value(),
                        "action", "ROULETTE")));
    }

    public void cancel(ViewerId viewer) {
        if (active != null && active.request.identity().viewerId().equals(viewer)) {
            var player = context.server().getPlayerManager().getPlayer(active.request.targetUuid());
            if (player != null) RoulettePayload.send(player, RoulettePayload.cancel(active.request.id(), active.request.birdUuid()));
            active = null;
        }
        queue.remove(viewer.value()).ifPresent(entry -> finishCancelled(entry.value()));
    }
    private void finishCancelled(Request request) {
        context.commandService().finishRoulette(request.identity().viewerId(), new CommandResult(request.id(),
                CommandStatus.CANCELLED, ErrorCode.INVALID_STATE, "viewerparrots.action.cancelled",
                CommandResult.details("viewerId", request.identity().viewerId().value(), "action", "ROULETTE",
                        "reason", "parrot or streamer unavailable"), 0));
    }
    public void close() {
        if (active != null) cancel(active.request.identity().viewerId());
        closed = true;
        active = null;
        queue.clear().forEach(entry -> finishCancelled(entry.value()));
    }
}
