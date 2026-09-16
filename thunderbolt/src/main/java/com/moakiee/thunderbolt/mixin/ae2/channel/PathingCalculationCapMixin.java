package com.moakiee.thunderbolt.mixin.ae2.channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.moakiee.thunderbolt.core.channel.BorrowedCapacityCalculator;
import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;
import com.moakiee.thunderbolt.core.channel.HighCapacityChannelSupport;
import com.moakiee.thunderbolt.core.channel.HighCapacitySubtreeNode;
import com.moakiee.thunderbolt.config.ThunderboltCommonConfig;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridMultiblock;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ChannelMode;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.me.GridConnection;
import appeng.me.GridNode;
import appeng.me.pathfinding.IPathItem;
import appeng.me.pathfinding.PathingCalculation;

/**
 * Replaces AE2's BFS-based channel assignment with Dinic's max-flow
 * for all controller networks.
 * <p>
 * Any network containing at least one controller (vanilla or high-capacity)
 * uses max-flow for channel assignment. Only ad-hoc networks (no
 * controllers at all) fall through to vanilla AE2 logic.
 * <p>
 * Phase 1 (constructor TAIL): identify high-capacity controllers, unify them
 * into a single BFS root for correct DFS tree propagation.
 * <p>
 * Phase 2 (tryUseChannel HEAD): return false for ALL devices in the network
 * so AE2 builds the routing tree without assigning channels.
 * <p>
 * Phase 3 (compute, before propagateAssignments): run max-flow, inject
 * winning devices into {@code channelNodes}, and set {@code usedChannels}
 * on each cable/node from the flow decomposition.
 */
@Mixin(PathingCalculation.class)
public abstract class PathingCalculationCapMixin {

    @Shadow @Final private IGrid grid;
    @Shadow @Final private Set<IPathItem> visited;
    @Shadow @Final private Queue<IPathItem>[] queues;
    @Shadow @Final private Set<GridNode> channelNodes;
    @Shadow @Final private Set<GridNode> multiblocksWithChannel;

    @Unique private List<IGridNode> thunderbolt$capacitySources;
    @Unique private boolean thunderbolt$useMaxFlow;
    @Unique private BorrowedCapacityCalculator.Result thunderbolt$flowResult;
    // -1 = not applicable, fall through to vanilla channelsInUse
    @Unique private int thunderbolt$maxFlowChannelsInUse;

    // ── Phase 1: constructor – identify & unify high-capacity controllers ──

    @Inject(method = "<init>", at = @At("TAIL"))
    private void thunderbolt$unifyCapacitySources(IGrid grid, CallbackInfo ci) {
        thunderbolt$maxFlowChannelsInUse = -1;
        var allControllers = HighCapacityChannelSupport.getAllControllerNodes(grid);

        List<IGridNode> capacitySources = new ArrayList<>();
        for (var node : allControllers) {
            if (ChannelSourceRegistry.isChannelSource(node.getOwner())) {
                capacitySources.add(node);
            }
        }

        thunderbolt$capacitySources = capacitySources;
        boolean hasControllers = !allControllers.isEmpty();
        var channelMode = grid.getPathingService().getChannelMode();
        thunderbolt$useMaxFlow = channelMode != ChannelMode.INFINITE
                && ThunderboltCommonConfig.useMaxFlow(grid, hasControllers);

        // Controller-root unification is part of our max-flow path. In infinite
        // mode AE2's own allocator already has unbounded capacity, so it must
        // keep its native multi-root routing tree intact. Applying only this
        // preprocessing step while falling back to vanilla assignment can drop
        // one of two adjacent devices from the final propagation tree.
        if (!thunderbolt$useMaxFlow || capacitySources.size() <= 1) {
            return;
        }

        IGridNode source = capacitySources.get(0);
        Set<IGridNode> nonSource = new ReferenceOpenHashSet<>(capacitySources.subList(1, capacitySources.size()));

        for (var node : nonSource) {
            if (node instanceof IPathItem p) {
                visited.remove(p);
            }
        }

        Queue<IPathItem> q0 = queues[0];
        var keep = new ArrayDeque<IPathItem>();
        while (!q0.isEmpty()) {
            var item = q0.poll();
            if (item instanceof GridConnection gc
                    && (nonSource.contains(gc.a()) || nonSource.contains(gc.b()))) {
                visited.remove((IPathItem) gc);
                gc.setControllerRoute(null);
                continue;
            }
            keep.add(item);
        }
        q0.addAll(keep);

        // BFS from source through ALL controllers (vanilla and high-capacity) to reach non-source capacity controllers.
        // This handles cases where high-capacity controllers are separated by vanilla
        // controllers in the multiblock (e.g. OC_A — Vanilla_B — OC_C).
        Queue<IGridNode> bfs = new ArrayDeque<>();
        Set<IGridNode> bfsVisited = new ReferenceOpenHashSet<>();
        bfs.add(source);
        bfsVisited.add(source);
        while (!bfs.isEmpty()) {
            var cur = bfs.poll();
            for (var conn : cur.getConnections()) {
                if (!(conn instanceof GridConnection gc)) continue;
                var neighbor = gc.getOtherSide(cur);
                if (!bfsVisited.add(neighbor)) continue;
                if (!(neighbor.getOwner() instanceof ControllerBlockEntity)) continue;
                if (nonSource.remove(neighbor)) {
                    if (!visited.contains((IPathItem) gc)) {
                        gc.setControllerRoute((IPathItem) cur);
                        visited.add((IPathItem) gc);
                        q0.add((IPathItem) gc);
                    }
                }
                bfs.add(neighbor);
            }
        }
    }

