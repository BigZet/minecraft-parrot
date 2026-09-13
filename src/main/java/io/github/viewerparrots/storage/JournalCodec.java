package io.github.viewerparrots.storage;

import com.google.gson.JsonObject;
import io.github.viewerparrots.json.CanonicalJson;
import io.github.viewerparrots.json.JsonSchemaException;
import io.github.viewerparrots.json.JsonView;
import io.github.viewerparrots.json.StrictJson;
import io.github.viewerparrots.storage.dto.JournalEntryDto;
import io.github.viewerparrots.storage.dto.SnapshotDto;

/**
 * Одна строка журнала — один JSON-объект (раздел 8.2 ТЗ).
 *
 * <p>Контрольная сумма считается по канонизированному представлению без самого поля
 * {@code checksum}; правила канонизации закреплены в {@link CanonicalJson} и проверяются
 * тестом, потому что сумма обязана совпадать между запусками.
 */
public final class JournalCodec {

	private JournalCodec() {
	}

	/** @return строка журнала с проставленной контрольной суммой, без перевода строки */
	public static String write(JournalEntryDto entry) {
		JsonObject json = toJson(entry);
		json.addProperty(CanonicalJson.CHECKSUM_FIELD, CanonicalJson.checksum(json));
		return json.toString();
	}

	public static JournalEntryDto read(String line, long lineNumber) {
		String path = "журнал:строка " + lineNumber;
		JsonObject json;
		try {
			json = StrictJson.parseObject(line);
		} catch (JsonSchemaException e) {
			throw new JsonSchemaException(path, e.getMessage(), e);
		}

		JsonView view = new JsonView(json, path);
		view.requireKnownFields("schemaVersion", "sequence", "operationId", "phase", "viewerId",
				"incarnationId", "generation", "record", "tombstone", CanonicalJson.CHECKSUM_FIELD);

		int schemaVersion = view.requireInt("schemaVersion");
		StorageMigrator.requireReadable(schemaVersion, path + ".schemaVersion");

		String expected = view.requireString(CanonicalJson.CHECKSUM_FIELD);
		String actual = CanonicalJson.checksum(json);
		if (!actual.equals(expected)) {
			// Не пропускаем молча: раздел 8.3 требует считать это storage fault.
			throw new StorageFaultException(path + ": контрольная сумма не совпадает, ожидалась "
					+ expected + ", вычислена " + actual);
		}

		SnapshotDto.ParrotRecordDto record = view.has("record")
				? SnapshotCodec.readRecord(view.requireObject("record"))
				: null;
		SnapshotDto.TombstoneDto tombstone = view.has("tombstone")
				? SnapshotCodec.readTombstone(view.requireObject("tombstone"))
				: null;

		return new JournalEntryDto(
				schemaVersion,
				view.requireLong("sequence"),
				view.requireUuid("operationId"),
				view.requireEnum("phase", JournalEntryDto.Phase.class),
				view.requireString("viewerId"),
				view.optionalUuid("incarnationId").orElse(null),
				view.has("generation") ? view.requireLong("generation") : 0L,
				record,
				tombstone,
				expected);
	}

	private static JsonObject toJson(JournalEntryDto entry) {
		JsonObject json = new JsonObject();
		json.addProperty("schemaVersion", entry.schemaVersion());
		json.addProperty("sequence", entry.sequence());
		json.addProperty("operationId", entry.operationId().toString());
		json.addProperty("phase", entry.phase().name());
		json.addProperty("viewerId", entry.viewerId());
		if (entry.incarnationId() != null) {
			json.addProperty("incarnationId", entry.incarnationId().toString());
		}
		json.addProperty("generation", entry.generation());
		if (entry.record() != null) {
			json.add("record", SnapshotCodec.recordToJson(entry.record()));
		}
		if (entry.tombstone() != null) {
			json.add("tombstone", SnapshotCodec.tombstoneToJson(entry.tombstone()));
		}
		return json;
	}
}
