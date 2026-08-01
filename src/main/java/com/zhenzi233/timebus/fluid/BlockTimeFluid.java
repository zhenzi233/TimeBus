package com.zhenzi233.timebus.fluid;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidClassic;
import net.minecraftforge.fluids.Fluid;

/**
 * Time Fluid block. Entities touching it are granted Speed III and Haste III
 * for 10 seconds (200 ticks) - time itself speeds them up.
 * Note: Cleanroom uses the onEntityCollision name (not MCP's
 * onEntityCollidedWithBlock), matching AE2 UEL's own block implementations.
 */
public class BlockTimeFluid extends BlockFluidClassic {

    public static final int BUFF_DURATION_TICKS = 200; // 10 seconds
    public static final int BUFF_AMPLIFIER = 2;        // level III = amplifier 2

    public BlockTimeFluid(Fluid fluid, Material material) {
        super(fluid, material);
    }

    @Override
    public void onEntityCollision(World worldIn, BlockPos pos, IBlockState state, Entity entityIn) {
        super.onEntityCollision(worldIn, pos, state, entityIn);
        if (entityIn instanceof EntityLivingBase && !worldIn.isRemote) {
            EntityLivingBase living = (EntityLivingBase) entityIn;
            living.addPotionEffect(new PotionEffect(
                    net.minecraft.init.MobEffects.SPEED, BUFF_DURATION_TICKS, BUFF_AMPLIFIER));
            living.addPotionEffect(new PotionEffect(
                    net.minecraft.init.MobEffects.HASTE, BUFF_DURATION_TICKS, BUFF_AMPLIFIER));
        }
    }
}
