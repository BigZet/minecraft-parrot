package io.github.viewerparrots.storage;

import io.github.viewerparrots.storage.dto.JournalEntryDto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		boolean endsWithNewline = endsWithNewline(file);

		List<JournalEntryDto> entries = new ArrayList<>();
		String truncatedTail = null;
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
				if (isLastLine && !endsWithNewline) {
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

	private static boolean endsWithNewline(Path file) throws IOException {
		long size = Files.size(file);
		if (size == 0) {
			return true;
		}
		try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1);
			channel.read(buffer, size - 1);
			return buffer.get(0) == '\n';
		}
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
