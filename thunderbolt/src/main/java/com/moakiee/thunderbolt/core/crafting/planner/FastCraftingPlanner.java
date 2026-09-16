package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.crafting.inv.CraftingSimulationState;
import appeng.crafting.pattern.AECraftingPattern;

import com.moakiee.thunderbolt.api.crafting.PlanningDiagnosticSnapshot;
import com.moakiee.thunderbolt.core.crafting.pattern.CraftingStockPolicy;
import com.moakiee.thunderbolt.core.crafting.pattern.FuzzyPatternInputs;
import com.moakiee.thunderbolt.core.crafting.pattern.IWrappedPatternDetails;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockPattern;
import com.moakiee.thunderbolt.core.crafting.support.CraftingPatternDelegates;

/**
 * Bridges one of AE2's per-amount crafting attempts ({@code CraftingCalculation#runCraftAttempt})
 * to a Thunderbolt graph solver, producing AE2-compatible {@link CraftingPlan}s.
 *
 * <p>This is Thunderbolt's single production fast-planner entry. Ordinary AE2 patterns, overload
 * patterns and contracted closed-loop patterns all enter through this adapter; capability interfaces
 * in {@code core.crafting.pattern}, {@code core.crafting.overload} and {@code core.crafting.loop}
 * describe the extra facts needed by the same graph build and solve.
 *
 * <p>Hooking the per-amount attempt (rather than the whole {@code computePlan}) lets AE2 keep driving
 * its own strategy/binary-search loop while we replace only the expensive tree simulation of each
 * attempt. The contract of {@code runCraftAttempt(simulate, amount)} is mirrored exactly:
 * {@code simulate=false} returns a feasible plan or {@code null} (this amount can't be made);
 * {@code simulate=true} must return a non-null plan carrying the missing items.
 *
 * <p>Engine: the v2 planner ({@link CraftPlannerV2}) — a linear topological backbone with a bounded
 * backtracking fallback for contention. It natively handles byproducts and multiple competing recipes.
 *
 * <p>Correctness rules:
 * <ul>
 *   <li>Modeled in-engine (no decline): byproducts (shared pool), hard-fuzzy substitution (OR
 *       expansion), catalysts/non-degrading containers ({@code returned}), filled containers
 *       (consumed + leftover byproduct, e.g. bucket→empty), durability tools (use-token chain), and
 *       emittable items (infinite on-demand source → {@code emittedItems}). A recipe step we still
 *       cannot model (e.g. a durability chain longer than the cyclic budget) is dropped so its item
 *       surfaces as <em>missing</em> rather than declining the whole attempt.</li>
 *   <li><b>Recursion / cycle</b> → handled in-engine: the v2 planner breaks back-edges ("去头尾") so a
 *       compress/decompress pair (1 block ⇄ 9 ingots) is planned directly instead of declining. The
 *       reverse side resolves from stock/missing; cuts only remove options, never overstate feasibility.</li>
 *   <li>A <b>feasible</b> plan is always safe to return (mass-balanced ⇒ executable), even with
 *       byproducts and multiple recipe choices.</li>
 *   <li><b>Proven infeasible</b>: best-effort, never declined (Policy A).
 *       {@code simulate=false}→null, {@code simulate=true}→partial plan with missing items.</li>
 *   <li><b>Search budget exhausted</b>: stop enumerating alternatives and finish the current route
 *       deterministically from the already-built graph and current shared pools. Its missing items are
 *       exposed through AE2's simulation result. The missing list is a targeted replenishment
 *       suggestion, not a proof about every alternate route; no second diagnostic plan is run.</li>
 * </ul>
 *
 * <p><b>Execution-time contract (fuzzy substitution).</b> For a hard-fuzzy slot the planner commits to a
 * <em>concrete</em> substitute (the most-available option in the chosen combination) and charges that exact
 * key as used. AE2 still fires the single real {@link IPatternDetails}, and at extraction time its fuzzy
 * matcher may pull a <em>different</em> acceptable stack than the one the plan charged (e.g. a different
 * NBT/damage variant, or a different tag member that happened to be in the same slot). The plan stays
 * mass-balanced for the key it charged, but the network can end up consuming a sibling substitute instead.
 * This is an <b>execution-time</b> concern, not a planning one: reconciling "what the plan charged" with
 * "what the fuzzy slot actually resolved" is the responsibility of the executing CPU (the batch crafting
 * CPU logic), which sees the real extraction. The planner intentionally does not try to predict AE2's
 * runtime fuzzy resolution here — see the batch CPU logic for the execution-side fix.
 *
 * <p><b>Planning-side input discovery.</b> Every input follows AE2's native contract: each
 * {@link IPatternDetails.IInput#getPossibleInputs()} entry anchors one primary-identity candidate
 * group, and concrete stocked/craftable keys from those groups are retained only when
 * {@link IPatternDetails.IInput#isValid(AEKey, Level)} accepts them. This works for ordinary AE2
 * substitutions, tags and custom matchers without requiring a Thunderbolt interface. Optional
 * {@link FuzzyPatternInputs#acceptsSameIdVariants(int)} metadata is only a closure proof: it permits a
 * late-bound same-id output to feed that slot, while an unmarked arbitrary accepted-key set may still
 * consume any concrete output that {@code isValid} accepts.
 *
 * <p>Byte accounting reproduces AE2's formulas (see {@code CraftingTreeNode#request},
 * {@code CraftingTreeProcess#request} and {@code ICraftingSimulationState#addStackBytes}) over the
 * memoized DAG: byte-identical to AE2 for jobs without shared sub-graphs, smaller otherwise.
 */
public final class FastCraftingPlanner {
    private static final PlanningDiagnosticSnapshot GRAPH_EXPORT_DIAGNOSTIC =
            PlanningDiagnosticSnapshot.phase("graph_export");
    private static final PlanningDiagnosticSnapshot PLANNING_DIAGNOSTIC =
            PlanningDiagnosticSnapshot.phase("planning");

    /**
     * Hard-fuzzy budget: an input slot that accepts several substitutes is expanded into the cartesian
     * product of concrete choices, each becoming a competing recipe the v2 planner selects among by
     * availability. If a pattern's product of substitute counts exceeds this, we DON'T drop the recipe
     * (that would be a false negative for something AE2 can craft); instead we greedily keep the best
     * {@code FUZZY_NONCYCLE_STEPS} combinations — the lowest rank-sum over per-slot, most-available-first
     * ordered options — bounding the graph without losing the cheapest routes ("贪心前 N" for non-cyclic
     * fuzzy).
     */
    static final long FUZZY_NONCYCLE_STEPS = 64;

    /**
     * Cyclic-fuzzy budget. A durability tool {@code 1·A(n) + 1·B → 1·C + A(n-1)} forms a degradation
     * chain {@code A(n)→A(n-1)→…→broken}. We walk that chain once via {@code getRemainingKey}, capping
     * the walk here ("超步报缺失" → omit that recipe), then reduce it to the closed form
     * {@code uses = chainLen} so a batch costs {@code ceil(times / uses)} full tools instead of one
     * firing per durability point. Also bounds the secondary-fuzzy (re-fuzzy output) collapse.
     */
    static final long FUZZY_CYCLE_STEPS = 8192;

    /** Adapter-side cap before the immutable core graph exists. */
    private static final long MAX_GRAPH_EXPORT_WORK = Math.max(
            4_096L,
            Long.getLong("thunderbolt.maxGraphExportWork", 65_536L));

    private FastCraftingPlanner() {
    }

    /**
     * Single-threaded state for every amount probe made by one AE2 crafting calculation. The
     * inventory/pattern graph is immutable for that calculation, so it is compiled once while the
     * core planner shares its exact and fallback work budgets across {@code CRAFT_LESS} probes.
     */
    public static final class CalculationSession {
        private enum SolverKind {
            V2,
            CP_SAT
        }

        private ICraftingService craftingService;
        private CraftingSimulationState networkInv;
        private Level level;
        private AEKey output;
        private CraftingStockPolicy reservedStock;
        private Thread owner;
        private CompiledGraph compiledGraph;
        private final SolverKind solverKind;
        @Nullable
        private final CraftPlannerV2.PlanningSession<AEKey> plannerSession;

        public CalculationSession() {
            this(SolverKind.V2);
        }

        private CalculationSession(SolverKind solverKind) {
            this.solverKind = solverKind;
            this.plannerSession = solverKind == SolverKind.V2
                    ? new CraftPlannerV2.PlanningSession<>()
                    : null;
        }

        public static CalculationSession cpSat() {
            return new CalculationSession(SolverKind.CP_SAT);
        }

