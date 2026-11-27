package dev.ae2lattice;

import java.util.List;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Identity keys keep count tests independent of a running Minecraft registry or world. */
public final class TestKey extends AEKey {
    public AEKeyType getType() { throw new UnsupportedOperationException(); }
    public AEKey dropSecondary() { return this; }
    public CompoundTag toTag() { throw new UnsupportedOperationException(); }
    public Object getPrimaryKey() { return this; }
    public ResourceLocation getId() { throw new UnsupportedOperationException(); }
    public void writeToPacket(FriendlyByteBuf buffer) { throw new UnsupportedOperationException(); }
    protected Component computeDisplayName() { throw new UnsupportedOperationException(); }
    public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        throw new UnsupportedOperationException();
    }
}
