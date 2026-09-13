package io.github.viewerparrots.service;

import io.github.viewerparrots.config.ViewerParrotsConfig;
import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Раздел 15.1 ТЗ: веса trait проверяются через контролируемый RNG, без flaky
 * Monte Carlo assertion.
 */
class TraitSelectorTest {

	/** RNG с заранее заданной последовательностью: результат выбора полностью определён. */
	private static final class ScriptedRandom extends Random {
		private final int[] values;
		private int index;

		private ScriptedRandom(int... values) {
			this.values = values;
		}

		@Override
		public int nextInt(int bound) {
			int value = this.values[this.index++ % this.values.length];
			if (value >= bound) {
				throw new IllegalStateException("подготовленное значение " + value
						+ " выходит за границу " + bound);
			}
			return value;
		}
	}

	@Test
	@DisplayName("границы весов по умолчанию отображаются в ожидаемые trait")
	void defaultWeightsMapToExpectedTraits() {
		Map<ParrotTrait, Integer> weights = ViewerParrotsConfig.defaults().behavior().traitWeights();

		// Веса 40/20/20/20 дают интервалы NORMAL 0..39, COWARD 40..59, LAZY 60..79, LOUD 80..99.
		assertAll(
				() -> assertEquals(ParrotTrait.NORMAL, select(weights, 0)),
				() -> assertEquals(ParrotTrait.NORMAL, select(weights, 39)),
				() -> assertEquals(ParrotTrait.COWARD, select(weights, 40)),
				() -> assertEquals(ParrotTrait.COWARD, select(weights, 59)),
				() -> assertEquals(ParrotTrait.LAZY, select(weights, 60)),
				() -> assertEquals(ParrotTrait.LAZY, select(weights, 79)),
				() -> assertEquals(ParrotTrait.LOUD, select(weights, 80)),
				() -> assertEquals(ParrotTrait.LOUD, select(weights, 99)));
	}

	@Test
	@DisplayName("trait с нулевым весом не выпадает никогда")
	void zeroWeightTraitsAreNeverSelected() {
		Map<ParrotTrait, Integer> weights = ViewerParrotsConfig.defaults().behavior().traitWeights();
		Map<ParrotTrait, Integer> counts = new EnumMap<>(ParrotTrait.class);

		TraitSelector selector = new TraitSelector(new Random(20260913L));
		for (int i = 0; i < 5_000; i++) {
			counts.merge(selector.selectTrait(weights), 1, Integer::sum);
		}

		assertAll(
				() -> assertEquals(null, counts.get(ParrotTrait.HERO), "HERO не должен выпадать"),
				() -> assertEquals(null, counts.get(ParrotTrait.THIEF), "THIEF не должен выпадать"),
				() -> assertTrue(counts.containsKey(ParrotTrait.NORMAL)),
				() -> assertTrue(counts.containsKey(ParrotTrait.COWARD)),
				() -> assertTrue(counts.containsKey(ParrotTrait.LAZY)),
				() -> assertTrue(counts.containsKey(ParrotTrait.LOUD)));
	}

	@Test
	@DisplayName("единственный ненулевой вес даёт единственный результат")
	void singleNonZeroWeightIsDeterministic() {
		Map<ParrotTrait, Integer> weights = new LinkedHashMap<>();
		for (ParrotTrait trait : ParrotTrait.values()) {
			weights.put(trait, 0);
		}
		weights.put(ParrotTrait.LAZY, 7);

		TraitSelector selector = new TraitSelector(new Random(1L));
		for (int i = 0; i < 100; i++) {
			assertEquals(ParrotTrait.LAZY, selector.selectTrait(weights));
		}
	}

	@Test
	@DisplayName("нулевая сумма весов — программная ошибка, а не случайный выбор")
	void zeroTotalWeightThrows() {
		Map<ParrotTrait, Integer> weights = new LinkedHashMap<>();
		for (ParrotTrait trait : ParrotTrait.values()) {
			weights.put(trait, 0);
		}

		TraitSelector selector = new TraitSelector(new Random(1L));
		assertThrows(IllegalArgumentException.class, () -> selector.selectTrait(weights));
	}

	@Test
	@DisplayName("расцветка выбирается равновероятно из всех пяти")
	void variantCoversAllFive() {
		TraitSelector selector = new TraitSelector(new Random(20260913L));
		java.util.EnumSet<ParrotVariant> seen = java.util.EnumSet.noneOf(ParrotVariant.class);
		for (int i = 0; i < 500; i++) {
			seen.add(selector.selectVariant());
		}
		assertEquals(ParrotVariant.values().length, seen.size());
	}

	private static ParrotTrait select(Map<ParrotTrait, Integer> weights, int roll) {
		return new TraitSelector(new ScriptedRandom(roll)).selectTrait(weights);
	}
}
