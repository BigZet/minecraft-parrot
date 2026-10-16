package io.github.viewerparrots.settings;

import io.github.viewerparrots.api.ActorContext;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.control.ControlPanelService;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.diagnostics.ErrorCode;

/** One policy at real service boundaries, including local admin tests and direct Java calls. */
public final class CommandAccess {
    private CommandAccess() { }
    public static CommandRules.Decision evaluate(ActorContext actor, ParrotAction action, ViewerId viewer) {
        var profile = ControlPanelService.settings().active();
        if (actor.isTwitch() && (viewer == null || !viewer.value().equals(actor.sourceKey())
                || !profile.commands().containsKey(action))) return new CommandRules.Decision(false, "PERMISSION_DENIED", 0);
        return CommandRules.evaluate(profile, action, actor.twitchRoles(), viewer == null ? "" : viewer.value(), actor.isTwitch());
    }
    public static ErrorCode error(CommandRules.Decision decision) { return ErrorCode.valueOf(decision.reason()); }
}
