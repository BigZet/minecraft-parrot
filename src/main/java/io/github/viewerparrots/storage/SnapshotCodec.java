package io.github.viewerparrots.storage;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;
import io.github.viewerparrots.storage.dto.SnapshotDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Чтение и запись {@code registry.json} по схеме v1 (раздел 8.1 ТЗ). */
public final class SnapshotCodec {

	private SnapshotCodec() {
	}

	public static SnapshotDto read(String json) {
		JsonView root = JsonView.ofRoot(StrictJson.parseObject(json));
		root.requireKnownFields("schemaVersion", "checkpointSequence", "streamerPlayerUuid",
				"parrots", "tombstones");

		int schemaVersion = root.requireInt("schemaVersion");
		StorageMigrator.requireReadable(schemaVersion, "$.schemaVersion");

		long checkpointSequence = root.requireLong("checkpointSequence");
		if (checkpointSequence < 0) {
			throw new JsonSchemaException("$.checkpointSequence", "не может быть отрицательным");
		}

		UUID streamer = root.optionalUuid("streamerPlayerUuid").orElse(null);

		Map<String, SnapshotDto.ParrotRecordDto> parrots = new LinkedHashMap<>();
		JsonView parrotsView = root.requireObject("parrots");
		for (String viewerId : parrotsView.fieldNames()) {
			parrots.put(viewerId, readRecord(parrotsView.requireObject(viewerId)));
		}

		List<SnapshotDto.TombstoneDto> tombstones = new ArrayList<>();
		for (JsonView view : root.requireObjectArray("tombstones")) {
			tombstones.add(readTombstone(view));
		}

		return new SnapshotDto(schemaVersion, checkpointSequence, streamer, parrots, tombstones);
	}

	static SnapshotDto.ParrotRecordDto readRecord(JsonView view) {
		view.requireKnownFields("viewer", "incarnationId", "generation", "entityUuid", "variant",
				"trait", "desiredBehavior", "physicalStatus", "lastKnownLocation", "lastSpawnedAt",
				"updatedAt", "revision");

		JsonView viewerView = view.requireObject("viewer");
		viewerView.requireKnownFields("viewerId", "displayName", "createdAt");
		SnapshotDto.ViewerDto viewer = new SnapshotDto.ViewerDto(
				viewerView.requireString("viewerId"),
				viewerView.requireString("displayName"),
				viewerView.requireInstant("createdAt"));

		SnapshotDto.LocationDto location = null;
		if (view.has("lastKnownLocation")) {
			JsonView locationView = view.requireObject("lastKnownLocation");
			locationView.requireKnownFields("dimension", "x", "y", "z");
			location = new SnapshotDto.LocationDto(
					locationView.requireString("dimension"),
					locationView.requireDouble("x"),
					locationView.requireDouble("y"),
					locationView.requireDouble("z"));
		}

		return new SnapshotDto.ParrotRecordDto(
				viewer,
				view.requireUuid("incarnationId"),
				view.requireLong("generation"),
				view.optionalUuid("entityUuid").orElse(null),
				view.requireString("variant"),
				view.requireString("trait"),
				view.requireString("desiredBehavior"),
				view.requireString("physicalStatus"),
				location,
				view.optionalInstant("lastSpawnedAt").orElse(null),
				view.requireInstant("updatedAt"),
				view.requireLong("revision"));
	}

	static SnapshotDto.TombstoneDto readTombstone(JsonView view) {
		view.requireKnownFields("viewerId", "incarnationId", "lastGeneration", "removedAt");
		return new SnapshotDto.TombstoneDto(
				view.requireString("viewerId"),
				view.requireUuid("incarnationId"),
				view.requireLong("lastGeneration"),
				view.requireInstant("removedAt"));
	}

	public static String write(SnapshotDto snapshot) {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
				.toJson(toJson(snapshot));
	}

	public static JsonObject toJson(SnapshotDto snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", snapshot.schemaVersion());
		root.addProperty("checkpointSequence", snapshot.checkpointSequence());
		if (snapshot.streamerPlayerUuid() != null) {
			root.addProperty("streamerPlayerUuid", snapshot.streamerPlayerUuid().toString());
		}

		JsonObject parrots = new JsonObject();
		// TreeMap: порядок ключей в файле должен быть устойчив между запусками,
		// иначе diff снимка становится нечитаемым.
		for (Map.Entry<String, SnapshotDto.ParrotRecordDto> entry
				: new TreeMap<>(snapshot.parrots()).entrySet()) {
			parrots.add(entry.getKey(), recordToJson(entry.getValue()));
		}
		root.add("parrots", parrots);

		JsonArray tombstones = new JsonArray();
		for (SnapshotDto.TombstoneDto tombstone : snapshot.tombstones()) {
			tombstones.add(tombstoneToJson(tombstone));
		}
		root.add("tombstones", tombstones);

		return root;
	}

	static JsonObject recordToJson(SnapshotDto.ParrotRecordDto record) {
		JsonObject json = new JsonObject();

		JsonObject viewer = new JsonObject();
		viewer.addProperty("viewerId", record.viewer().viewerId());
		viewer.addProperty("displayName", record.viewer().displayName());
		viewer.addProperty("createdAt", record.viewer().createdAt().toString());
		json.add("viewer", viewer);

		json.addProperty("incarnationId", record.incarnationId().toString());
		json.addProperty("generation", record.generation());
		if (record.entityUuid() != null) {
			json.addProperty("entityUuid", record.entityUuid().toString());
		}
		json.addProperty("variant", record.variant());
		json.addProperty("trait", record.trait());
		json.addProperty("desiredBehavior", record.desiredBehavior());
		json.addProperty("physicalStatus", record.physicalStatus());

		if (record.lastKnownLocation() != null) {
			JsonObject location = new JsonObject();
			location.addProperty("dimension", record.lastKnownLocation().dimension());
			location.addProperty("x", record.lastKnownLocation().x());
			location.addProperty("y", record.lastKnownLocation().y());
			location.addProperty("z", record.lastKnownLocation().z());
			json.add("lastKnownLocation", location);
		}

		if (record.lastSpawnedAt() != null) {
			json.addProperty("lastSpawnedAt", record.lastSpawnedAt().toString());
		}
		json.addProperty("updatedAt", record.updatedAt().toString());
		json.addProperty("revision", record.revision());
		return json;
	}

	static JsonObject tombstoneToJson(SnapshotDto.TombstoneDto tombstone) {
		JsonObject json = new JsonObject();
		json.addProperty("viewerId", tombstone.viewerId());
		json.addProperty("incarnationId", tombstone.incarnationId().toString());
		json.addProperty("lastGeneration", tombstone.lastGeneration());
		json.addProperty("removedAt", tombstone.removedAt().toString());
		return json;
	}

}