        private void bind(
                ICraftingService candidateCraftingService,
                CraftingSimulationState candidateNetworkInv,
                Level candidateLevel,
                AEKey candidateOutput,
                @Nullable CraftingStockPolicy candidateReservedStock) {
            PlanningCancellation.check();
            Thread current = Thread.currentThread();
            if (owner == null) {
                craftingService = candidateCraftingService;
                networkInv = candidateNetworkInv;
                level = candidateLevel;
                output = candidateOutput;
                reservedStock = candidateReservedStock;
                owner = current;
                return;
            }
            if (owner != current) {
                throw new IllegalStateException("calculation session cannot move between threads");
            }
            if (craftingService != candidateCraftingService
                    || networkInv != candidateNetworkInv
                    || level != candidateLevel
                    || reservedStock != candidateReservedStock
                    || !Objects.equals(output, candidateOutput)) {
                throw new IllegalArgumentException(
                        "calculation session is already bound to another crafting calculation");
            }
        }
    }

    /** Outcome of an attempt: either declined (run AE2) or handled (use {@link #plan}, may be null). */
    public record FastAttempt(
            boolean handled,
            @Nullable CraftingPlan plan,
            @Nullable CraftingPlan simulationFallback,
            Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
        public FastAttempt {
            usedReusableStock = Map.copyOf(usedReusableStock);
        }

        static FastAttempt decline() {
            return new FastAttempt(false, null, null, Map.of());
        }

        static FastAttempt handled(
                CraftingPlan plan, Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
            return new FastAttempt(true, plan, null, usedReusableStock);
        }

        static FastAttempt infeasible(
                CraftingPlan simulationFallback,
                Map<ReusableStockUsageKey<AEKey>, Long> usedReusableStock) {
            return new FastAttempt(true, null, simulationFallback, usedReusableStock);
        }
    }

    /**
     * Try to satisfy a single {@code runCraftAttempt(simulate, amount)} call.
     */
    public static FastAttempt tryAttempt(ICraftingService craftingService,
                                         CraftingSimulationState networkInv,
                                         Level level,
                                         AEKey output,
                                         long amount,
                                         boolean simulate) {
        return tryAttempt(
                craftingService, networkInv, level, output, amount, simulate, null,
                new CalculationSession());
    }

    public static FastAttempt tryAttempt(ICraftingService craftingService,
                                         CraftingSimulationState networkInv,
                                         Level level,
                                         AEKey output,
                                         long amount,
                                         boolean simulate,
                                         @Nullable CraftingStockPolicy reservedStock) {
        return tryAttempt(
                craftingService, networkInv, level, output, amount, simulate, reservedStock,
                new CalculationSession());
    }

    public static FastAttempt tryAttempt(ICraftingService craftingService,
                                         CraftingSimulationState networkInv,
                                         Level level,
                                         AEKey output,
                                         long amount,
                                         boolean simulate,
                                         @Nullable CraftingStockPolicy reservedStock,
                                         CalculationSession session) {
        if (amount <= 0) {
            return FastAttempt.decline();
        }
        Objects.requireNonNull(session, "session");
        session.bind(craftingService, networkInv, level, output, reservedStock);

        // Snapshot inventory the same way AE2 does: a child view that ignores the requested output
        // (existing output stock is never consumed; the full amount is always crafted).
        ChildCraftingSimulationState snapshot = new ChildCraftingSimulationState(networkInv);
        snapshot.ignore(output);

        CompiledGraph compiled = session.compiledGraph;
        if (compiled == null) {
            PlanningCancellation.report(GRAPH_EXPORT_DIAGNOSTIC);
            compiled = compileGraph(
                    craftingService,
                    snapshot,
                    new ChildCraftingSimulationState(networkInv),
                    level,
                    output,
                    reservedStock);
            session.compiledGraph = compiled;
        }

        PlanningCancellation.report(PLANNING_DIAGNOSTIC);
        CraftPlan<AEKey> plan;
        if (session.solverKind == CalculationSession.SolverKind.CP_SAT) {
            CpSatRankedFlowSolver.Result<AEKey> solved =
                    CpSatRankedFlowSolver.solve(compiled.graph, output, amount);
            if (solved.status() != CpSatRankedFlowSolver.Status.SOLVED
                    || solved.plan() == null) {
                return FastAttempt.decline();
            }
            plan = solved.plan();
        } else {
            PlanningResult<AEKey> planning = CraftPlannerV2.planDetailed(
                    compiled.graph, output, amount,
                    Objects.requireNonNull(session.plannerSession));
            plan = planning.plan();
        }
        if (!plan.supported()) {
            return FastAttempt.decline();
        }
        boolean multi = compiled.multiplePaths;
        // Emittable shortfalls are supplied by emitters, not crafted, so they don't make a plan
        // infeasible — only a non-emittable shortfall does.
        if (plan.feasible() || noNonEmittableMissing(plan, compiled.emittable)) {
            return FastAttempt.handled(
                    toAe2Plan(output, amount, plan, multi, false, compiled.durability,
                            compiled.patternSources, compiled.emittable, snapshot, reservedStock),
                    plan.usedReusableStock());
        }
        // Infeasible at this amount. A completed search reports proven shortfalls; a budget-limited
        // search reports the missing leaves of its best concrete diagnostic route. In both cases keep
        // the attempt inside Thunderbolt so AE2 can show replenishment targets without re-entering the
        // exhaustive simulator that this fast path is protecting.
        if (!simulate) {
            // AE2 asks for this exact amount again with simulate=true to build the missing-material
            // page. Build that cheap representation now while the graph/snapshot are still available;
            // CraftingCalculationMixin reuses it instead of rebuilding and replanning the same graph.
            return FastAttempt.infeasible(
                    toAe2Plan(output, amount, plan, multi, true, compiled.durability,
                            compiled.patternSources, compiled.emittable, snapshot, reservedStock),
                    plan.usedReusableStock());
        }
        return FastAttempt.handled(
                toAe2Plan(output, amount, plan, multi, true, compiled.durability,
                        compiled.patternSources, compiled.emittable, snapshot, reservedStock),
                plan.usedReusableStock()); // partial + missing
    }

    private static CompiledGraph compileGraph(
            ICraftingService craftingService,
            ChildCraftingSimulationState snapshot,
            ChildCraftingSimulationState reusableSeedSnapshot,
            Level level,
            AEKey output,
            @Nullable CraftingStockPolicy reservedStock) {
        // A durability item can occasionally be used under incompatible semantics in the same
        // reachable graph. Rediscover only those items using conservative whole-item semantics.
        Set<Item> conservativeDurabilityItems = new HashSet<>();
        Map<AEKey, RequirementMode> forcedRequirementModes = new HashMap<>();
        GraphExportBudget exportBudget = new GraphExportBudget(MAX_GRAPH_EXPORT_WORK);
        PrimaryIdentityCraftables primaryIdentityCraftables = new PrimaryIdentityCraftables(
                () -> craftingService.getCraftables(ignored -> true), exportBudget);
        GraphBuild graphBuild;
        try {
            while (true) {
                exportBudget.consume();
                graphBuild = new GraphBuild();
                GraphBuildDiscovery discovery = buildGraph(
                        craftingService, snapshot, reusableSeedSnapshot, level, output,
                        graphBuild.builder, graphBuild.multiplePaths, graphBuild.durability,
                        graphBuild.patternSources, graphBuild.emittable, conservativeDurabilityItems,
                        forcedRequirementModes, primaryIdentityCraftables, exportBudget, reservedStock);
                boolean durabilityChanged = conservativeDurabilityItems.addAll(
                        discovery.durabilityConflicts());
                boolean requirementChanged = mergeForcedRequirementModes(
                        forcedRequirementModes, discovery.lateRequirementModes());
                if (!durabilityChanged && !requirementChanged) {
                    break;
                }
            }
        } catch (GraphExportLimitExceeded ignored) {
            return CompiledGraph.empty();
        }
        return new CompiledGraph(
                graphBuild.builder.build(),
                graphBuild.multiplePaths[0],
                graphBuild.durability,
                graphBuild.patternSources,
                graphBuild.emittable);
    }

    private static final class GraphBuild {
        private final CraftGraph.Builder<AEKey> builder = CraftGraph.builder();
        private final boolean[] multiplePaths = {false};
        private final Map<AEKey, DurabilityChain<AEKey>> durability = new HashMap<>();
        private final Map<AEKey, Set<IPatternDetails>> patternSources = new HashMap<>();
        private final Set<AEKey> emittable = new HashSet<>();
    }

    private static final class CompiledGraph {
        private final CraftGraph<AEKey> graph;
        private final boolean multiplePaths;
        private final Map<AEKey, DurabilityChain<AEKey>> durability;
        private final Map<AEKey, Set<IPatternDetails>> patternSources;
        private final Set<AEKey> emittable;

