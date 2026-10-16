package io.github.viewerparrots.twitch;

import io.github.viewerparrots.api.ActionParameters;
import io.github.viewerparrots.api.CommandRequest;
import io.github.viewerparrots.api.ParrotAction;
import io.github.viewerparrots.domain.DisplayName;
import io.github.viewerparrots.domain.ViewerId;
import io.github.viewerparrots.settings.TwitchRole;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TwitchCommandInbox {
    private static final Pattern SPEECH_PREFIX = Pattern.compile("^!say\\s",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WORD_SPLIT = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final String channel;
    private final int capacity;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
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
        // Аргумент принимают только !say и !color; остальные команды пишутся ровно одним словом, как раньше.
        String[] words = WORD_SPLIT.split(text, 2);
        String argument = words.length > 1 ? words[1].strip() : "";
        boolean colorCommand = words[0].equalsIgnoreCase("!color");
        boolean nameCommand = words[0].equalsIgnoreCase("!name");
        ParrotAction action = speech ? ParrotAction.SAY : colorCommand ? ParrotAction.COLOR : nameCommand ? ParrotAction.NAME
                : switch (text.toLowerCase(Locale.ROOT)) {
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
            case "!roulette" -> ParrotAction.ROULETTE;
            default -> null;
        };
        if (action == null) return Optional.empty();
        try {
            ActionParameters parameters;
            if (action == ParrotAction.CREATE) parameters = new ActionParameters.Name(DisplayName.of(message.displayName()));
            else if (speech) parameters = ActionParameters.Speech.parse(text.substring(4));
            else if (colorCommand && !argument.isEmpty()) {
                // Неизвестный цвет не превращается в случайный: такое сообщение просто не команда.
                var variant = io.github.viewerparrots.cosmetic.ParrotColors.parse(argument);
                if (variant.isEmpty()) return Optional.empty();
                parameters = new ActionParameters.Color(variant.get());
            } else if (nameCommand) {
                // Пустой !name убирает кличку; недопустимое имя (§, управляющие, >32 символов) — не команда.
                parameters = new ActionParameters.Nickname(argument.isEmpty() ? null : DisplayName.of(argument));
            } else parameters = ActionParameters.None.INSTANCE;
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
        queue.addLast(new Entry(request.get(), message.roles()));
        return true;
    }

    public synchronized Optional<Entry> pollEntry() { return Optional.ofNullable(queue.pollFirst()); }
    public synchronized Optional<CommandRequest> poll() { return pollEntry().map(Entry::request); }
    public synchronized int pending() { return queue.size(); }
    public synchronized void discardPending() { queue.clear(); }

    public record Entry(CommandRequest request, Set<TwitchRole> roles) {
        public Entry {
            Objects.requireNonNull(request, "request");
            roles = Set.copyOf(roles);
        }
    }
}
