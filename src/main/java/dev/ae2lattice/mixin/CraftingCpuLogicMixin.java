package dev.ae2lattice.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import dev.ae2lattice.Lattice;
import dev.ae2lattice.server.LatticeTracker;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only observation of the crafting CPU. Never changes what AE2 does:
 * the redirect calls the original pushPattern and returns its result untouched.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicMixin {
    @Shadow
    @Final
    CraftingCPUCluster cluster;

    @Shadow
    private ExecutingCraftingJob job;

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void ae2lattice$saveTracking(CompoundTag data, CallbackInfo ci) {
        try {
            LatticeTracker.save(cluster, data);
        } catch (Throwable t) {
            Lattice.LOG.warn("Lattice: failed to save recipe output tracking", t);
        }
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void ae2lattice$loadTracking(CompoundTag data, CallbackInfo ci) {
        try {
            LatticeTracker.load(cluster, data);
        } catch (Throwable t) {
            Lattice.LOG.warn("Lattice: failed to restore recipe output tracking", t);
        }
    }

    @Redirect(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2lattice$observePush(ICraftingProvider provider, IPatternDetails details, KeyCounter[] inputs) {
        boolean pushed = provider.pushPattern(details, inputs);
        if (pushed) {
            try {
                LatticeTracker.onPush(cluster, details, provider);
            } catch (Throwable t) {
                Lattice.LOG.debug("Lattice: failed to record push", t);
            }
        }
        return pushed;
    }

    /** AE2 is finishing the job (success = crafted everything, false = cancelled). Just take a note. */
    @Inject(method = "finishJob", at = @At("HEAD"))
    private void ae2lattice$observeFinish(boolean success, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (job == null) return;
        try {
            var ja = (ExecutingCraftingJobAccessor) job;
            long elapsed = ((CraftingCpuLogic) (Object) this).getElapsedTimeTracker().getElapsedTime();
            LatticeTracker.onFinish(cluster, success, ja.ae2lattice$getFinalOutput(),
                    ja.ae2lattice$getRemainingAmount(), elapsed);
        } catch (Throwable t) {
            Lattice.LOG.debug("Lattice: failed to record job finish", t);
        }
    }

    @Inject(method = "insert", at = @At("HEAD"))
    private void ae2lattice$observeReturn(AEKey what, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        if (mode != Actionable.MODULATE || what == null || job == null || amount <= 0) {
            return;
        }
        try {
            long waiting = ((ExecutingCraftingJobAccessor) job).ae2lattice$getWaitingFor()
                    .extract(what, amount, Actionable.SIMULATE);
            if (waiting > 0) {
                LatticeTracker.onReturn(cluster, what, Math.min(waiting, amount));
            }
        } catch (Throwable t) {
            Lattice.LOG.debug("Lattice: failed to record return", t);
        }
    }
}
