package io.github.viewerparrots.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Selective access to vanilla shoulder storage; dropping one bird leaves the other untouched. */
@Mixin(PlayerEntity.class)
public interface PlayerShoulderAccess {
    @Invoker("setShoulderEntityLeft")
    void viewerparrots$setShoulderLeft(NbtCompound nbt);

    @Invoker("setShoulderEntityRight")
    void viewerparrots$setShoulderRight(NbtCompound nbt);

    @Invoker("dropShoulderEntity")
    void viewerparrots$dropShoulderEntity(NbtCompound nbt);
}
