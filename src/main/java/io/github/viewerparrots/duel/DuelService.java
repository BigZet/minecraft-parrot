package io.github.viewerparrots.duel;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.diagnostics.ErrorCode;
import io.github.viewerparrots.domain.BehaviorState;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.domain.ViewerParrotRecord;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Дуэль по согласию: {@code !fight @ник} → вызов, {@code !accept}/{@code !decline} у цели.
 * Без урона и без управления чужой птицей: битва начинается только после согласия,
 * и тогда каждый участник отдал команду своей птице. Всё работает на игровом потоке.
 */
public final class DuelService {
    public static final int CHALLENGE_TICKS = 60 * 20;
    public static final int DUEL_TICKS = 60;
    public static final int WIN_TICKS = 60;
    public static final int LOSS_TICKS = 100;
    public static final double MAX_DISTANCE = 48.0;

    public static final int PHASE_NONE = 0;
    public static final int PHASE_DUELING = 1;
    public static final int PHASE_WON = 2;
    public static final int PHASE_LOST = 3;

    private final ServerContext context;
    private final Random random = new Random();
    /** Ключ — вызывающий. */
    private final Map<ViewerId, Challenge> outgoing = new HashMap<>();
    /** Ключ — цель. */
    private final Map<ViewerId, Challenge> incoming = new HashMap<>();
    private final List<Duel> duels = new ArrayList<>();

    private record Challenge(ViewerId challenger, ViewerId target, long createdTick) { }

    private static final class Duel {
        final ViewerId first;
        final ViewerId second;
        final long startTick;
        long outcomeTick = -1;
        ViewerId winner;
        Duel(ViewerId first, ViewerId second, long startTick) { this.first = first; this.second = second; this.startTick = startTick; }
        boolean involves(ViewerId viewer) { return first.equals(viewer) || second.equals(viewer); }
    }

    public DuelService(ServerContext context) { this.context = Objects.requireNonNull(context, "context"); }

    public int pendingCount() { return outgoing.size() + duels.size(); }

    public boolean involves(ViewerId viewer) {
        for (Duel duel : duels) if (duel.involves(viewer)) return true;
        return false;
    }