        private CompiledGraph(
                CraftGraph<AEKey> graph,
                boolean multiplePaths,
                Map<AEKey, DurabilityChain<AEKey>> durability,
                Map<AEKey, Set<IPatternDetails>> patternSources,
                Set<AEKey> emittable) {
            this.graph = graph;
            this.multiplePaths = multiplePaths;
            this.durability = Map.copyOf(durability);
            Map<AEKey, Set<IPatternDetails>> frozenSources = new HashMap<>();
            patternSources.forEach((key, value) -> frozenSources.put(
                    key, java.util.Collections.unmodifiableSet(value)));
            this.patternSources = Map.copyOf(frozenSources);
            this.emittable = Set.copyOf(emittable);
        }

        private static CompiledGraph empty() {
            return new CompiledGraph(
                    CraftGraph.<AEKey>builder().build(), false, Map.of(), Map.of(), Set.of());
        }
    }

    private static final class GraphExportBudget {
        private long remaining;

        private GraphExportBudget(long work) {
            remaining = Math.max(1L, work);
        }

        private void consume() {
            PlanningCancellation.check();
            if (remaining-- <= 0L) {
                throw GraphExportLimitExceeded.INSTANCE;
            }
        }
    }

    private static final class GraphExportLimitExceeded extends RuntimeException {
        private static final GraphExportLimitExceeded INSTANCE = new GraphExportLimitExceeded();

