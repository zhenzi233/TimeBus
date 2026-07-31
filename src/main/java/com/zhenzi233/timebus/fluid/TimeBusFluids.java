package com.zhenzi233.timebus.fluid;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.BlockFluidClassic;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;

public class TimeBusFluids {

    public static final Fluid TIME_FLUID = new Fluid("time_fluid",
            new ResourceLocation(TimeBus.MOD_ID, "blocks/time_fluid_still"),
            new ResourceLocation(TimeBus.MOD_ID, "blocks/time_fluid_flow"))
            .setViscosity(1500)
            .setDensity(1200);

    public static Block TIME_FLUID_BLOCK;

    public static void register() {
        // Idempotent: register() is called from both TimeBus.preInit and CommonProxy.preInit.
        if (TIME_FLUID_BLOCK != null) {
            return;
        }
        FluidRegistry.registerFluid(TIME_FLUID);
        // registerFluid returns false (or the name is taken) when another mod owns "time_fluid".
        // Creating a BlockFluidClassic from a Fluid that is not registered here throws
        // "Cannot create a fluidstack from an unregistered fluid", so bail out in that case.
        Fluid existing = FluidRegistry.getFluid(TIME_FLUID.getName());
        if (existing == null || existing != TIME_FLUID) {
            TimeBus.LOGGER.warn("Fluid '{}' is not owned by Time Bus ({}); fluid mode will be unavailable.",
                    TIME_FLUID.getName(), existing == null ? "not registered" : "owned by another mod");
            return;
        }
        // Universal bucket support (Cleanroom registers fluids for the universal bucket;
        // no standalone bucket item is created - use the debug wand to spawn fluid).
        FluidRegistry.addBucketForFluid(TIME_FLUID);

        // Block must be created AFTER fluid registration (BlockFluidClassic calls FluidStack internally)
        TIME_FLUID_BLOCK = new BlockFluidClassic(TIME_FLUID, Material.WATER)
                .setRegistryName(TimeBus.MOD_ID, "time_fluid");
        GameRegistry.findRegistry(Block.class).register(TIME_FLUID_BLOCK);
        TIME_FLUID.setBlock(TIME_FLUID_BLOCK);
    }
}
