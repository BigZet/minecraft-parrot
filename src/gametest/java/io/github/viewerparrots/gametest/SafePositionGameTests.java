package io.github.viewerparrots.gametest;

import io.github.viewerparrots.entity.SafePositionFinder;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/** A clear destination must also have a safe descent and support below it. */
public final class SafePositionGameTests implements FabricGameTest {
    @GameTest(batchId = "safe-position", templateName = EMPTY_STRUCTURE)
    public void rejectsDangerousSupportBelowClearAir(TestContext test) {
        SafePositionFinder finder = new SafePositionFinder(new Random(1));
        BlockPos support = test.getAbsolutePos(new BlockPos(3, 1, 3));
        Vec3d airborne = Vec3d.ofBottomCenter(support.up(3));
        test.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
        test.assertTrue(finder.isSafe(test.getWorld(), airborne), "Clear air above stone is safe");
        test.getWorld().setBlockState(support, Blocks.MAGMA_BLOCK.getDefaultState());
        test.assertTrue(!finder.isSafe(test.getWorld(), airborne), "Magma three blocks below is unsafe support");
        test.getWorld().setBlockState(support, Blocks.CAMPFIRE.getDefaultState());
        test.assertTrue(!finder.isSafe(test.getWorld(), airborne), "Campfire below a clear candidate is unsafe");
        test.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
        test.getWorld().setBlockState(support.up(), Blocks.WATER.getDefaultState());
        test.assertTrue(!finder.isSafe(test.getWorld(), airborne), "Descent through water is unsafe");
        test.complete();
    }
}