    /** {@code "@NumNerd"}, {@code "numnerd"} или {@code "twitch:123"} → запись зрителя с птицей. */
    public Optional<ViewerParrotRecord> resolveOpponent(String rawName) {
        String name = normalizeOpponent(rawName);
        if (name.isEmpty()) return Optional.empty();
        if (name.matches("twitch:[0-9]{1,32}")) return context.registry().find(ViewerId.of(name));
        if (name.matches("[0-9]{1,32}")) return context.registry().find(ViewerId.of("twitch:" + name));
        for (ViewerParrotRecord record : context.registry().allSorted()) {
            if (record.viewer().displayName().value().toLowerCase(Locale.ROOT).equals(name)
                    || record.viewer().nicknameValue().map(n -> n.value().toLowerCase(Locale.ROOT).equals(name)).orElse(false)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    public static String normalizeOpponent(String rawName) {
        if (rawName == null) return "";
        String name = rawName.strip();
        if (name.startsWith("@")) name = name.substring(1);
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** Вызов или, если цель уже вызвала нас, принятие её вызова. */
    public ErrorCode challenge(ViewerParrotRecord challenger, ViewerParrotEntity bird, String opponentName) {
        ViewerId me = challenger.viewerId();
        Optional<ViewerParrotRecord> opponent = resolveOpponent(opponentName);
        if (opponent.isEmpty()) { tell("Дуэль: у «" + safe(opponentName) + "» нет птицы в этом мире."); return ErrorCode.VIEWER_NOT_FOUND; }
        ViewerId other = opponent.get().viewerId();
        if (other.equals(me)) return ErrorCode.INVALID_ARGUMENT;
        Challenge mutual = incoming.get(me);
        if (mutual != null && mutual.challenger().equals(other)) return accept(me);
        if (context.shoulderService().contains(me)) { tell(name(challenger) + ": для дуэли слезьте с плеча (!dearm)."); return ErrorCode.INVALID_STATE; }
        if (outgoing.containsKey(me) || involves(me)) return ErrorCode.OPERATION_PENDING;
        if (incoming.containsKey(other) || outgoing.containsKey(other) || involves(other)) {
            tell("Дуэль: птица " + name(opponent.get()) + " уже вызвана или дерётся.");
            return ErrorCode.INVALID_STATE;
        }
        ViewerParrotEntity target = context.loadedIndex().find(other).orElse(null);
        if (target == null || !target.isAlive()) { tell("Дуэль: птица " + name(opponent.get()) + " не загружена или на плече."); return ErrorCode.TARGET_UNAVAILABLE; }
        Challenge created = new Challenge(me, other, context.clock().currentTick());
        outgoing.put(me, created);
        incoming.put(other, created);
        context.soundService().playScream(bird);
        angryParticles(bird);
        angryParticles(target);
        tell(name(challenger) + " вызывает " + name(opponent.get()) + " на дуэль: !accept или !decline (60 секунд).");
        return null;
    }

    public ErrorCode accept(ViewerId target) {
        Challenge challenge = incoming.get(target);
        if (challenge == null) return ErrorCode.INVALID_STATE;
        ViewerParrotEntity first = loaded(challenge.challenger());
        ViewerParrotEntity second = loaded(challenge.target());
        if (first == null || second == null) {
            remove(challenge);
            tell("Дуэль отменена: одна из птиц не в мире или на плече.");
            return ErrorCode.TARGET_UNAVAILABLE;
        }
        if (first.getWorld() != second.getWorld() || first.squaredDistanceTo(second) > MAX_DISTANCE * MAX_DISTANCE) {
            tell("Дуэль: птицы слишком далеко друг от друга (нужно ближе " + (int) MAX_DISTANCE + " блоков). Подзовите их !come.");
            return ErrorCode.TARGET_UNAVAILABLE;
        }
        remove(challenge);
        Duel duel = new Duel(challenge.challenger(), challenge.target(), context.clock().currentTick());
        duels.add(duel);
        for (ViewerParrotEntity bird : List.of(first, second)) {
            bird.setDuelPhase(PHASE_DUELING);
            hold(bird);
        }
        first.getLookControl().lookAt(second);
        second.getLookControl().lookAt(first);
        tell("Дуэль началась: " + displayName(duel.first) + " против " + displayName(duel.second) + "!");
        return null;
    }

    public ErrorCode decline(ViewerId target) {
        Challenge challenge = incoming.get(target);
        if (challenge == null) return ErrorCode.INVALID_STATE;
        remove(challenge);
        tell(displayName(target) + " отклоняет дуэль с " + displayName(challenge.challenger()) + ".");
        return null;
    }

    /** Собственная команда участника завершает дуэль без победителя; вызовы снимаются. */
    public void abort(ViewerId viewer) {
        Challenge out = outgoing.remove(viewer);
        if (out != null) incoming.remove(out.target());
        Challenge in = incoming.remove(viewer);
        if (in != null) outgoing.remove(in.challenger());
        Iterator<Duel> iterator = duels.iterator();
        while (iterator.hasNext()) {
            Duel duel = iterator.next();
            if (!duel.involves(viewer)) continue;
            iterator.remove();
            release(duel);
            tell("Дуэль " + displayName(duel.first) + " и " + displayName(duel.second) + " прервана.");
        }
    }

    public void cancelAll() {
        outgoing.clear();
        incoming.clear();
        for (Duel duel : List.copyOf(duels)) release(duel);
        duels.clear();
    }

    public void tick() {
        long now = context.clock().currentTick();
        Iterator<Challenge> pending = outgoing.values().iterator();
        while (pending.hasNext()) {
            Challenge challenge = pending.next();
            if (now - challenge.createdTick() >= CHALLENGE_TICKS) {
                pending.remove();
                incoming.remove(challenge.target());
                continue;
            }
            if ((now - challenge.createdTick()) % 20 == 0) {
                ViewerParrotEntity challenger = loaded(challenge.challenger());
                ViewerParrotEntity target = loaded(challenge.target());
                if (challenger != null) angryParticles(challenger);
                if (target != null) angryParticles(target);
            }
        }
        Iterator<Duel> active = duels.iterator();
        while (active.hasNext()) {
            Duel duel = active.next();
            ViewerParrotEntity first = loaded(duel.first);
            ViewerParrotEntity second = loaded(duel.second);
            if (first == null || second == null) {
                active.remove();
                release(duel);
                continue;
            }
            if (duel.outcomeTick < 0) {
                hold(first);
                hold(second);
                if ((now - duel.startTick) % 5 == 0) scuffleParticles(first, second);
                if (now - duel.startTick >= DUEL_TICKS) decide(duel, first, second, now);
                continue;
            }
            long sinceOutcome = now - duel.outcomeTick;
            ViewerParrotEntity winner = duel.winner.equals(duel.first) ? first : second;
            ViewerParrotEntity loser = winner == first ? second : first;
            hold(loser);
            if (sinceOutcome % 10 == 0 && loser.getWorld() instanceof ServerWorld world) {
                world.spawnParticles(ParticleTypes.SMOKE, loser.getX(), loser.getY() + loser.getHeight() + 0.3, loser.getZ(), 3, 0.15, 0.1, 0.15, 0.01);
            }
            if (sinceOutcome == WIN_TICKS) finishWinner(winner);
            if (sinceOutcome >= LOSS_TICKS) {
                active.remove();
                release(duel);
            }
        }
    }

    private void decide(Duel duel, ViewerParrotEntity first, ViewerParrotEntity second, long now) {
        duel.outcomeTick = now;
        duel.winner = random.nextBoolean() ? duel.first : duel.second;
        ViewerParrotEntity winner = duel.winner.equals(duel.first) ? first : second;
        ViewerParrotEntity loser = winner == first ? second : first;
        winner.setDuelPhase(PHASE_WON);
        winner.setForcedDancing(true);
        loser.setDuelPhase(PHASE_LOST);
        loser.setInSittingPose(true);
        if (loser.getWorld() instanceof ServerWorld world) {
            world.spawnParticles(ParticleTypes.POOF, loser.getX(), loser.getY() + 0.3, loser.getZ(), 10, 0.3, 0.2, 0.3, 0.02);
        }
        tell(displayName(duel.winner) + " победил(а) " + displayName(winner == first ? duel.second : duel.first) + "!");
    }

    private void finishWinner(ViewerParrotEntity winner) {
        if (winner.duelPhase() != PHASE_WON) return;
        winner.setDuelPhase(PHASE_NONE);
        if (!isCommandDancing(winner)) winner.setForcedDancing(false);
    }

    private void release(Duel duel) {
        for (ViewerId viewer : List.of(duel.first, duel.second)) {
            ViewerParrotEntity bird = context.loadedIndex().find(viewer).orElse(null);
            if (bird == null) continue;
            int phase = bird.duelPhase();
            bird.setDuelPhase(PHASE_NONE);
            if (phase == PHASE_WON && !isCommandDancing(bird)) bird.setForcedDancing(false);
            if (phase == PHASE_LOST && !isCommandSitting(bird)) bird.setInSittingPose(false);
        }
    }

    private boolean isCommandDancing(ViewerParrotEntity bird) {
        var viewer = context.loadedIndex().findViewerId(bird.getUuid()).orElse(null);
        var runtime = viewer == null ? null : context.runtimes().get(viewer);
        return runtime != null && runtime.behavior() == BehaviorState.DANCING;
    }

    private boolean isCommandSitting(ViewerParrotEntity bird) {
        var viewer = context.loadedIndex().findViewerId(bird.getUuid()).orElse(null);
        var runtime = viewer == null ? null : context.runtimes().get(viewer);
        return runtime != null && runtime.behavior() == BehaviorState.SITTING;
    }

    /** Птица остаётся на месте: навигация стоит, горизонтальная скорость гасится. */
    private static void hold(ViewerParrotEntity bird) {
        bird.getNavigation().stop();
        bird.setVelocity(bird.getVelocity().multiply(0.0, 1.0, 0.0));
    }

    private void scuffleParticles(ViewerParrotEntity first, ViewerParrotEntity second) {
        if (!(first.getWorld() instanceof ServerWorld world)) return;
        double x = (first.getX() + second.getX()) / 2.0;
        double y = (first.getY() + second.getY()) / 2.0 + 0.4;
        double z = (first.getZ() + second.getZ()) / 2.0;
        world.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.FEATHER)), x, y, z, 4, 0.4, 0.3, 0.4, 0.05);
        world.spawnParticles(ParticleTypes.POOF, x, y, z, 2, 0.3, 0.2, 0.3, 0.02);
    }

    private static void angryParticles(ViewerParrotEntity bird) {
        if (bird.getWorld() instanceof ServerWorld world) {
            world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, bird.getX(), bird.getY() + bird.getHeight() + 0.5, bird.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
        }
    }

    private ViewerParrotEntity loaded(ViewerId viewer) {
        ViewerParrotEntity bird = context.loadedIndex().find(viewer).orElse(null);
        return bird != null && bird.isAlive() && !context.shoulderService().contains(viewer) ? bird : null;
    }

    private void remove(Challenge challenge) {
        outgoing.remove(challenge.challenger());
        incoming.remove(challenge.target());
    }

    private String displayName(ViewerId viewer) {
        return context.registry().find(viewer).map(DuelService::name).orElse(viewer.value());
    }

    private static String name(ViewerParrotRecord record) {
        return record.viewer().shownName().value();
    }

    private static String safe(String text) {
        String stripped = text == null ? "" : text.replaceAll("[\\p{Cntrl}§]", "").strip();
        return stripped.length() > 40 ? stripped.substring(0, 40) : stripped;
    }

    /** Сообщения дуэли видит только стример: в Twitch мод не пишет. */
    private void tell(String message) {
        ServerPlayerEntity streamer = context.streamerService().onlineTarget().orElse(null);
        if (streamer != null) streamer.sendMessage(Text.literal("[Дуэль] " + message), false);
        else ViewerParrotsMod.LOGGER.info("Duel: {}", message);
    }
}
