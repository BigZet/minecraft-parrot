package io.github.viewerparrots.entity;

import io.github.viewerparrots.ViewerParrotsMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Регистрация собственного типа сущности. Спавн-яйцо, природный спавн и рецепты
 * намеренно не регистрируются.
 */
public final class ModEntities {
	public static final String VIEWER_PARROT_PATH = "viewer_parrot";

	public static final EntityType<ViewerParrotEntity> VIEWER_PARROT = Registry.register(
			Registries.ENTITY_TYPE,
			ViewerParrotsMod.id(VIEWER_PARROT_PATH),
			EntityType.Builder.create(ViewerParrotEntity::new, SpawnGroup.CREATURE)
					.dimensions(0.5f, 0.9f)
					.eyeHeight(0.54f)
					.maxTrackingRange(8)
					.build(VIEWER_PARROT_PATH)
	);

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(VIEWER_PARROT, ViewerParrotEntity.createViewerParrotAttributes());
	}
}
