package com.moakiee.thunderbolt.core.eject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.moakiee.thunderbolt.core.eject.ThunderboltBlockEntities;

/** Runtime-only block entity used for endpoints whose real block position is empty. */
public final class ThunderboltGhostOutputBlockEntity extends BlockEntity {
    public ThunderboltGhostOutputBlockEntity(BlockPos pos) {
        super(ThunderboltBlockEntities.GHOST_OUTPUT.get(), pos, Blocks.AIR.defaultBlockState());
    }

    public ThunderboltGhostOutputBlockEntity(BlockPos pos, BlockState ignoredState) {
        this(pos);
    }
}
