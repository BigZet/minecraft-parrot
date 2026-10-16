package io.github.viewerparrots.speech;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.viewerparrots.json.StrictJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Voice identity follows the logical bird incarnation, including entity respawns. */
public final class VoiceAssignments {
	public static final List<String> DEFAULT_VOICES = List.of(
			"rSfuQoQ3FY8SVKeraMAp", "1BfrkuYXmEwp8AWqSLWk", "Pt5YrLNyu6d2s3s4CVMg",
			"bwCXcoVxWNYMlC6Esa8u", "LeAiLsg4dAEgTA94l0il", "Rmv8zCb2IRE895dK1qWB",
			"oQL5kq26ctJzupM0mJot", "pPdl9cQBQq4p6mRkZy2Z", "Z7RrOqZFTyLpIlzCgfsp",
			"ygoBNrnmTEdu5NtDTmAY", "n7Wi4g1bhpw4Bs8HK5ph", "Z9LM7NBnQ8aOZKIXkd5S",
			"6A9D8WSMm4rFsg2DWFeE", "4RloeZf2FRvGiu4uoKOf", "XJ2fW4ybq7HouelYYGcL",
			"dfZGXKiIzjizWtJ0NgPy", "VD1if7jDVYtAKs4P0FIY", "vGQNBgLaiM3EdZtxIiuY",
			"piI8Kku0DcvcL6TTSeQt", "PoHUWWWMHFrA8z7Q88pu", "y3H6zY6KvCH2pEuQjmv8",
			"ecp3DWciuUyW7BYM7II1", "eBvoGh8YGJn1xokno71w", "1cxc5c3E9K6F1wlqOJGV");
	private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private List<String> pool;
	private final Random random;
	private final Map<UUID, String> assignments = new HashMap<>();

	public VoiceAssignments(List<String> pool, Random random) {
		this.pool = List.copyOf(pool);
		this.random = Objects.requireNonNull(random, "random");
		if (this.pool.isEmpty() || this.pool.stream().anyMatch(String::isBlank)
				|| new HashSet<>(this.pool).size() != this.pool.size()) {
			throw new IllegalArgumentException("Voice pool must contain distinct nonempty voices.");
		}
	}

	public synchronized String assign(UUID incarnationId) {
		Objects.requireNonNull(incarnationId, "incarnationId");
		String existing = assignments.get(incarnationId);
		if (existing != null) {
			return existing;
		}
		Map<String, Integer> usage = new HashMap<>();
		for (String voice : assignments.values()) {
			usage.merge(voice, 1, Integer::sum);
		}
		int leastUsage = pool.stream().mapToInt(voice -> usage.getOrDefault(voice, 0)).min().orElseThrow();
		List<String> candidates = new ArrayList<>();
		for (String voice : pool) {
			if (usage.getOrDefault(voice, 0) == leastUsage) {
				candidates.add(voice);
			}
		}
		String selected = candidates.get(random.nextInt(candidates.size()));
		assignments.put(incarnationId, selected);
		return selected;
	}

    public synchronized void updatePool(List<String> enabled) {
        if (enabled.isEmpty() || enabled.stream().anyMatch(v -> !v.matches("[A-Za-z0-9_-]{1,128}"))
                || new HashSet<>(enabled).size() != enabled.size()) throw new IllegalArgumentException("Invalid voice pool");
        pool = List.copyOf(enabled);
    }
    public synchronized void setVoice(UUID incarnation, String voice) {
        if (voice == null || !voice.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid voice ID");
        assignments.put(Objects.requireNonNull(incarnation), voice);
    }
	public synchronized void reconcile(Set<UUID> active) {
		Set<UUID> current = Set.copyOf(active);
		assignments.keySet().retainAll(current);
	}

	public synchronized Map<UUID, String> snapshot() {
		return Map.copyOf(assignments);
	}

	public synchronized void restore(Map<UUID, String> saved) {
		Map<UUID, String> validated = validAssignments(saved);
		assignments.clear();
		assignments.putAll(validated);
	}

	/** Call only from the existing storage worker. Missing sidecars start with no assignments. */
	public static Map<UUID, String> read(Path path) throws IOException {
		if (!Files.exists(path)) {
			return Map.of();
		}
		try {
			JsonObject object = StrictJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
			Map<UUID, String> saved = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				JsonElement value = entry.getValue();
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
					throw new IllegalArgumentException("Voice must be a string.");
				}
				UUID incarnation = UUID.fromString(entry.getKey());
				if (!incarnation.toString().equals(entry.getKey()) || saved.put(incarnation, value.getAsString()) != null) {
					throw new IllegalArgumentException("Invalid or duplicate incarnation identity.");
				}
			}
			return validAssignments(saved);
		} catch (RuntimeException error) {
			throw new IOException("Invalid voice assignments file.");
		}
	}

	/** Writes a small world sidecar; it contains bird IDs and voice IDs only. */
	public static void write(Path path, Map<UUID, String> saved) throws IOException {
		Map<UUID, String> validated = validAssignments(saved);
		JsonObject object = new JsonObject();
		validated.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(entry -> object.addProperty(entry.getKey().toString(), entry.getValue()));
		Path target = path.toAbsolutePath();
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
		try {
			Files.writeString(temporary, JSON.toJson(object) + "\n", StandardCharsets.UTF_8);
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static Map<UUID, String> validAssignments(Map<UUID, String> saved) {
		Map<UUID, String> validated = Map.copyOf(saved);
		if (validated.values().stream().anyMatch(v -> !v.matches("[A-Za-z0-9_-]{1,128}"))) {
			throw new IllegalArgumentException("Voice assignment must not be blank.");
		}
		return validated;
	}
}
