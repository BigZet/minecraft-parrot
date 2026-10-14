package io.github.viewerparrots.behavior.goal;

import io.github.viewerparrots.bootstrap.ServerContext;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.EnumSet;

/** An independent LOOK goal lets a bird watch while sitting, following or wandering. */
public final class LookAtStreamerGoal extends Goal {
    private final ViewerParrotEntity parrot;

    public LookAtStreamerGoal(ViewerParrotEntity parrot) {
        this.parrot = parrot;
        setControls(EnumSet.of(Control.LOOK));
    }

    private ServerPlayerEntity target() {
        return ServerContext.current().flatMap(context -> context.streamerService().onlineTarget())
                .filter(player -> player.getWorld() == parrot.getWorld()).orElse(null);
    }

    @Override public boolean canStart() { return parrot.isViewingStreamer() && !parrot.isSpinning() && target() != null; }
    @Override public boolean shouldContinue() { return canStart(); }
    @Override public boolean shouldRunEveryTick() { return true; }

    @Override public void tick() {
        if (!canStart()) return;
        ServerPlayerEntity streamer = target();
        if (streamer != null) parrot.getLookControl().lookAt(streamer, 10, 10);
    }
}
