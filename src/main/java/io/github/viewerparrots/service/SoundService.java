package io.github.viewerparrots.service;

import io.github.viewerparrots.ViewerParrotsMod;
import io.github.viewerparrots.diagnostics.AuditLog;
import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.entity.ViewerParrotEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Воспроизведение случайного звука из настроенного списка.
 *
 * <p>Звук отправляется сервером один раз из координат попугая; клиент не дублирует его
 * локально. По умолчанию список содержит пять встроенных записей настоящих
 * попугаев; каждая выбирается с одинаковой вероятностью.
 */
public final class SoundService {
	private final ViewerParrotsConfig config;
	private final Random random;
	private final List<SoundEvent> allowedSounds;

	public SoundService(ViewerParrotsConfig config, Random random) {
		this.config = Objects.requireNonNull(config, "config");
		this.random = Objects.requireNonNull(random, "random");
		this.allowedSounds = resolve(config.sound().allowedSounds());
	}

	/**
	 * @return {@code false} если ни один звук из белого списка не нашёлся в реестре игры
	 */
	public boolean hasUsableSounds() {
		return !this.allowedSounds.isEmpty();
	}

	public int usableSoundCount() {
		return this.allowedSounds.size();
	}

	/** Играет случайный разрешённый звук из позиции попугая. */
	public boolean playScream(ViewerParrotEntity parrot) {
		Optional<SoundEvent> sound = pickSound();
		if (sound.isEmpty()) {
			return false;
		}

		float volume = (float) this.config.sound().volume();
		float pitch = randomPitch();

		// playSound с null-игроком: слышат все, кто находится в радиусе слышимости,
		// и никто не получает звук дважды.
		parrot.getWorld().playSound(null, parrot.getX(), parrot.getY(), parrot.getZ(),
				sound.get(), SoundCategory.NEUTRAL, volume, pitch);
		return true;
	}

	private Optional<SoundEvent> pickSound() {
		if (this.allowedSounds.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(this.allowedSounds.get(this.random.nextInt(this.allowedSounds.size())));
	}

	private float randomPitch() {
		double min = this.config.sound().minPitch();
		double max = this.config.sound().maxPitch();
		return (float) (min + this.random.nextDouble() * (max - min));
	}

	private static List<SoundEvent> resolve(List<String> ids) {
		long started = AuditLog.started();
		List<SoundEvent> resolved = new ArrayList<>(ids.size());
		List<String> unavailable = new ArrayList<>();
		for (String id : ids) {
			Identifier identifier = Identifier.tryParse(id);
			SoundEvent event = identifier == null || !Registries.SOUND_EVENT.containsId(identifier)
					? null : Registries.SOUND_EVENT.get(identifier);
			if (event == null) {
				// Валидация конфига уже могла это отсеять, но реестр доступен только
				// в рантайме, поэтому здесь остаётся предупреждение, а не отказ.
				unavailable.add(id);
				continue;
			}
			resolved.add(event);
		}
		if (!unavailable.isEmpty()) {
			AuditLog.warn("sound_unavailable", null, "SKIPPED", "UNKNOWN_SOUND", started,
					java.util.Map.of("soundIds", unavailable), null);
		}
		return List.copyOf(resolved);
	}

	public static boolean soundExists(String id) {
		Identifier identifier = Identifier.tryParse(id);
		return identifier != null && Registries.SOUND_EVENT.containsId(identifier);
	}
}
