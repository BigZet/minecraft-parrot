package io.github.viewerparrots.service;

import io.github.viewerparrots.domain.ParrotTrait;
import io.github.viewerparrots.domain.ParrotVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Выбор характера и расцветки при создании (раздел 10 ТЗ).
 *
 * <p>Trait выбирается один раз и сохраняется; изменение весов в конфиге не перераспределяет
 * уже созданных попугаев. Генератор случайности внедряется, чтобы тесты проверяли
 * распределение без flaky Monte Carlo.
 */
public final class TraitSelector {
	private final Random random;

	public TraitSelector(Random random) {
		this.random = Objects.requireNonNull(random, "random");
	}

	/**
	 * @param weights веса из конфига; HERO и THIEF в MVP имеют нулевой вес и потому
	 *                случайно не выпадают
	 * @throws IllegalArgumentException если сумма весов не положительна — конфиг с такой
	 *         суммой не проходит валидацию, так что сюда попасть можно только программной ошибкой
	 */
	public ParrotTrait selectTrait(Map<ParrotTrait, Integer> weights) {
		int total = 0;
		List<ParrotTrait> candidates = new ArrayList<>();
		List<Integer> cumulative = new ArrayList<>();

		for (ParrotTrait trait : ParrotTrait.values()) {
			int weight = weights.getOrDefault(trait, 0);
			if (weight <= 0) {
				continue;
			}
			total += weight;
			candidates.add(trait);
			cumulative.add(total);
		}

		if (total <= 0) {
			throw new IllegalArgumentException("сумма весов должна быть положительной");
		}

		int roll = this.random.nextInt(total);
		for (int i = 0; i < candidates.size(); i++) {
			if (roll < cumulative.get(i)) {
				return candidates.get(i);
			}
		}
		// Недостижимо: roll строго меньше total, а последний порог равен total.
		throw new IllegalStateException("выбор trait не завершился, roll=" + roll + ", total=" + total);
	}

	/** Расцветка выбирается равновероятно: весов для неё ТЗ не задаёт. */
	public ParrotVariant selectVariant() {
		ParrotVariant[] variants = ParrotVariant.values();
		return variants[this.random.nextInt(variants.length)];
	}
}
