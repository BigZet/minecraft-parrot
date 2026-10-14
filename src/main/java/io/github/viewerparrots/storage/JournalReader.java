package io.github.viewerparrots.storage;

import io.github.viewerparrots.storage.dto.JournalEntryDto;
import com.google.gson.stream.MalformedJsonException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Чтение журнала с разбором аварийных случаев (раздел 8.3 ТЗ).
 *
 * <p>Единственная допустимая потеря — неполная последняя строка: сервер мог упасть в момент
 * записи. Она отбрасывается с предупреждением. Всё остальное — повреждение середины, разрыв
 * sequence, неверная контрольная сумма — storage fault, потому что иначе подтверждённая
 * операция исчезнет молча.
 */
public final class JournalReader {

	private JournalReader() {
	}

	/**
	 * @param truncatedTail  сюда попадает описание отброшенного хвоста, если он был
	 * @return записи в порядке возрастания sequence
	 */
	public static Result read(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		if (!Files.exists(file)) {
			return new Result(List.of(), null);
		}

		byte[] bytes = Files.readAllBytes(file);
		int boundary = bytes.length - 1;
		while (boundary >= 0 && bytes[boundary] != '\n') boundary--;
		// Committed bytes are decoded strictly. Only the bytes after the final newline may
		// contain half of a UTF-8 code point following a crash during append.
		List<String> lines = new ArrayList<>(StandardCharsets.UTF_8.newDecoder()
				.decode(ByteBuffer.wrap(bytes, 0, boundary + 1)).toString().lines().toList());
		int committedLines = lines.size();
		String truncatedTail = null;
		if (boundary + 1 < bytes.length) {
			ByteBuffer suffix = ByteBuffer.wrap(bytes, boundary + 1, bytes.length - boundary - 1);
			try {
				lines.add(StandardCharsets.UTF_8.newDecoder().decode(suffix).toString());
			} catch (CharacterCodingException error) {
				if (!isIncompleteUtf8Sequence(bytes, suffix.position())) {
					throw new StorageFaultException("журнал:строка " + (committedLines + 1)
							+ ": некорректный UTF-8 в последней записи", error);
				}
				truncatedTail = "строка " + (committedLines + 1) + " отброшена как незафиксированный хвост: неполный UTF-8";
			}
		}

		List<JournalEntryDto> entries = new ArrayList<>();
		long previousSequence = -1;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			long lineNumber = i + 1L;
			boolean isLastLine = i == lines.size() - 1;

			if (line.isBlank()) {
				if (isLastLine) {
					continue;
				}
				throw new StorageFaultException("журнал:строка " + lineNumber + ": пустая строка в середине файла");
			}

			JournalEntryDto entry;
			try {
				entry = JournalCodec.read(line, lineNumber);
			} catch (RuntimeException e) {
				if (e instanceof StorageMigrator.UnsupportedSchemaException) throw e;
				if (isLastLine && i >= committedLines && isIncompleteJson(e)) {
					// Незавершённая запись последней строки: единственный допустимый случай.
					truncatedTail = "строка " + lineNumber + " отброшена как незафиксированный хвост: "
							+ e.getMessage();
					break;
				}
				throw e instanceof StorageFaultException fault
						? fault
						: new StorageFaultException("журнал:строка " + lineNumber + ": " + e.getMessage(), e);
			}

			if (previousSequence >= 0 && entry.sequence() != previousSequence + 1) {
				throw new StorageFaultException("журнал:строка " + lineNumber + ": разрыв sequence, ожидался "
						+ (previousSequence + 1) + ", получен " + entry.sequence());
			}
			previousSequence = entry.sequence();
			entries.add(entry);
		}

		return new Result(List.copyOf(entries), truncatedTail);
	}

	private static boolean isIncompleteJson(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof EOFException) return true;
			if (cause instanceof MalformedJsonException) {
				String message = cause.getMessage();
				if (message != null && (message.startsWith("Unterminated ")
						|| message.startsWith("End of input"))) return true;
			}
		}
		return false;
	}

	/** Accept only a valid UTF-8 sequence cut short at the physical end of the file. */
	private static boolean isIncompleteUtf8Sequence(byte[] bytes, int start) {
		if (start >= bytes.length) return false;
		int first = bytes[start] & 0xff;
		int expected = first >= 0xc2 && first <= 0xdf ? 2
				: first >= 0xe0 && first <= 0xef ? 3
				: first >= 0xf0 && first <= 0xf4 ? 4 : 0;
		if (expected == 0 || bytes.length - start >= expected) return false;
		for (int index = start + 1; index < bytes.length; index++) {
			int continuation = bytes[index] & 0xff;
			if (continuation < 0x80 || continuation > 0xbf) return false;
			if (index == start + 1 && ((first == 0xe0 && continuation < 0xa0)
					|| (first == 0xed && continuation >= 0xa0)
					|| (first == 0xf0 && continuation < 0x90)
					|| (first == 0xf4 && continuation >= 0x90))) return false;
		}
		return true;
	}

	public record Result(List<JournalEntryDto> entries, String truncatedTail) {
		public Result {
			entries = List.copyOf(entries);
		}

		public boolean hasTruncatedTail() {
			return this.truncatedTail != null;
		}

		public long lastSequence() {
			return this.entries.isEmpty() ? 0L : this.entries.get(this.entries.size() - 1).sequence();
		}
	}
}
