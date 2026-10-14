package io.github.viewerparrots.cosmetic;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/** Shared canonical hat IDs and selection policy; persistence stays with the entity. */
public final class ParrotHats {
	public static final List<String> IDS = List.of("royal_crown", "top_hat", "pirate_hat", "mushroom_hat",
			"wizard_hat", "propeller_cap", "cowboy_hat", "ufo_hat");

	private ParrotHats() { }

	public static boolean isKnown(String id) {
		return id != null && IDS.contains(id);
	}

	public static String normalize(String id) {
		if (id == null) {
			return "";
		}
		String normalized = id.strip().toLowerCase(Locale.ROOT);
		return isKnown(normalized) ? normalized : "";
	}

	public static String randomDifferent(String current, Random random) {
		Objects.requireNonNull(random, "random");
		int currentIndex = IDS.indexOf(normalize(current));
		int selectedIndex = random.nextInt(IDS.size() - (currentIndex >= 0 ? 1 : 0));
		if (currentIndex >= 0 && selectedIndex >= currentIndex) {
			selectedIndex++;
		}
		return IDS.get(selectedIndex);
	}
}
