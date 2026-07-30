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
        FluidRegistry.registerFluid(TIME_FLUID);
        FluidRegistry.addBucketForFluid(TIME_FLUID);
        // Block must be created AFTER fluid registration (BlockFluidClassic calls FluidStack internally)
        TIME_FLUID_BLOCK = new BlockFluidClassic(TIME_FLUID, Material.WATER)
                .setRegistryName(TimeBus.MOD_ID, "time_fluid");
        GameRegistry.findRegistry(Block.class).register(TIME_FLUID_BLOCK);
        TIME_FLUID.setBlock(TIME_FLUID_BLOCK);
    }
}