        private GraphExportLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private static boolean noNonEmittableMissing(CraftPlan<AEKey> plan, Set<AEKey> emittable) {
        for (AEKey k : plan.missing().keySet()) {
            if (!emittable.contains(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameItem(AEKey a, AEKey b) {
        return a instanceof AEItemKey ai && b instanceof AEItemKey bi && ai.getItem() == bi.getItem();
    }

    /**
     * A planned durability step is executable only when AE2's pattern input accepts the exact
     * returned variant. In particular, a crafting pattern with substitutions disabled accepts the
     * encoded tool but rejects the damaged key returned after the first craft. Counting that key as
     * reusable stock would produce a feasible plan that the CPU can never dispatch.
     */
    static boolean acceptsDurabilityRemainder(
            IPatternDetails.IInput input, AEKey remainder, Level level) {
        return remainder != null && input.isValid(remainder, level);
    }

    /**
     * BFS the reachable recipe graph.
     *
     * @return durability conflicts plus demand modes discovered after their node was already expanded;
     *         either condition requires a clean graph rebuild
     */
    private static GraphBuildDiscovery buildGraph(ICraftingService craftingService,
                                      ChildCraftingSimulationState snapshot,
                                      ChildCraftingSimulationState reusableSeedSnapshot,
                                      Level level,
                                      AEKey root,
                                      CraftGraph.Builder<AEKey> builder,
                                      boolean[] multiplePaths,
                                      Map<AEKey, DurabilityChain<AEKey>> durability,
                                      Map<AEKey, Set<IPatternDetails>> patternSources,
                                      Set<AEKey> emittable,
                                      Set<Item> conservativeDurabilityItems,
                                      Map<AEKey, RequirementMode> forcedRequirementModes,
                                      PrimaryIdentityCraftables primaryIdentityCraftables,
                                      GraphExportBudget exportBudget,
                                      @Nullable CraftingStockPolicy reservedStock) {
        Set<AEKey> seen = new HashSet<>();
        Deque<AEKey> queue = new ArrayDeque<>();
        // Memoized "how much is already in the network" per key (SIMULATE probe), used to rank fuzzy
        // substitutes most-available-first so the bounded keep-best-32 picks the cheapest routes.
        Map<AEKey, Long> availability = new HashMap<>();
        RequirementModes requirementModes = new RequirementModes(root, forcedRequirementModes);
        Map<AEKey, Long> supplementalSelfSeedStock = new HashMap<>();
        // Unit-system bookkeeping. A durability chain prices its links in USES (carrier pool); every
        // other node is priced in whole ITEMS. The same physical stock must never be counted under
        // both systems (or under two incompatible chains), so we track:
        //   itemUnitKeys — keys stocked/consumed as whole items (BFS-polled nodes + plain inputs);
        //   linkOwner    — every link of every registered chain -> that chain (uses-priced keys).
        // linkOwner doubles as the merge index: a slot whose declared start key is a mid-chain link of
        // an already-built chain reuses that chain when their step granularities line up ("相接"),
        // instead of building a second, overlapping chain. Any overlap that ISN'T such a clean merge
        // (a link priced as a whole item, or two chains stepping the same tool at different
        // durability-per-craft granularities) is a genuine conflict. Such an item is rediscovered on
        // a clean pass using conservative whole-item semantics; the rest of the graph stays optimized.
        Set<AEKey> itemUnitKeys = new HashSet<>();
        Map<AEKey, DurabilityChain<AEKey>> linkOwner = new HashMap<>();
        Set<Item> durabilityConflicts = new HashSet<>();
        seen.add(root);
        queue.add(root);

        while (!queue.isEmpty()) {
            exportBudget.consume();
            AEKey key = queue.poll();
            requirementModes.markProcessed(key);

            // Durability carrier: a finite-use token resource. Its stock (= aggregate uses over the
            // whole chain, 链长×数量) was set once at capture, so we don't re-stock it here — but we DO
            // craft it: a normal tool is craftable, and one crafted (full) tool yields n uses, so the
            // tool's real pattern is registered with its output scaled by n (优先按链长×数量).
            DurabilityChain<AEKey> carrier = durability.get(key);
            long outputScale = 1;
            if (carrier != null) {
                outputScale = carrier.n();
            } else {
                long available = usableStock(snapshot, key, reservedStock);
                if (available > 0) {
                    builder.stock(key, available);
                }
                itemUnitKeys.add(key); // this node is priced in whole items from here on
            }

            // Emitable items (e.g. via level/energy emitters) are an infinite on-demand source: keep
            // them as a leaf with their current real stock, and treat any shortfall as emitted (handled
            // in toAe2Plan) rather than crafted. Never decline just because an item is emittable.
            if (craftingService.canEmitFor(key)) {
                emittable.add(key);
                builder.stock(key, Sat.SAT);
                continue;
            }

            Collection<IPatternDetails> patterns = craftingService.getCraftingFor(key);
            // Count only the primary-output views we actually register (see restriction below); the raw
            // getCraftingFor count would over-report "multiple paths" for secondary-output aliases.
            int registeredForKey = 0;

            for (IPatternDetails details : patterns) {
                exportBudget.consume();
                // Primary-output restriction: a pattern becomes a craft node ONLY under its declared
                // primary output. getCraftingFor(key) also returns patterns where `key` is merely a
                // SECONDARY output; if we registered those as a second node producing `key`, the same
                // real IPatternDetails would be fireable through two nodes and toAe2Plan (which merges
                // firings by source) could schedule it twice -> double-craft (over-draws stock, piles up
                // byproducts). So we skip non-primary views here: `key` is instead supplied from the
                // byproduct pool when its real primary is crafted, never by a second firing of this
                // source. Trade-off (accepted): an item that is ONLY ever a secondary output, with no
                // pattern of its own, surfaces as missing (Policy A best-effort) rather than being
                // made by deliberately over-producing the primary.
                GenericStack primaryStack = details.getPrimaryOutput();
                if (primaryStack == null || !key.equals(primaryStack.what())) {
                    continue;
                }
                if (!producerMatchesRequirement(
                        details, key, requirementModes.modeFor(key), exportBudget)) {
                    continue;
                }
                patternSources.computeIfAbsent(key,
                        ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                        .add(details);
                List<GenericStack> outputs = details.getOutputs();
                GenericStack primary = null;
                List<CraftOutput<AEKey>> byproducts = new ArrayList<>(Math.max(0, outputs.size() - 1));
                for (GenericStack out : outputs) {
                    exportBudget.consume();
                    if (primary == null && key.equals(out.what())) {
                        primary = out;
                    } else {
                        byproducts.add(CraftOutput.of(out.what(), out.amount()));
                    }
                }
                if (primary == null) {
                    continue; // defensive: primary not enumerated in getOutputs(); skip this pattern
                }

                if (details instanceof ReusableStockPattern seeded) {
                    var source = seeded.reusableStockSource();
                    var physicalVariants = seeded.availableReusableStockSnapshot();
                    for (var entry : physicalVariants.entrySet()) {
                        exportBudget.consume();
                        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
                        builder.reusableStock(
                                source.storageScope(), entry.getKey(), entry.getValue());
                    }
                    for (var requirement : seeded.reusableStockRequirements().entrySet()) {
                        exportBudget.consume();
                        if (requirement.getKey() == null || requirement.getValue() == null
                                || requirement.getValue() <= 0) continue;
                        var acceptedVariants = new ArrayList<AEKey>();
                        for (var actual : physicalVariants.keySet()) {
                            exportBudget.consume();
                            if (actual != null
                                    && seeded.acceptsReusableStockVariant(requirement.getKey(), actual)) {
                                acceptedVariants.add(actual);
                            }
                        }
                        builder.reusableStockRoute(source, requirement.getKey(), acceptedVariants);
                    }
                    // AE2 intentionally ignores pre-existing stock of the requested output. When a
                    // contracted gain loop uses that same key as its catalyst, publish only the part
                    // hidden by that ignore view. For a dependency key, its ordinary stock was
                    // already published above and CraftPlannerV2 reserves the seed from that same
                    // pool; publishing it twice would undercount loop firings by one seed batch.
                    long selfSeedRequired = seeded.reusableStockRequirements()
                            .getOrDefault(key, 0L);
                    if (selfSeedRequired > 0) {
                        long supplemental = ReusableStockFallback.supplementalSelfSeedStock(
                                selfSeedRequired,
                                usableStock(reusableSeedSnapshot, key, reservedStock),
                                usableStock(snapshot, key, reservedStock));
                        long previous = supplementalSelfSeedStock.getOrDefault(key, 0L);
                        if (supplemental > previous) {
                            builder.stock(key, supplemental - previous);
                            supplementalSelfSeedStock.put(key, supplemental);
                        }
                    }
                }

                // Per-slot acceptable concrete options for the hard-fuzzy (OR) expansion.
                IPatternDetails.IInput[] inputs = details.getInputs();
                // Resolve optional same-id closure metadata through wrappers. Concrete candidates do
                // not depend on this interface: all slots use getPossibleInputs as discovery anchors
                // and isValid as the authoritative membership test, matching AE2.
                var providerDetails = CraftingPatternDelegates.forProviderLookup(details);
                FuzzyPatternInputs overloadView =
                        providerDetails instanceof FuzzyPatternInputs op
                        ? op
                        : null;
                boolean craftingPattern = isCraftingPattern(providerDetails);
                List<List<SlotChoice>> slotOptions = new ArrayList<>(inputs.length);
                List<RequirementMode> slotRequirementModes = new ArrayList<>(inputs.length);
                long combos = 1;
                boolean patternUnsatisfiable = false;
                for (int slot = 0; slot < inputs.length; slot++) {
                    exportBudget.consume();
                    IPatternDetails.IInput in = inputs[slot];
                    // Durability semantics are inferred only for real crafting patterns. Processing
                    // patterns cannot encode component-dependent tool degradation reliably, so even a
                    // custom input that reports a same-item remainder stays outside this optimization.
                    ChainLookup lookup = craftingPattern
                            ? durabilityChain(in, craftingService, snapshot, level, builder,
                                    durability, linkOwner, itemUnitKeys, conservativeDurabilityItems,
                                    exportBudget, reservedStock)
                            : ChainLookup.NONE;
                    if (lookup.conflict()) {
                        durabilityConflicts.add(lookup.conflictItem());
                        patternUnsatisfiable = true;
                        break;
                    }
                    DurabilityChain<AEKey> chain = lookup.chain();
                    if (chain != null) {
                        // Chain "uses" per firing = tools in the slot x multiplier. One chain step
                        // already encodes this slot's per-craft durability cost (the chain was built
                        // from this slot's own getRemainingKey), so a 2-durability-per-craft recipe
                        // simply produces a chain whose length is the firings a full tool survives.
                        long usesPerFiring = Sat.mul(
                                Math.max(1, in.getPossibleInputs()[0].amount()),
                                Math.max(1, in.getMultiplier()));
                        slotOptions.add(List.of(new SlotChoice(List.of(
                                CraftInput.of(chain.carrier(), usesPerFiring)))));
                        // A durability carrier means one exact full tool. An ID_ONLY producer is
                        // late-bound and must never be priced as that full carrier.
                        slotRequirementModes.add(RequirementMode.STRICT);
                        continue; // single deterministic option, never enqueued for crafting
                    }
                    boolean sameIdClosure = overloadView != null
                            && overloadView.acceptsSameIdVariants(slot);
                    List<GenericStack> templates = inputTemplates(
                            in, snapshot, primaryIdentityCraftables, level, exportBudget);
                    templates = addConservativeDurabilityTemplates(
                            in, templates, craftingService, snapshot, level,
                            conservativeDurabilityItems, exportBudget);
                    List<CraftInput<AEKey>> opts = new ArrayList<>(templates.size());
                    for (GenericStack template : templates) {
                        exportBudget.consume();
                        AEKey inputKey = template.what();
                        if (linkOwner.containsKey(inputKey)) {
                            // Whole-item consumption of a key some chain prices in uses (its carrier
                            // OR any mid-chain damaged variant) would mix the two unit systems.
                            DurabilityChain<AEKey> owner = linkOwner.get(inputKey);
                            if (owner.carrier() instanceof AEItemKey carrierKey) {
                                durabilityConflicts.add(carrierKey.getItem());
                            }
                            patternUnsatisfiable = true;
                            break;
                        }
                        itemUnitKeys.add(inputKey);
                        AEKey remaining = in.getRemainingKey(inputKey) instanceof AEKey r ? r : null;
                        if (remaining == null) {
                            opts.add(CraftInput.of(inputKey, template.amount()));
                        } else if (remaining.equals(inputKey)) {
                            // Returned unchanged: a true catalyst / non-degrading container. One seed
                            // serves the whole batch (AE2's limitQty), modelled as a returned input.
                            if (details instanceof ReusableStockPattern seeded
                                    && seeded.reusableStockRequirements()
                                            .getOrDefault(inputKey, 0L) > 0) {
                                opts.add(CraftInput.returnedFrom(
                                        inputKey, template.amount(), seeded.reusableStockSource()));
                            } else {
                                opts.add(CraftInput.returned(inputKey, template.amount()));
                            }
                        } else if (sameItem(inputKey, remaining)) {
                            if (inputKey instanceof AEItemKey itemKey
                                    && conservativeDurabilityItems.contains(itemKey.getItem())) {
                                // Local conflict fallback: consume one concrete tool per firing and
                                // deliberately ignore its damaged return in the planning graph. Runtime
                                // still returns that tool, so this can only over-prepare tools; it cannot
                                // over-promise stock or double-count a tool between pattern semantics.
                                opts.add(CraftInput.of(inputKey, template.amount()));
                            }
                            // Otherwise durabilityChain declined it (non-crafting pattern, non-chain
                            // remainder semantics, or cyclic budget). Drop the option so this recipe
                            // safely surfaces as missing instead of inventing durability behavior.
                        } else {
                            // Different leftover item (e.g. filled bucket -> empty bucket): consume the
                            // full input and hand back the remainder as a byproduct, so refilling it
                            // closes a cycle the planner resolves instead of declining.
                            opts.add(CraftInput.consumedReturning(inputKey, template.amount(), remaining));
                        }
                    }
                    if (patternUnsatisfiable) {
                        break;
                    }
                    if (opts.isEmpty()) {
                        patternUnsatisfiable = true; // this recipe can't be fired; surfaces as missing
                        break;
                    }
                    // Rank this slot's substitutes most-available-first. When the full OR-product
                    // overruns the budget we keep only the best `FUZZY_NONCYCLE_STEPS` combinations
                    // (lowest rank-sum), so the cheapest/in-stock routes survive instead of the recipe
                    // being dropped wholesale.
                    if (opts.size() > 1) {
                        for (CraftInput<AEKey> o : opts) {
                            availability.computeIfAbsent(o.key(),
                                k -> usableStock(snapshot, k, reservedStock));
                        }
                        opts.sort(Comparator.comparingLong(
                            (CraftInput<AEKey> o) -> availability.get(o.key())).reversed());
                    }
                    List<SlotChoice> choices = expandSlotChoices(opts, in.getMultiplier(), availability);
                    slotOptions.add(choices);
                    slotRequirementModes.add(
                            sameIdClosure ? RequirementMode.ID_ONLY : RequirementMode.STRICT);
                    // Saturating product: a raw `combos *= opts.size()` can overflow Long for patterns
                    // with many fuzzy slots over large tags, wrapping to a small value that slips past
                    // the budget check below. Sat.mul clamps so the budget comparison stays correct.
                    combos = Sat.mul(combos, choices.size());
                }
                if (patternUnsatisfiable) {
                    continue; // skip this recipe; if nothing else makes `key` it surfaces as missing
                }
                if (combos > 1) {
                    multiplePaths[0] = true; // fuzzy expanded into competing recipes
                }
                // For a craftable durability tool, one firing makes one full tool = n uses.
                long outAmount = Sat.mul(primary.amount(), outputScale);
                // Keep the best (lowest rank-sum) up to FUZZY_NONCYCLE_STEPS combinations; when the
                // product is within budget this emits all of them, otherwise it greedily keeps the front.
                emitBestCombinations(
                        builder, seen, queue, key, outAmount, byproducts, slotOptions,
                        slotRequirementModes, requirementModes, details, exportBudget);
                registeredForKey++;
            }
            if (registeredForKey > 1) {
                multiplePaths[0] = true; // genuinely distinct recipes whose primary output is `key`
            }
        }
        return new GraphBuildDiscovery(
                Set.copyOf(durabilityConflicts), requirementModes.lateChanges());
    }

    /**
     * Concrete templates to consider for one input slot. Every declared possible input is a discovery
     * anchor, exactly as in AE2. For each anchor we inspect same-primary stocked and craftable variants,
     * then retain only keys accepted by {@link IPatternDetails.IInput#isValid}. The declared templates
     * remain first so their own producer routes retain precedence. This deliberately does not consult
     * {@link FuzzyPatternInputs}: arbitrary accepted-key sets work through the native {@code IInput}
     * contract, while the optional fuzzy declaration is reserved for late-bound output compatibility.
     */
    private static List<GenericStack> inputTemplates(
            IPatternDetails.IInput in,
            ChildCraftingSimulationState snapshot,
            PrimaryIdentityCraftables craftableIndex,
            Level level,
            GraphExportBudget exportBudget) {
        return expandInputTemplates(
                in,
                snapshot::findFuzzyTemplates,
                craftableIndex::get,
                level,
                exportBudget);
    }

    static List<GenericStack> expandInputTemplates(
            IPatternDetails.IInput in,
            Function<AEKey, ? extends Iterable<AEKey>> stockedVariants,
            Function<AEKey, ? extends Iterable<AEKey>> craftableVariants,
            Level level) {
        return expandInputTemplates(
                in, stockedVariants, craftableVariants, level, null);
    }

    private static List<GenericStack> expandInputTemplates(
            IPatternDetails.IInput in,
            Function<AEKey, ? extends Iterable<AEKey>> stockedVariants,
            Function<AEKey, ? extends Iterable<AEKey>> craftableVariants,
            Level level,
            @Nullable GraphExportBudget exportBudget) {
        GenericStack[] possible = in.getPossibleInputs();
        LinkedHashMap<AEKey, GenericStack> byKey = new LinkedHashMap<>();
        for (GenericStack template : possible) {
            consumeExportWork(exportBudget);
            if (template == null || template.what() == null) {
                continue;
            }
            byKey.putIfAbsent(template.what(), template);
            var stocked = stockedVariants.apply(template.what());
            if (stocked != null) {
                for (AEKey candidate : stocked) {
                    consumeExportWork(exportBudget);
                    if (candidate != null && in.isValid(candidate, level)) {
                        byKey.putIfAbsent(
                                candidate, new GenericStack(candidate, template.amount()));
                    }
                }
            }
            AEKey identity = template.what().dropSecondary();
            var craftables = craftableVariants.apply(identity);
            if (craftables != null) {
                for (AEKey candidate : craftables) {
                    consumeExportWork(exportBudget);
                    if (candidate != null && in.isValid(candidate, level)) {
                        byKey.putIfAbsent(
                                candidate, new GenericStack(candidate, template.amount()));
                    }
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static void consumeExportWork(@Nullable GraphExportBudget exportBudget) {
        if (exportBudget != null) exportBudget.consume();
        else PlanningCancellation.check();
    }

    private static boolean producerMatchesRequirement(
            IPatternDetails details,
            AEKey requestedKey,
            RequirementMode requirementMode,
            GraphExportBudget exportBudget) {
        var providerDetails = CraftingPatternDelegates.forProviderLookup(details);
        if (!(providerDetails instanceof FuzzyPatternInputs overload)) {
            return true;
        }
        var outputs = providerDetails.getOutputs();
        for (int slot = 0; slot < outputs.size(); slot++) {
            exportBudget.consume();
            var output = outputs.get(slot);
            if (output != null && requestedKey.equals(output.what())) {
                return PatternInputMatchPolicy.accepts(
                        new GenericStack[] {new GenericStack(requestedKey, 1)},
                        requirementMode.acceptsIdOnlyOutput(),
                        output.what(),
                        overload.producesSameIdVariants(slot));
            }
        }
        return true;
    }

    enum RequirementMode {
        STRICT,
        ID_ONLY,
        MIXED;

        boolean acceptsIdOnlyOutput() {
            return this == ID_ONLY;
        }

        static RequirementMode merge(RequirementMode left, RequirementMode right) {
            return left == right ? left : MIXED;
        }
    }

    private record GraphBuildDiscovery(
            Set<Item> durabilityConflicts,
            Map<AEKey, RequirementMode> lateRequirementModes) {
    }

    /**
     * Tracks the demand capability attached to each concrete graph key. If a key has already been
     * expanded and a later edge tightens its mode, the current graph is discarded and rebuilt with
     * that mode forced from the start. This makes producer eligibility independent of BFS order.
     */
    static final class RequirementModes {
        private final Map<AEKey, RequirementMode> modes;
        private final Set<AEKey> processed = new HashSet<>();
        private final Map<AEKey, RequirementMode> lateChanges = new HashMap<>();

        RequirementModes(AEKey root, Map<AEKey, RequirementMode> forcedModes) {
            modes = new HashMap<>(forcedModes);
            require(root, RequirementMode.STRICT);
        }

        RequirementMode modeFor(AEKey key) {
            return modes.getOrDefault(key, RequirementMode.STRICT);
        }

        void markProcessed(AEKey key) {
            processed.add(key);
        }

        void require(AEKey key, RequirementMode requested) {
            var previous = modes.get(key);
            var merged = previous == null ? requested : RequirementMode.merge(previous, requested);
            if (merged == previous) {
                return;
            }
            modes.put(key, merged);
            if (processed.contains(key)) {
                lateChanges.merge(key, merged, RequirementMode::merge);
            }
        }

        Map<AEKey, RequirementMode> lateChanges() {
            return Map.copyOf(lateChanges);
        }
    }

    /** Builds one same-primary-identity index from AE2's full craftable snapshot, lazily. */
    static final class PrimaryIdentityCraftables {
        private final Supplier<? extends Iterable<AEKey>> source;
        private final GraphExportBudget exportBudget;
        private Map<AEKey, List<AEKey>> byIdentity;

        PrimaryIdentityCraftables(Supplier<? extends Iterable<AEKey>> source) {
            this(source, null);
        }

        private PrimaryIdentityCraftables(
                Supplier<? extends Iterable<AEKey>> source,
                @Nullable GraphExportBudget exportBudget) {
            this.source = source;
            this.exportBudget = exportBudget;
        }

        List<AEKey> get(AEKey identity) {
            if (byIdentity == null) {
                var mutable = new LinkedHashMap<AEKey, List<AEKey>>();
                var craftables = source.get();
                if (craftables != null) {
                    for (var craftable : craftables) {
                        if (exportBudget != null) exportBudget.consume();
                        else PlanningCancellation.check();
                        if (craftable == null) continue;
                        mutable.computeIfAbsent(
                                craftable.dropSecondary(), ignored -> new ArrayList<>()).add(craftable);
                    }
                }
                var frozen = new HashMap<AEKey, List<AEKey>>();
                for (var entry : mutable.entrySet()) {
                    frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
                byIdentity = Map.copyOf(frozen);
            }
            return byIdentity.getOrDefault(identity, List.of());
        }
    }

    private static boolean mergeForcedRequirementModes(
            Map<AEKey, RequirementMode> forced,
            Map<AEKey, RequirementMode> discovered) {
        boolean changed = false;
        for (var entry : discovered.entrySet()) {
            PlanningCancellation.check();
            var previous = forced.get(entry.getKey());
            var merged = previous == null
                    ? entry.getValue() : RequirementMode.merge(previous, entry.getValue());
            if (merged != previous) {
                forced.put(entry.getKey(), merged);
                changed = true;
            }
        }
        return changed;
    }

    static boolean isCraftingPattern(IPatternDetails details) {
        var current = details;
        var visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<IPatternDetails, Boolean>());
        while (current instanceof IWrappedPatternDetails wrapped) {
            PlanningCancellation.check();
            if (!visited.add(current)) {
                return false;
            }
            current = wrapped.wrappedPatternDetails();
            if (current == null) {
                return false;
            }
        }
        return current instanceof AECraftingPattern;
    }

    /**
     * Add concrete, currently stocked/craftable variants for durability items whose optimized chain
     * representation conflicted elsewhere in the graph. Each variant remains an ordinary whole-item
     * option, so all patterns share the same physical stock instead of receiving private token pools.
     */
    private static List<GenericStack> addConservativeDurabilityTemplates(
            IPatternDetails.IInput in,
            List<GenericStack> base,
            ICraftingService craftingService,
            ChildCraftingSimulationState snapshot,
            Level level,
            Set<Item> conservativeDurabilityItems,
            GraphExportBudget exportBudget) {
        if (conservativeDurabilityItems.isEmpty()) {
            return base;
        }
        Map<AEKey, GenericStack> byKey = new LinkedHashMap<>();
        for (GenericStack template : base) {
            exportBudget.consume();
            byKey.putIfAbsent(template.what(), template);
        }
        for (GenericStack possible : in.getPossibleInputs()) {
            exportBudget.consume();
            if (!(possible.what() instanceof AEItemKey template)
                    || !conservativeDurabilityItems.contains(template.getItem())) {
                continue;
            }
            Item item = template.getItem();
            if (craftingService.getFuzzyCraftable(template, k -> in.isValid(k, level))
                    instanceof AEItemKey craftable
                    && craftable.getItem() == item) {
                byKey.putIfAbsent(craftable, new GenericStack(craftable, possible.amount()));
            }
            for (AEKey variant : snapshot.findFuzzyTemplates(template)) {
                exportBudget.consume();
                if (variant instanceof AEItemKey itemVariant
                        && itemVariant.getItem() == item
                        && in.isValid(variant, level)) {
                    byKey.putIfAbsent(variant, new GenericStack(variant, possible.amount()));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Result of a durability-slot probe: a usable chain, nothing, or an item-local unit conflict. */
    private record ChainLookup(
            @Nullable DurabilityChain<AEKey> chain,
            @Nullable Item conflictItem) {
        static final ChainLookup NONE = new ChainLookup(null, null);

        static ChainLookup conflict(Item item) {
            return new ChainLookup(null, item);
        }

        boolean conflict() {
            return conflictItem != null;
        }
    }

    /**
     * Detect a durability-tool input from an AE2 crafting pattern and capture its degradation chain
     * once, delegating the chain building / reduction to the engine ({@link DurabilityChain}). Callers
     * must not invoke this for processing or other pattern kinds. A chain is accepted only when this
     * input's remainder stays on the same item id; null, unchanged, and different-id remainders are not
     * durability semantics.
     *
     * <p>The only AE2-specific bits are the two lambdas: {@code remaining} follows
     * {@code getRemainingKey} but returns {@code null} when the step leaves the tool's own {@link Item}
     * (a container like a bucket degrades into a <em>different</em> item → not durability), and
     * {@code stock} probes each exact variant's count (so partial tools are counted). The aggregate uses
     * become the carrier's stock in the graph.
     *
     * <p><b>Chains are matched to SLOT semantics, not just to the item.</b> {@code getRemainingKey} is
     * per-input: one recipe may cost 1 durability per craft, another 2. The chain is built by stepping
     * <em>this slot's</em> rule, so a 2-per-craft slot yields a chain whose length is the firings a full
     * tool survives — self-consistent on its own.
     *
     * <p><b>Anchoring at the fullest tool.</b> A slot's declared template need not be the full tool: an
     * overload pattern is captured from real items the player inserts, so its template is frequently a
     * <em>partially used</em> tool, while the tool that will actually refill the pool is crafted brand
     * new (full). Building the chain straight from a damaged template would miss the fuller variants
     * above it and, worse, never discover the tool's own craft recipe (that recipe's output is the full
     * key, which nothing else would poll). {@link #fullestAnchor} therefore relocates the anchor to the
     * fullest same-item variant the slot accepts (craftable variant first, à la AE2's
     * {@code CraftingTreeNode.findCraftedStack}, otherwise the longest-downward-walk stock variant). That
     * makes the fullest tool the single carrier for every slot on the orbit, so discovery order stops
     * mattering and we never need to re-anchor a chain backward.
     *
     * <p><b>Merge by connectivity ("相接"), not by item identity.</b> {@code linkOwner} indexes every
     * link of every already-built chain back to that chain. When a slot's (anchored) start key is
     * already a link of an existing chain we <em>reuse</em> that chain — but only when this slot's own
     * step from that link lands on the chain's <em>next</em> link, i.e. the two share the same
     * durability-per-craft granularity and lie on one orbit. That is precisely the case where two slots
     * start at different damage levels of the same tool yet connect into one chain, so pooling their
     * stock once is sound. If the step lands elsewhere (e.g. a 2-per-craft slot meeting a 1-per-craft
     * chain), the same physical tools would be priced in two incompatible "uses" units on one pool with
     * no sound linear split, so it reports an item-local conflict. The graph is then rebuilt with that
     * item represented as concrete whole-item inputs; unrelated durability items retain their reduced
     * chains. The same conflict is reported when a fresh chain's links overlap keys already priced as
     * whole items ({@code itemUnitKeys}) or links owned by another chain.
     *
     * <p>Returns {@link ChainLookup#NONE} when this slot is not a reducible durability tool (plain
     * item, container, or a chain longer than {@link #FUZZY_CYCLE_STEPS}).
     */
    private static ChainLookup durabilityChain(IPatternDetails.IInput in,
                                               ICraftingService craftingService,
                                               ChildCraftingSimulationState snapshot,
                                               Level level,
                                               CraftGraph.Builder<AEKey> builder,
                                               Map<AEKey, DurabilityChain<AEKey>> registry,
                                               Map<AEKey, DurabilityChain<AEKey>> linkOwner,
                                               Set<AEKey> itemUnitKeys,
                                               Set<Item> conservativeDurabilityItems,
                                               GraphExportBudget exportBudget,
                                               @Nullable CraftingStockPolicy reservedStock) {
        GenericStack[] possible = in.getPossibleInputs();
        if (possible.length == 0 || !(possible[0].what() instanceof AEItemKey template)) {
            return ChainLookup.NONE;
        }
        Item item = template.getItem();
        if (conservativeDurabilityItems.contains(item)) {
            return ChainLookup.NONE;
        }
        AEItemKey full = fullestAnchor(
                in, craftingService, snapshot, level, template, item, exportBudget);
        AEKey remaining = in.getRemainingKey(full);
        // Classify the resolved craftable/stock anchor, not the declared template. AE2 permits a
        // pattern to accidentally encode a damaged output for a damageable input; in that case the
        // pristine declared template may appear unchanged while the fuzzy craftable variant starts a
        // real degradation chain. Returning before fullestAnchor would miss that pattern entirely.
        if (full.equals(remaining)) {
            return ChainLookup.NONE; // true unbreakable / non-degrading catalyst
        }
        // One degradation step under THIS slot's rule (null = consumed outright / leaves the item).
        AEKey step = remaining instanceof AEItemKey next && next.getItem() == item ? next : null;
        if (step != null && !acceptsDurabilityRemainder(in, step, level)) {
            // The recipe returns a damaged tool, but this strict pattern cannot consume it again.
            // Rebuild this item with conservative whole-item semantics instead of promising a
            // durability pool whose physical variants the CPU will reject during extraction.
            return ChainLookup.conflict(item);
        }

        // Merge index: is this slot's start key already a link of some built chain?
        DurabilityChain<AEKey> owner = linkOwner.get(full);
        if (owner != null) {
            if (step == null) {
                return ChainLookup.NONE; // plain consumption; the caller's linkOwner check declines
            }
            // "相接": stepping this slot's rule from `full` must land on the chain's next link, i.e.
            // both step at the same durability-per-craft granularity along the same orbit.
            List<AEKey> links = owner.links();
            int idx = links.indexOf(full);
            AEKey chainNext = idx >= 0 && idx + 1 < links.size() ? links.get(idx + 1) : null;
            return step.equals(chainNext)
                    ? new ChainLookup(owner, null)
                    : ChainLookup.conflict(item); // same tool link, different per-craft granularity
        }
        if (step == null) {
            return ChainLookup.NONE;
        }

        DurabilityChain<AEKey> chain = DurabilityChain.build(
                full,
                k -> {
                    exportBudget.consume();
                    return in.getRemainingKey(k) instanceof AEItemKey next
                            && next.getItem() == item
                            && acceptsDurabilityRemainder(in, next, level) ? next : null;
                },
                k -> {
                    exportBudget.consume();
                    return usableStock(snapshot, k, reservedStock);
                },
                FUZZY_CYCLE_STEPS);
        if (chain == null) {
            return ChainLookup.NONE;
        }
        for (AEKey link : chain.links()) {
            exportBudget.consume();
            if (itemUnitKeys.contains(link) || linkOwner.containsKey(link)) {
                return ChainLookup.conflict(item); // already counted under another unit system / chain
            }
        }
        registry.put(chain.carrier(), chain); // carrier == full == links[0]
        for (AEKey link : chain.links()) {
            exportBudget.consume();
            linkOwner.put(link, chain);
        }
        builder.stock(full, chain.totalUses()); // carrier stock = aggregate uses (set once)
        return new ChainLookup(chain, null);
    }

    private static long usableStock(
            ChildCraftingSimulationState snapshot,
            AEKey key,
            @Nullable CraftingStockPolicy reservedStock) {
        long actual = Math.max(0L, snapshot.extract(key, Long.MAX_VALUE, Actionable.SIMULATE));
        if (reservedStock == null) return actual;
        if (reservedStock.groupsSecondaryVariants(key)) {
            var group = new LinkedHashMap<AEKey, Long>();
            group.put(key, actual);
            for (AEKey variant : snapshot.findFuzzyTemplates(key)) {
                PlanningCancellation.check();
                if (!key.dropSecondary().equals(variant.dropSecondary())) continue;
                long amount = Math.max(0L,
                        snapshot.extract(variant, Long.MAX_VALUE, Actionable.SIMULATE));
                group.put(variant, amount);
            }
            return Math.max(0L, Math.min(actual,
                    reservedStock.usablePreexistingStock(key, actual, Map.copyOf(group))));
        }
        return Math.max(0L, Math.min(actual, reservedStock.usablePreexistingStock(key, actual)));
    }

    /**
     * Pick the fullest (most-uses-remaining) same-item variant this slot accepts, to anchor its chain.
     *
     * <p>The relative durability of two variants is decided <em>structurally</em> — the one whose
     * downward {@code getRemainingKey} walk is longer takes more crafts to break, so it has more uses
     * left — never by reading a damage value (modded tools may not use vanilla durability at all).
     *
     * <p>A craftable variant, when present, is the absolute fullest: a freshly crafted tool is at max
     * durability, so no stock variant can beat it. So we take AE2's {@code getFuzzyCraftable} answer
     * directly when it exists (and skip the potentially expensive stock scan). Only when nothing
     * craftable is valid for the slot do we widen the search to same-item stock variants (fuzzy,
     * ignore-NBT) and the declared template, keeping the one with the longest downward walk. Any variant
     * that turns out to sit off this slot's step lattice simply isn't reached by the anchor's walk and
     * is left uncounted — an undercount, which is safe (it can only over-report missing, never
     * over-promise).
     */
    private static AEItemKey fullestAnchor(IPatternDetails.IInput in, ICraftingService craftingService,
                                           ChildCraftingSimulationState snapshot, Level level,
                                           AEItemKey template, Item item,
                                           GraphExportBudget exportBudget) {
        if (craftingService.getFuzzyCraftable(template, k -> in.isValid(k, level)) instanceof AEItemKey craftable
                && craftable.getItem() == item) {
            return craftable; // craftable == full durability == absolute fullest; no scan needed
        }
        AEItemKey anchor = template;
        long best = downwardLength(in, template, item, level, exportBudget);
        for (AEKey variant : snapshot.findFuzzyTemplates(template)) {
            exportBudget.consume();
            if (!(variant instanceof AEItemKey ik) || ik.getItem() != item || ik.equals(anchor)
                    || !in.isValid(ik, level)) {
                continue;
            }
            long len = downwardLength(in, ik, item, level, exportBudget);
            if (len > best) {
                best = len;
                anchor = ik;
            }
        }
        return anchor;
    }

    /** Number of links on {@code from}'s downward chain (uses left + 1), capped at the cyclic budget. */
    private static long downwardLength(
            IPatternDetails.IInput in,
            AEItemKey from,
            Item item,
            Level level,
            GraphExportBudget exportBudget) {
        long len = 1;
        Set<AEKey> guard = new HashSet<>();
        AEKey cur = from;
        guard.add(cur);
        while (in.getRemainingKey(cur) instanceof AEItemKey next
                && next.getItem() == item
                && acceptsDurabilityRemainder(in, next, level)
                && guard.add(next)) {
            exportBudget.consume();
            cur = next;
            if (++len > FUZZY_CYCLE_STEPS) {
                break;
            }
        }
        return len;
    }

    /**
     * Emit up to {@link #FUZZY_NONCYCLE_STEPS} {@link CraftPattern}s for the per-slot substitute options,
     * choosing the lowest rank-sum (most-available-first) combinations when the full cartesian product
     * exceeds the budget. All share the same {@code source} {@link IPatternDetails} (so AE2 fires the one
     * real pattern and resolves the fuzzy slot from whatever the plan charged as used); the v2 planner
     * treats them as competing recipes and picks per availability.
     */
    private static void emitBestCombinations(
            CraftGraph.Builder<AEKey> builder,
            Set<AEKey> seen,
            Deque<AEKey> queue,
            AEKey key,
            long outputAmount,
            List<CraftOutput<AEKey>> byproducts,
            List<List<SlotChoice>> slotOptions,
            List<RequirementMode> slotRequirementModes,
            RequirementModes requirementModes,
            IPatternDetails source,
            GraphExportBudget exportBudget) {
        for (List<SlotChoice> selectedSlots
                : BoundedCombinations.bestFirst(slotOptions, (int) FUZZY_NONCYCLE_STEPS)) {
            exportBudget.consume();
            List<CraftInput<AEKey>> coreInputs = new ArrayList<>();
            for (int slot = 0; slot < selectedSlots.size(); slot++) {
                SlotChoice selected = selectedSlots.get(slot);
                RequirementMode mode = slotRequirementModes.get(slot);
                for (var input : selected.inputs()) {
                    requirementModes.require(input.key(), mode);
                }
                coreInputs.addAll(selected.inputs());
            }
            // A container option (consume full, return empty) contributes its leftover as a byproduct of
            // THIS combination, so we copy the shared byproduct list and append per-chosen-option leftovers.
            List<CraftOutput<AEKey>> combo = byproducts;
            for (CraftInput<AEKey> opt : coreInputs) {
                if (seen.add(opt.key())) {
                    queue.add(opt.key());
                }
                if (opt.remainder() != null) {
                    requirementModes.require(opt.remainder(), RequirementMode.STRICT);
                    if (combo == byproducts) {
                        combo = new ArrayList<>(byproducts);
                    }
                    combo.add(CraftOutput.of(opt.remainder(), opt.amount()));
                    if (seen.add(opt.remainder())) {
                        queue.add(opt.remainder());
                    }
                }
            }
            builder.pattern(new CraftPattern<>(key, outputAmount, coreInputs, combo, source));
        }
    }

    /** One concrete integer allocation of a fuzzy slot across its accepted substitutes. */
    private record SlotChoice(List<CraftInput<AEKey>> inputs) {
        private SlotChoice {
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * Expands a slot multiplier into bounded integer partitions across its concrete substitutes.
     * AE2 may satisfy one slot with a mixture (for example two acacia, one birch and one oak plank),
     * whereas treating every substitute as an all-or-nothing recipe incorrectly requires all units
     * to come from one key. Pure allocations are retained and mixed allocations are ranked by how
     * many firings the current network stock can immediately support.
     */
    private static List<SlotChoice> expandSlotChoices(
            List<CraftInput<AEKey>> options,
            long multiplier,
            Map<AEKey, Long> availability) {
        long units = Math.max(1, multiplier);
        if (options.size() == 1) {
            return List.of(new SlotChoice(List.of(scaleInput(options.get(0), units))));
        }

        int limit = (int) FUZZY_NONCYCLE_STEPS;
        List<long[]> allocations = new ArrayList<>(limit);

        // Always retain every pure route that fits the budget.
        for (int i = 0; i < options.size() && allocations.size() < limit; i++) {
            long[] counts = new long[options.size()];
            counts[i] = units;
            allocations.add(counts);
        }

        // Then add mixed partitions, best substitute first. Enumeration is capped, so even very
        // large multipliers or tags cannot make graph construction unbounded.
        enumerateMixedAllocations(options.size(), 0, units, new long[options.size()], allocations, limit);

        List<SlotChoice> choices = new ArrayList<>(allocations.size());
        for (long[] allocation : allocations) {
            List<CraftInput<AEKey>> inputs = new ArrayList<>();
            for (int i = 0; i < allocation.length; i++) {
                if (allocation[i] > 0) {
                    inputs.add(scaleInput(options.get(i), allocation[i]));
                }
            }
            choices.add(new SlotChoice(inputs));
        }
        choices.sort(Comparator.comparingLong(
                (SlotChoice choice) -> immediatelySupportedFirings(choice, availability)).reversed());
        return choices;
    }

    private static void enumerateMixedAllocations(
            int optionCount,
            int index,
            long remaining,
            long[] counts,
            List<long[]> out,
            int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (index == optionCount - 1) {
            counts[index] = remaining;
            int nonZero = 0;
            for (long count : counts) {
                if (count > 0) nonZero++;
            }
            if (nonZero > 1) {
                out.add(Arrays.copyOf(counts, counts.length));
            }
            return;
        }
        for (long count = remaining; count >= 0 && out.size() < limit; count--) {
            counts[index] = count;
            enumerateMixedAllocations(optionCount, index + 1, remaining - count, counts, out, limit);
            if (count == 0) break; // avoid long underflow
        }
        counts[index] = 0;
    }

    private static CraftInput<AEKey> scaleInput(CraftInput<AEKey> input, long units) {
        return new CraftInput<>(
                input.key(), Sat.mul(input.amount(), units), input.returned(), input.uses(),
                input.remainder(), input.reusableStockSource());
    }

    private static long immediatelySupportedFirings(
            SlotChoice choice,
            Map<AEKey, Long> availability) {
        long supported = Sat.SAT;
        for (CraftInput<AEKey> input : choice.inputs()) {
            supported = Math.min(supported,
                    input.firingsFrom(availability.getOrDefault(input.key(), 0L)));
        }
        return supported;
    }

    private static CraftingPlan toAe2Plan(AEKey output, long amount, CraftPlan<AEKey> plan,
                                          boolean multiplePaths, boolean simulation,
                                          Map<AEKey, DurabilityChain<AEKey>> durability,
                                          Map<AEKey, Set<IPatternDetails>> patternSources,
                                          Set<AEKey> emittable,
                                          ChildCraftingSimulationState snapshot,
                                          @Nullable CraftingStockPolicy reservedStock) {
        // Several CraftPatterns may share one IPatternDetails (fuzzy combos / multi-output nodes), so
        // accumulate firing counts per real pattern.
        Map<IPatternDetails, Long> patternTimes = new HashMap<>();
        for (Map.Entry<CraftPattern<AEKey>, Long> e : plan.firings().entrySet()) {
            PlanningCancellation.check();
            patternTimes.merge((IPatternDetails) e.getKey().source(), e.getValue(), Sat::add);
        }

        KeyCounter usedItems = new KeyCounter();
        KeyCounter emittedItems = new KeyCounter();
        for (Map.Entry<AEKey, Long> e : plan.usedStock().entrySet()) {
            PlanningCancellation.check();
            if (emittable.contains(e.getKey())) {
                emittedItems.add(e.getKey(), e.getValue());
                continue;
            }
            DurabilityChain<AEKey> chain = durability.get(e.getKey());
            if (chain == null) {
                usedItems.add(e.getKey(), e.getValue());
            } else {
                // tokens drawn from stock -> real tools, most-degraded first
                chain.chargeFromStock(e.getValue(), usedItems::add);
            }
        }

        // AE2 extracts fuzzy-slot stock before it decides how much of the remainder must be crafted.
        // Preserve that observable behavior: charge any still-unused accepted stock up to the slot's
        // aggregate demand, even when the compact planner found a more economical concrete mix.
        chargeAvailableFuzzyStock(plan, usedItems, snapshot, reservedStock);

        KeyCounter missingItems = new KeyCounter();
        for (Map.Entry<AEKey, Long> e : plan.missing().entrySet()) {
            PlanningCancellation.check();
            if (emittable.contains(e.getKey())) {
                emittedItems.add(e.getKey(), e.getValue()); // emitter supplies the shortfall on demand
                continue;
            }
            DurabilityChain<AEKey> chain = durability.get(e.getKey());
            if (chain == null) {
                // CraftPlan.missing contains only the concrete route retained by the planner. A key
                // accepted by a fuzzy slot must therefore remain visible even when another accepted
                // candidate happens to be produced by some fired pattern. Suppressing the whole slot
                // here could otherwise export simulation=true with an empty missingItems counter.
                missingItems.add(e.getKey(), e.getValue());
            } else {
                // Missing uses become full tools to craft/supply: ceil(uses / n).
                missingItems.add(chain.carrier(), Sat.ceilDiv(e.getValue(), chain.n()));
            }
        }

        long bytes = computeBytes(plan, durability, patternSources);

        return new CraftingPlan(
                new GenericStack(output, amount),
                bytes,
                simulation,
                multiplePaths,
                usedItems,
                emittedItems,
                missingItems,
                patternTimes);
    }

    private static void chargeAvailableFuzzyStock(
            CraftPlan<AEKey> plan,
            KeyCounter usedItems,
            ChildCraftingSimulationState snapshot,
            @Nullable CraftingStockPolicy reservedStock) {
        Map<IPatternDetails, Long> sourceFirings = new HashMap<>();
        for (var entry : plan.firings().entrySet()) {
            sourceFirings.merge((IPatternDetails) entry.getKey().source(), entry.getValue(), Sat::add);
        }
        for (var sourceEntry : sourceFirings.entrySet()) {
            PlanningCancellation.check();
            long times = sourceEntry.getValue();
            for (IPatternDetails.IInput slot : sourceEntry.getKey().getInputs()) {
                GenericStack[] possible = slot.getPossibleInputs();
                if (possible.length <= 1) continue;
                long remainingUnits = Sat.mul(times, Math.max(1, slot.getMultiplier()));
                for (GenericStack option : possible) {
                    long unitAmount = Math.max(1, option.amount());
                    remainingUnits = Math.max(0, remainingUnits - usedItems.get(option.what()) / unitAmount);
                }
                for (GenericStack option : possible) {
                    if (remainingUnits <= 0) break;
                    long unitAmount = Math.max(1, option.amount());
                    long alreadyUsed = usedItems.get(option.what());
                    long availableStock = usableStock(snapshot, option.what(), reservedStock);
                    long unusedStock = Math.max(0, availableStock - alreadyUsed);
                    long extraUnits = Math.min(remainingUnits, unusedStock / unitAmount);
                    if (extraUnits > 0) {
                        usedItems.add(option.what(), Sat.mul(extraUnits, unitAmount));
                        remainingUnits -= extraUnits;
                    }
                }
            }
        }
    }

    /**
     * Reproduces AE2's byte total: {@code addStackBytes} per requested node
     * ({@code items / amountPerByte * 8}), plus one byte per pattern firing, one byte per returned
     * container firing, and {@code 8 * nodeCount}.
     */
    private static long computeBytes(CraftPlan<AEKey> plan,
                                     Map<AEKey, DurabilityChain<AEKey>> durability,
                                     Map<AEKey, Set<IPatternDetails>> patternSources) {
        double bytes = 0;
        for (Map.Entry<AEKey, Long> e : plan.grossDemand().entrySet()) {
            PlanningCancellation.check();
            int amountPerByte = Math.max(1, e.getKey().getType().getAmountPerByte());
            // AE2 charges a node request for every requested input unit. A durability carrier is
            // represented in use-units in the graph, which is exactly the number of requests made by
            // the parent process; do not collapse it to the number of physical tools crafted here.
            bytes += (double) e.getValue() / amountPerByte * 8.0;
        }
        for (long times : plan.firings().values()) {
            bytes += times;
        }
        for (Map.Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
            PlanningCancellation.check();
            long times = entry.getValue();
            for (CraftInput<AEKey> input : entry.getKey().inputs()) {
                if (input.returned() || input.remainder() != null || durability.containsKey(input.key())) {
                    bytes += (double) Sat.mul(times, input.amount());
                }
            }
        }
        long nodeCount = plan.grossDemand().size() - fuzzyNodeReduction(plan);
        nodeCount = Sat.add(nodeCount, unusedAlternativeNodeCount(plan, patternSources));
        bytes += 8.0 * Math.max(0, nodeCount);
        return (long) Math.ceil(bytes);
    }

    /**
     * AE2 creates every sibling {@code CraftingTreeProcess} when a craftable node is expanded, so the
     * input nodes of an unused alternative still occupy eight bytes each. The compact planner keeps
     * only demanded keys in {@code grossDemand}; restore those sibling nodes without defeating AE2's
     * lazy behavior for nodes satisfied entirely from stock (which have no selected source at all).
     */
    private static long unusedAlternativeNodeCount(
            CraftPlan<AEKey> plan,
            Map<AEKey, Set<IPatternDetails>> patternSources) {
        Map<AEKey, Set<IPatternDetails>> selectedByOutput = new HashMap<>();
        for (Map.Entry<CraftPattern<AEKey>, Long> entry : plan.firings().entrySet()) {
            if (entry.getValue() <= 0) continue;
            CraftPattern<AEKey> pattern = entry.getKey();
            selectedByOutput.computeIfAbsent(pattern.output(),
                    ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))
                    .add((IPatternDetails) pattern.source());
        }

        long extra = 0;
        for (Map.Entry<AEKey, Set<IPatternDetails>> entry : selectedByOutput.entrySet()) {
            PlanningCancellation.check();
            Set<IPatternDetails> selected = entry.getValue();
            for (IPatternDetails source : patternSources.getOrDefault(entry.getKey(), Set.of())) {
                if (!selected.contains(source)) {
                    extra = Sat.add(extra, source.getInputs().length);
                }
            }
        }
        return extra;
    }

    private static long fuzzyNodeReduction(CraftPlan<AEKey> plan) {
        long reduction = 0;
        Set<IPatternDetails> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (CraftPattern<AEKey> pattern : plan.firings().keySet()) {
            PlanningCancellation.check();
            IPatternDetails source = (IPatternDetails) pattern.source();
            if (!visited.add(source)) continue;
            for (IPatternDetails.IInput slot : source.getInputs()) {
                Set<AEKey> present = new HashSet<>();
                for (GenericStack possible : slot.getPossibleInputs()) {
                    if (plan.grossDemand().containsKey(possible.what())) {
                        present.add(possible.what());
                    }
                }
                reduction += Math.max(0, present.size() - 1L);
            }
        }
        return reduction;
    }
}
