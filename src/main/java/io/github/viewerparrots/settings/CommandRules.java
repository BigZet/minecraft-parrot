package io.github.viewerparrots.settings;

import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.service.CommandPolicy;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure admission policy. The trusted API and menu use baseline cooldowns without Twitch restrictions. */
public final class CommandRules {
    private static final TwitchRole[] PRIORITY = {TwitchRole.BROADCASTER, TwitchRole.MODERATOR, TwitchRole.VIP, TwitchRole.SUBSCRIBER};
    private CommandRules() { }

    public record Decision(boolean allowed, String reason, int cooldownTicks) { }

    public static Decision evaluate(ModSettings.Profile profile, ParrotAction action, Set<TwitchRole> roles,
                                    String viewerId, boolean twitch) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(action, "action");
        roles = roles == null ? Set.of() : roles;
        var rule = profile.commands().get(action);
        var cooldownRule = profile.commands().get(CommandPolicy.cooldownKey(action));
        int cooldown = cooldownRule == null ? 0 : cooldownRule.cooldownTicks();
        if (!twitch || rule == null) return new Decision(true, "ALLOWED", cooldown);
        for (TwitchRole role : PRIORITY) {
            Integer override = cooldownRule == null ? null : cooldownRule.roleCooldownTicks().get(role);
            if (roles.contains(role) && override != null) { cooldown = override; break; }
        }
        if (matches(profile.moderation().blockedViewers(), viewerId)) return new Decision(false, "VIEWER_BLOCKED", cooldown);
        if (action == ParrotAction.SAY && matches(profile.moderation().mutedViewers(), viewerId)) return new Decision(false, "VIEWER_MUTED", cooldown);
        if (!rule.enabled()) return new Decision(false, "COMMAND_DISABLED", cooldown);
        if (!rule.roles().contains(TwitchRole.EVERYONE) && rule.roles().stream().noneMatch(roles::contains)) {
            return new Decision(false, "ROLE_NOT_ALLOWED", cooldown);
        }
        return new Decision(true, "ALLOWED", cooldown);
    }

    private static boolean matches(Set<String> values, String viewer) {
        if (viewer == null) return false;
        String normalized = viewer.strip().toLowerCase(Locale.ROOT);
        return values.contains(normalized) || (normalized.startsWith("twitch:")
                ? values.contains(normalized.substring(7)) : values.contains("twitch:" + normalized));
    }
}