    // ── Phase 2: skip AE2 channel assignment for ALL devices ──

    @Inject(method = "tryUseChannel", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$skipAllDevices(GridNode node, CallbackInfoReturnable<Boolean> cir) {
        if (thunderbolt$useMaxFlow) {
            cir.setReturnValue(false);
        }
    }

    // ── Phase 3: run max-flow between BFS and DFS ──
    //   Sets static flow data so GridNode.propagateChannelsUpwards can read it
    //   during the subsequent DFS pass.

    @Inject(method = "compute",
            at = @At(value = "INVOKE",
                     target = "Lappeng/me/pathfinding/PathingCalculation;propagateAssignments()V"))
    private void thunderbolt$runMaxFlowBeforeDFS(CallbackInfo ci) {
        BorrowedCapacityCalculator.clearActiveData();

        if (!thunderbolt$useMaxFlow) {
            return;
        }

        thunderbolt$flowResult = BorrowedCapacityCalculator.assignChannels(grid, thunderbolt$capacitySources);
        if (thunderbolt$flowResult == null) {
            return;
        }

        channelNodes.addAll(thunderbolt$flowResult.channelNodes());

        for (var winner : thunderbolt$flowResult.channelNodes()) {
            if (!winner.hasFlag(GridFlags.MULTIBLOCK)) continue;
            var multiblock = ((IGridNode) winner).getService(IGridMultiblock.class);
            if (multiblock == null) continue;
            var siblings = multiblock.getMultiblockNodes();
            while (siblings.hasNext()) {
                var sibling = siblings.next();
                if (sibling != null && sibling != winner) {
                    multiblocksWithChannel.add((GridNode) sibling);
                }
            }
        }

        BorrowedCapacityCalculator.activeNodeFlow = thunderbolt$flowResult.nodeFlow();
        BorrowedCapacityCalculator.activeNetworkNodes = thunderbolt$flowResult.networkNodes();
        BorrowedCapacityCalculator.activeConnectionFlow = thunderbolt$flowResult.connectionFlow();
    }

    // ── Phase 4: force-apply max-flow results & cleanup after DFS ──

    @Inject(method = "compute", at = @At("TAIL"))
    private void thunderbolt$applyFlowAndCleanup(CallbackInfo ci) {
        if (thunderbolt$flowResult != null) {
            // Reset ALL connections of network nodes to 0 first.
            // AE2's DFS uses getMachineNodes(ControllerBlockEntity.class) which
            // misses high-capacity controllers (exact class match). When no vanilla
            // controllers exist, the DFS never runs and stale usedChannels from
            // a previous pathing calculation are never cleared.
            Set<GridConnection> resetSeen = new ReferenceOpenHashSet<>();
            Set<IGridNode> networkNodes = thunderbolt$flowResult.networkNodes();
            for (var node : networkNodes) {
                for (var conn : node.getConnections()) {
                    if (conn instanceof GridConnection gc && resetSeen.add(gc)) {
                        gc.setAdHocChannels(0);
                    }
                }
                if (node instanceof HighCapacitySubtreeNode osn) {
                    osn.thunderbolt$setUsedChannels(0);
                }
            }

            var nodeFlow = thunderbolt$flowResult.nodeFlow();
            for (var node : networkNodes) {
                if (node instanceof HighCapacitySubtreeNode osn) {
                    int flow = nodeFlow.getInt(node);
                    osn.thunderbolt$setUsedChannels(flow);
                }
            }

            // Vanilla AE2 gives every non-winner multiblock sibling +1 via
            // incrementChannelCount at the end of propagateAssignments, so that
            // meetsChannelRequirements() is true for the entire cluster.
            // We overwrote usedChannels above, so re-apply the bonus here for
            // siblings we manage. Without this, the cluster's core block may
            // end up with usedChannels=0 and the whole multiblock (e.g. a
            // crafting CPU) reports isActive()=false even though the flow
            // reservation succeeded.
            for (var sibling : multiblocksWithChannel) {
                if (networkNodes.contains(sibling)) {
                    sibling.incrementChannelCount(1);
                }
            }

            var connFlow = thunderbolt$flowResult.connectionFlow();
            for (var entry : connFlow.reference2IntEntrySet()) {
                entry.getKey().setAdHocChannels(entry.getIntValue());
            }

            // Persist used-channel count for getChannelsInUse() override.
            // Vanilla DFS in propagateAssignments() never sees our channelNodes
            // for high-capacity-only networks (getMachineNodes(ControllerBlockEntity.class)
            // misses subclasses at that call site), so channelsInUse stays 0.
            // channelNodes here holds exactly the max-flow winners (one per
            // device, one per multiblock cluster) — its size IS the answer.
            thunderbolt$maxFlowChannelsInUse = thunderbolt$flowResult.channelNodes().size();
        }
        BorrowedCapacityCalculator.clearActiveData();
        thunderbolt$flowResult = null;
    }

    // ── getChannelsInUse: report max-flow result for high-capacity networks ──
    //   PathingService reads this immediately after compute() and stores it
    //   in its own channelsInUse field, which feeds NetworkStatusMenu etc.

    @Inject(method = "getChannelsInUse", at = @At("HEAD"), cancellable = true)
    private void thunderbolt$overrideChannelsInUse(CallbackInfoReturnable<Integer> cir) {
        if (thunderbolt$maxFlowChannelsInUse >= 0) {
            cir.setReturnValue(thunderbolt$maxFlowChannelsInUse);
        }
    }
}
