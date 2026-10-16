package io.github.viewerparrots.settings;

import io.github.viewerparrots.api.ParrotAction;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandRulesTest {
    @Test void roleCooldownPriorityIsBroadcasterModeratorVipSubscriberThenBase() {
        var base = ModSettings.Profile.defaults();
        var rule = new ModSettings.CommandRule(true, Set.of(TwitchRole.EVERYONE), 100,
                Map.of(TwitchRole.SUBSCRIBER, 80, TwitchRole.VIP, 60,
                        TwitchRole.MODERATOR, 40, TwitchRole.BROADCASTER, 20));
        var profile = withRule(base, ParrotAction.COME, rule);
        assertEquals(100, decision(profile, ParrotAction.COME, Set.of()).cooldownTicks());
        assertEquals(80, decision(profile, ParrotAction.COME, Set.of(TwitchRole.SUBSCRIBER)).cooldownTicks());
        assertEquals(60, decision(profile, ParrotAction.COME, Set.of(TwitchRole.SUBSCRIBER, TwitchRole.VIP)).cooldownTicks());
        assertEquals(40, decision(profile, ParrotAction.COME, Set.of(TwitchRole.VIP, TwitchRole.MODERATOR)).cooldownTicks());
        assertEquals(20, decision(profile, ParrotAction.COME, Set.of(TwitchRole.BROADCASTER, TwitchRole.MODERATOR)).cooldownTicks());
    }

    @Test void pairedCommandsShareCanonicalCooldownButKeepTheirOwnAvailability() {
        var base = ModSettings.Profile.defaults();
        for (ParrotAction[] pair : new ParrotAction[][] {
                {ParrotAction.HAT, ParrotAction.DEHAT}, {ParrotAction.VIEW, ParrotAction.DEVIEW}, {ParrotAction.ARM, ParrotAction.DEARM}}) {
            var profile = withRule(base, pair[0], new ModSettings.CommandRule(true, Set.of(TwitchRole.EVERYONE),
                    73, Map.of(TwitchRole.SUBSCRIBER, 17)));
            assertEquals(73, decision(profile, pair[1], Set.of()).cooldownTicks());
            assertEquals(17, decision(profile, pair[1], Set.of(TwitchRole.SUBSCRIBER)).cooldownTicks());
            profile = withRule(profile, pair[1], new ModSettings.CommandRule(false, Set.of(TwitchRole.EVERYONE), 5, Map.of()));
            assertFalse(decision(profile, pair[1], Set.of()).allowed());
            assertTrue(decision(profile, pair[0], Set.of()).allowed());
        }
    }

    @Test void disabledRoleBlockedAndMutedRulesOnlyRestrictTwitch() {
        var base = ModSettings.Profile.defaults();
        var disabled = withRule(base, ParrotAction.SAY,
                new ModSettings.CommandRule(false, Set.of(TwitchRole.SUBSCRIBER), 35, Map.of()));
        assertFalse(decision(disabled, ParrotAction.SAY, Set.of(TwitchRole.SUBSCRIBER)).allowed());
        assertTrue(CommandRules.evaluate(disabled, ParrotAction.SAY, Set.of(), "twitch:42", false).allowed());
        var moderation = new ModSettings.Moderation(256, 4, Set.of("twitch:42"), Set.of("43"));
        var profile = new ModSettings.Profile(base.name(), base.general(), base.commands(), base.speech(),
                base.roulette(), base.gameplay(), moderation);
        assertFalse(CommandRules.evaluate(profile, ParrotAction.COME, Set.of(), "twitch:42", true).allowed());
        assertTrue(CommandRules.evaluate(profile, ParrotAction.COME, Set.of(), "twitch:42", false).allowed());
        assertFalse(CommandRules.evaluate(profile, ParrotAction.SAY, Set.of(), "twitch:43", true).allowed());
        assertTrue(CommandRules.evaluate(profile, ParrotAction.SCREAM, Set.of(), "twitch:43", true).allowed());
    }

    @Test void everyoneAndExplicitRolesHavePredictableAdmission() {
        var base = ModSettings.Profile.defaults();
        assertTrue(decision(base, ParrotAction.COME, Set.of()).allowed());
        var profile = withRule(base, ParrotAction.COME,
                new ModSettings.CommandRule(true, Set.of(TwitchRole.SUBSCRIBER, TwitchRole.VIP), 100, Map.of()));
        assertFalse(decision(profile, ParrotAction.COME, Set.of()).allowed());
        assertTrue(decision(profile, ParrotAction.COME, Set.of(TwitchRole.VIP)).allowed());
    }

    private static CommandRules.Decision decision(ModSettings.Profile profile, ParrotAction action, Set<TwitchRole> roles) {
        return CommandRules.evaluate(profile, action, roles, "twitch:1", true);
    }

    private static ModSettings.Profile withRule(ModSettings.Profile base, ParrotAction action, ModSettings.CommandRule rule) {
        var commands = new EnumMap<ParrotAction, ModSettings.CommandRule>(ParrotAction.class);
        commands.putAll(base.commands());
        commands.put(action, rule);
        return new ModSettings.Profile(base.name(), base.general(), commands, base.speech(), base.roulette(), base.gameplay(), base.moderation());
    }
}
