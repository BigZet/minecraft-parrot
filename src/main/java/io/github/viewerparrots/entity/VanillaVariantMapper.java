package io.github.viewerparrots.entity;

import io.github.viewerparrots.domain.ParrotVariant;
import net.minecraft.entity.passive.ParrotEntity;

/**
 * Явное сопоставление доменной расцветки с ванильной. Порядковые номера enum'ов
 * намеренно не используются: ванильный порядок не является частью контракта.
 */
public final class VanillaVariantMapper {
	private VanillaVariantMapper() {
	}

	public static ParrotEntity.Variant toVanilla(ParrotVariant variant) {
		return switch (variant) {
			case RED_BLUE -> ParrotEntity.Variant.RED_BLUE;
			case BLUE -> ParrotEntity.Variant.BLUE;
			case GREEN -> ParrotEntity.Variant.GREEN;
			case YELLOW_BLUE -> ParrotEntity.Variant.YELLOW_BLUE;
			case GRAY -> ParrotEntity.Variant.GRAY;
		};
	}

	public static ParrotVariant fromVanilla(ParrotEntity.Variant variant) {
		return switch (variant) {
			case RED_BLUE -> ParrotVariant.RED_BLUE;
			case BLUE -> ParrotVariant.BLUE;
			case GREEN -> ParrotVariant.GREEN;
			case YELLOW_BLUE -> ParrotVariant.YELLOW_BLUE;
			case GRAY -> ParrotVariant.GRAY;
		};
	}
}
