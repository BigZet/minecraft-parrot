package io.github.viewerparrots.domain;

/**
 * Расцветка попугая-зрителя. Это доменное перечисление сознательно не зависит от
 * Minecraft: сопоставление с ванильными вариантами выполняет явный mapper в слое entity.
 */
public enum ParrotVariant {
	RED_BLUE,
	BLUE,
	GREEN,
	YELLOW_BLUE,
	GRAY
}
