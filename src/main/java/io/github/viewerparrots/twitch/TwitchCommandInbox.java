package io.github.viewerparrots.twitch;

import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ViewerId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TwitchCommandInbox {
    private static final Pattern SPEECH_PREFIX = Pattern.compile("^!say\\s",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private final String channel;
    private final int capacity;
    private final ArrayDeque<CommandRequest> queue = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private boolean accepting;

    public TwitchCommandInbox(String channel, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.channel = channel;
        this.capacity = capacity;
    }

    /** No target ID or arbitrary Minecraft command can come from chat text. */
    public static Optional<CommandRequest> command(TwitchChatMessage message, String channel) {
        if (message == null || !channel.equals(message.channel()) || message.userId() == null
                || !message.userId().matches("[0-9]{1,32}") || message.text() == null) return Optional.empty();
        String text = message.text().strip();
        boolean speech = SPEECH_PREFIX.matcher(text).find();
        ParrotAction action = speech ? ParrotAction.SAY : switch (text.toLowerCase(Locale.ROOT)) {
            case "!parrot" -> ParrotAction.CREATE;
            case "!come" -> ParrotAction.COME;
            case "!follow" -> ParrotAction.FOLLOW;
            case "!free" -> ParrotAction.FREE;
            case "!freefollow" -> ParrotAction.FREEFOLLOW;
            case "!view" -> ParrotAction.VIEW;
            case "!deview" -> ParrotAction.DEVIEW;
            case "!arm" -> ParrotAction.ARM;
            case "!dearm" -> ParrotAction.DEARM;
            case "!sit" -> ParrotAction.SIT;
            case "!dance" -> ParrotAction.DANCE;
            case "!spin" -> ParrotAction.SPIN;
            case "!scream" -> ParrotAction.SCREAM;
            case "!love" -> ParrotAction.LOVE;
            case "!hat" -> ParrotAction.HAT;
            case "!dehat" -> ParrotAction.DEHAT;
            case "!exp" -> ParrotAction.EXP;
            default -> null;
        };
        if (action == null) return Optional.empty();
        try {
            ActionParameters parameters = action == ParrotAction.CREATE
                    ? new ActionParameters.Name(DisplayName.of(message.displayName()))
                    : speech ? new ActionParameters.Speech(text.substring(4)) : ActionParameters.None.INSTANCE;
            UUID commandId = message.messageId() == null || message.messageId().isEmpty() ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes(("twitch-message:" + message.messageId()).getBytes(StandardCharsets.UTF_8));
            return Optional.of(new CommandRequest(commandId, ViewerId.of("twitch:" + message.userId()), action, parameters));
        } catch (IllegalArgumentException invalidInput) {
            return Optional.empty();
        }
    }

    public synchronized void setAccepting(boolean accepting) {
        this.accepting = accepting;
        if (!accepting) { queue.clear(); seen.clear(); }
    }

    public synchronized boolean offer(TwitchChatMessage message) {
        if (!accepting || queue.size() >= capacity) return false;
        Optional<CommandRequest> request = command(message, channel);
        if (request.isEmpty()) return false;
        String id = message.messageId();
        if (id != null && !id.isEmpty()) {
            if (!seen.add(id)) return false;
            if (seen.size() > 512) seen.remove(seen.iterator().next());
        }
        queue.addLast(request.get());
        return true;
    }

    public synchronized Optional<CommandRequest> poll() { return Optional.ofNullable(queue.pollFirst()); }
    public synchronized int pending() { return queue.size(); }
    public synchronized void discardPending() { queue.clear(); }
}
