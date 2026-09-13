package io.github.viewerparrots.storage;

import io.github.viewerparrots.storage.dto.JournalEntryDto;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Append-only журнал транзакций (раздел 8.2 ТЗ).
 *
 * <p>Каждая подтверждаемая запись проходит {@code force(true)} до того, как вызывающий
 * получит управление: без этого «durable PREPARE» ничего не гарантирует, а именно на нём
 * держится правило «spawn только после PREPARE».
 *
 * <p>Sequence монотонный и непрерывный. Разрыв при чтении — storage fault, поэтому счётчик
 * восстанавливается из существующего файла при открытии.
 */
public final class JournalWriter implements AutoCloseable {
	private final Path file;
	private final FileChannel channel;
	private final OutputStream out;

	private long lastSequence;

	private JournalWriter(Path file, FileChannel channel, OutputStream out, long lastSequence) {
		this.file = file;
		this.channel = channel;
		this.out = out;
		this.lastSequence = lastSequence;
	}

	public static JournalWriter open(Path file, long lastSequence) throws IOException {
		Objects.requireNonNull(file, "file");
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		FileChannel channel = FileChannel.open(file,
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		return new JournalWriter(file, channel, java.nio.channels.Channels.newOutputStream(channel), lastSequence);
	}

	public long lastSequence() {
		return this.lastSequence;
	}

	public long nextSequence() {
		return this.lastSequence + 1;
	}

	/**
	 * Дописывает запись и принудительно сбрасывает её на диск.
	 *
	 * @throws IllegalArgumentException если sequence не продолжает предыдущий
	 */
	public void append(JournalEntryDto entry) throws IOException {
		Objects.requireNonNull(entry, "entry");
		if (entry.sequence() != this.lastSequence + 1) {
			throw new IllegalArgumentException("ожидался sequence " + (this.lastSequence + 1)
					+ ", получен " + entry.sequence());
		}

		byte[] payload = (JournalCodec.write(entry) + "\n").getBytes(StandardCharsets.UTF_8);
		this.out.write(payload);
		this.out.flush();
		// Метаданные тоже: иначе после падения файл может оказаться нужной длины,
		// но с нечитаемым хвостом.
		this.channel.force(true);

		this.lastSequence = entry.sequence();
	}

	public Path file() {
		return this.file;
	}

	@Override
	public void close() throws IOException {
		try {
			this.out.flush();
			if (this.channel.isOpen()) {
				this.channel.force(true);
			}
		} finally {
			this.channel.close();
		}
	}
}
