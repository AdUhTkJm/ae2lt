package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.DecisionStrategyProto;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;

/**
 * Small bootstrap-type-only boundary loaded with the downloaded OR-Tools runtime.
 *
 * <p>Do not expose Thunderbolt, OR-Tools, or protobuf types in this class's public methods: the
 * caller intentionally lives in a different class loader.</p>
 */
public final class CpSatBridge {
    public static final long SOLVED = 0L;
    public static final long INFEASIBLE = 1L;
    public static final long MODEL_INVALID = 2L;
    public static final long UNKNOWN = 3L;

    private CpSatBridge() {
    }

    public static void initialize() {
        Loader.loadNativeLibraries();
    }

    /** Returns {@code [status, branches, x0, x1, ...]}. */
    public static long[] solve(
            long[][] coefficients,
            long[] minimums,
            long[] upperBounds,
            int executionVariableCount,
            long[][] stockUseNetCoefficients,
            long[] stockUseOffsets,
            long[] stockUseUpperBounds,
            int[] stockUseDistances,
            int[] directUsedVariables,
            int[] directUsedDistances,
            double maxSeconds) {
        int variableCount = upperBounds.length;
        int stockUseCount = stockUseOffsets.length;
        if (minimums.length != coefficients.length
                || executionVariableCount < 0
                || executionVariableCount > variableCount
                || stockUseNetCoefficients.length != stockUseCount
                || stockUseUpperBounds.length != stockUseCount
                || stockUseDistances.length != stockUseCount
                || directUsedVariables.length != directUsedDistances.length) {
            return new long[] {MODEL_INVALID, 0L};
        }
        var model = new CpModel();
        var variables = new IntVar[variableCount];
        for (int variable = 0; variable < variableCount; variable++) {
            if (upperBounds[variable] < 0L) return new long[] {MODEL_INVALID, 0L};
            variables[variable] = model.newIntVar(0L, upperBounds[variable], "x_" + variable);
        }
        for (int row = 0; row < coefficients.length; row++) {
            if (coefficients[row].length != variableCount) {
                return new long[] {MODEL_INVALID, 0L};
            }
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(variables, coefficients[row]), minimums[row]);
        }

        var allUsed = new java.util.ArrayList<IntVar>();
        for (int row = 0; row < stockUseCount; row++) {
            if (stockUseNetCoefficients[row].length != variableCount
                    || stockUseUpperBounds[row] < 0L
                    || stockUseDistances[row] < 0) {
                return new long[] {MODEL_INVALID, 0L};
            }
            IntVar used = model.newIntVar(
                    0L, stockUseUpperBounds[row], "used_" + row);
            IntVar[] stockUseVariables = new IntVar[variableCount + 1];
            long[] stockUseCoefficients = new long[variableCount + 1];
            stockUseVariables[0] = used;
            stockUseCoefficients[0] = 1L;
            System.arraycopy(variables, 0, stockUseVariables, 1, variableCount);
            System.arraycopy(
                    stockUseNetCoefficients[row],
                    0,
                    stockUseCoefficients,
                    1,
                    variableCount);
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(stockUseVariables, stockUseCoefficients),
                    stockUseOffsets[row]);
            if (stockUseUpperBounds[row] > 0L) {
                allUsed.add(used);
            }
        }
        for (int index = 0; index < directUsedVariables.length; index++) {
            int variable = directUsedVariables[index];
            if (variable < 0
                    || variable >= variableCount
                    || directUsedDistances[index] < 0) {
                return new long[] {MODEL_INVALID, 0L};
            }
            allUsed.add(variables[variable]);
        }

        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT integer model: " + validation);
        }

        long deadlineNanos = deadlineNanos(maxSeconds);
        long branches = 0L;
        IntVar[] executions = java.util.Arrays.copyOf(variables, executionVariableCount);
        model.minimize(LinearExpr.sum(executions));
        SolveAttempt executionAttempt = solveOptimal(model, deadlineNanos);
        if (executionAttempt.status() != CpSolverStatus.OPTIMAL) {
            return new long[] {optimalStatusCode(executionAttempt.status()), branches};
        }
        long executionOptimum = 0L;
        try {
            for (IntVar execution : executions) {
                executionOptimum = Math.addExact(
                        executionOptimum, executionAttempt.solver().value(execution));
            }
        } catch (ArithmeticException overflow) {
            return new long[] {MODEL_INVALID, branches};
        }
        model.addEquality(LinearExpr.sum(executions), executionOptimum);
        model.clearObjective();

        SolveAttempt finalAttempt = executionAttempt;
        if (!allUsed.isEmpty()) {
            model.minimize(LinearExpr.sum(allUsed.toArray(IntVar[]::new)));
            finalAttempt = solveOptimal(model, deadlineNanos);
            branches = saturatedAdd(branches, finalAttempt.branches());
        }
        long statusCode = optimalStatusCode(finalAttempt.status());
        long[] result = new long[2 + (statusCode == SOLVED ? variableCount : 0)];
        result[0] = statusCode;
        result[1] = branches;
        if (statusCode == SOLVED) {
            for (int variable = 0; variable < variableCount; variable++) {
                result[2 + variable] = finalAttempt.solver().value(variables[variable]);
            }
        }
        return result;
    }

    /**
     * Selects an acyclic support for an ordinary material graph.
     *
     * <p>Every recipe has one long firing variable and one activation Boolean. If active, all of its
     * input groups must have a smaller topological rank than its primary-output group. Items in one
     * proven ratio-conservative conversion SCC share a rank and may therefore use both conversion
     * directions; the caller supplies a separate executable-prefix certificate for those groups.
     * Together with exact material balances and explicit unchanged-catalyst presence rows, no time
     * horizon or per-firing expansion is present.</p>
     *
     * @param cycleRecipes recipe indices in each proven strict conversion cycle, in cycle order
     * @param cycleInputItems internal input item aligned with every cycle recipe
     * @param cycleInputAmounts internal input amount aligned with every cycle recipe
     * @param cyclePrimitiveFirings one zero-net primitive firing vector per cycle
     * @param reusableCatalysts per-recipe unchanged-catalyst amount for each private route
     * @param reusableItems logical item represented by each private route
     * @param reusableCandidatePhysicals accepted physical-stock indices for each private route
     * @param reusablePhysicalStocks capacity of every host + actual-variant physical stock
     * @return {@code [status, branches, x0..xN, rank0..rankM, selectedCycleStart0..K,
     * missing0..missingM, reusableMissing0..G]}
     */
    public static long[] solveRankedPlan(
            long[][] consumed,
            long[][] produced,
            long[][] catalysts,
            long[][] finiteUseAmounts,
            long[][] finiteUseLifetimes,
            int[] outputItems,
            int[] primaryOutputItems,
            long[] primaryOutputAmounts,
            int[] rankGroups,
            int[][] cycleRecipes,
            int[][] cycleInputItems,
            long[][] cycleInputAmounts,
            long[][] cyclePrimitiveFirings,
            long[] stocks,
            long[][] reusableCatalysts,
            int[] reusableItems,
            int[][] reusableCandidatePhysicals,
            long[] reusablePhysicalStocks,
            int[] itemDistances,
            int targetItem,
            long targetAmount,
            long[] firingUpperBounds,
            double maxSeconds) {
        int recipeCount = consumed.length;
        int itemCount = stocks.length;
        if (recipeCount == 0
                || recipeCount != produced.length
                || recipeCount != catalysts.length
                || recipeCount != finiteUseAmounts.length
                || recipeCount != finiteUseLifetimes.length
                || recipeCount != reusableCatalysts.length
                || recipeCount != outputItems.length
                || recipeCount != primaryOutputItems.length
                || recipeCount != primaryOutputAmounts.length
                || recipeCount != firingUpperBounds.length
                || rankGroups.length != itemCount
                || itemDistances.length != itemCount
                || cycleRecipes.length != cycleInputItems.length
                || cycleRecipes.length != cycleInputAmounts.length
                || cycleRecipes.length != cyclePrimitiveFirings.length
                || reusableItems.length != reusableCandidatePhysicals.length
                || targetItem < 0
                || targetItem >= itemCount
                || targetAmount <= 0L) {
            return new long[] {MODEL_INVALID, 0L};
        }

        var model = new CpModel();
        var firings = new IntVar[recipeCount];
        var active = new BoolVar[recipeCount];
        var finiteUseBatches = new IntVar[recipeCount][itemCount];
        var ranks = new IntVar[itemCount];
        var used = new IntVar[itemCount];
        var missing = new IntVar[itemCount];
        long missingUpperBound = Math.max(1L, (Long.MAX_VALUE / 4L) / itemCount);
        for (int item = 0; item < itemCount; item++) {
            if (rankGroups[item] < 0 || itemDistances[item] < 0 || stocks[item] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            ranks[item] = model.newIntVar(0L, Math.max(0, itemCount - 1L), "rank_" + item);
            used[item] = model.newIntVar(0L, stocks[item], "used_" + item);
            missing[item] = model.newIntVar(0L, missingUpperBound, "missing_" + item);
        }
        var representativeByGroup = new java.util.HashMap<Integer, Integer>();
        for (int item = 0; item < itemCount; item++) {
            Integer representative = representativeByGroup.putIfAbsent(rankGroups[item], item);
            if (representative != null) {
                model.addEquality(ranks[item], ranks[representative]);
            }
        }
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            if (consumed[recipe].length != itemCount
                    || produced[recipe].length != itemCount
                    || catalysts[recipe].length != itemCount
                    || finiteUseAmounts[recipe].length != itemCount
                    || finiteUseLifetimes[recipe].length != itemCount
                    || reusableCatalysts[recipe].length != reusableItems.length
                    || outputItems[recipe] < 0
                    || outputItems[recipe] >= itemCount
                    || primaryOutputItems[recipe] < 0
                    || primaryOutputItems[recipe] >= itemCount
                    || primaryOutputAmounts[recipe] <= 0L
                    || firingUpperBounds[recipe] <= 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            firings[recipe] = model.newIntVar(
                    0L, firingUpperBounds[recipe], "x_" + recipe);
            active[recipe] = model.newBoolVar("active_" + recipe);
            for (int item = 0; item < itemCount; item++) {
                long amount = finiteUseAmounts[recipe][item];
                long lifetime = finiteUseLifetimes[recipe][item];
                if ((amount == 0L) != (lifetime == 0L) || amount < 0L || lifetime < 0L) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                if (amount == 0L) continue;
                long upper = Math.floorDiv(firingUpperBounds[recipe] - 1L, lifetime) + 1L;
                IntVar batches = model.newIntVar(
                        0L, upper, "finite_" + recipe + "_" + item);
                finiteUseBatches[recipe][item] = batches;
                model.addGreaterOrEqual(
                        LinearExpr.weightedSum(
                                new IntVar[] {batches, firings[recipe]},
                                new long[] {lifetime, -1L}),
                        0L);
                model.addLessOrEqual(
                        LinearExpr.weightedSum(
                                new IntVar[] {batches, firings[recipe]},
                                new long[] {lifetime, -1L}),
                        lifetime - 1L);
            }
        }

        var cycleStarts = new BoolVar[cycleRecipes.length][];
        for (int cycle = 0; cycle < cycleRecipes.length; cycle++) {
            int size = cycleRecipes[cycle].length;
            if (size == 0
                    || cycleInputItems[cycle].length != size
                    || cycleInputAmounts[cycle].length != size
                    || cyclePrimitiveFirings[cycle].length != size) {
                return new long[] {MODEL_INVALID, 0L};
            }
            boolean[] memberRecipe = new boolean[recipeCount];
            for (int offset = 0; offset < size; offset++) {
                int recipe = cycleRecipes[cycle][offset];
                int input = cycleInputItems[cycle][offset];
                if (recipe < 0 || recipe >= recipeCount
                        || input < 0 || input >= itemCount
                        || memberRecipe[recipe]
                        || cycleInputAmounts[cycle][offset] <= 0L
                        || cyclePrimitiveFirings[cycle][offset] <= 0L
                        || rankGroups[input] != rankGroups[outputItems[recipe]]) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                memberRecipe[recipe] = true;
            }

            cycleStarts[cycle] = new BoolVar[size];
            for (int start = 0; start < size; start++) {
                BoolVar selected = model.newBoolVar("cycle_" + cycle + "_start_" + start);
                cycleStarts[cycle][start] = selected;
            }
            model.addExactlyOne(cycleStarts[cycle]);

            // A weighted conversion cycle may need to interleave the same transition several times
            // within one primitive residue (for example A->B, B->A, A->B). Treating each recipe's
            // complete firing count as one contiguous block rejects such executable markings. The
            // caller therefore reconstructs an exact bounded Petri prefix for the chosen firing
            // vector and charges its concrete startup state during independent replay. The one-hot
            // value remains in the wire format for compatibility; it is no longer a block-order
            // certificate.

            // A complete primitive round has zero internal delta and only consumes non-negative
            // external inputs. Subtracting it preserves every material lower bound while strictly
            // reducing the objective, so retain only cycle-reduced representatives in the model.
            BoolVar[] belowPrimitive = new BoolVar[size];
            for (int offset = 0; offset < size; offset++) {
                int recipe = cycleRecipes[cycle][offset];
                belowPrimitive[offset] = model.newBoolVar(
                        "cycle_" + cycle + "_below_" + offset);
                model.addLessThan(
                                firings[recipe], cyclePrimitiveFirings[cycle][offset])
                        .onlyEnforceIf(belowPrimitive[offset]);
            }
            model.addGreaterOrEqual(LinearExpr.sum(belowPrimitive), 1L);
        }
        // Create the complete variable vector before building any weighted sum: a catalyst may be
        // supplied by a producer that appears later in the caller's stable recipe order.
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            model.addGreaterThan(firings[recipe], 0L).onlyEnforceIf(active[recipe]);
            model.addEquality(firings[recipe], 0L).onlyEnforceIf(active[recipe].not());
            int output = outputItems[recipe];
            for (int input = 0; input < itemCount; input++) {
                if ((consumed[recipe][input] > 0L
                                || catalysts[recipe][input] > 0L
                                || finiteUseAmounts[recipe][input] > 0L)
                        && rankGroups[input] != rankGroups[output]) {
                    model.addLessThan(ranks[input], ranks[output])
                            .onlyEnforceIf(active[recipe]);
                }
                if (catalysts[recipe][input] > 0L) {
                    long shortage = catalysts[recipe][input] - stocks[input];
                    long[] catalystProduction = new long[recipeCount];
                    for (int producer = 0; producer < recipeCount; producer++) {
                        catalystProduction[producer] = produced[producer][input];
                    }
                    if (shortage > 0L) {
                        IntVar[] presenceVariables = java.util.Arrays.copyOf(
                                firings, recipeCount + 1);
                        long[] presenceCoefficients = java.util.Arrays.copyOf(
                                catalystProduction, recipeCount + 1);
                        presenceVariables[recipeCount] = missing[input];
                        presenceCoefficients[recipeCount] = 1L;
                        model.addGreaterOrEqual(
                                        LinearExpr.weightedSum(
                                                presenceVariables, presenceCoefficients),
                                        shortage)
                                .onlyEnforceIf(active[recipe]);
                    }

                    // Stock reserved for an unchanged catalyst is the part of its presence
                    // requirement not supplied by earlier production. This lower bound becomes
                    // exact because used[input] is minimized after the execution optimum is fixed.
                    IntVar[] catalystUseVariables = new IntVar[recipeCount + 3];
                    long[] catalystUseCoefficients = new long[recipeCount + 3];
                    catalystUseVariables[0] = used[input];
                    catalystUseCoefficients[0] = 1L;
                    catalystUseVariables[1] = missing[input];
                    catalystUseCoefficients[1] = 1L;
                    for (int producer = 0; producer < recipeCount; producer++) {
                        catalystUseVariables[2 + producer] = firings[producer];
                        catalystUseCoefficients[2 + producer] = produced[producer][input];
                    }
                    catalystUseVariables[recipeCount + 2] = active[recipe];
                    catalystUseCoefficients[recipeCount + 2] = -catalysts[recipe][input];
                    model.addGreaterOrEqual(
                            LinearExpr.weightedSum(
                                    catalystUseVariables, catalystUseCoefficients),
                            0L);
                }
            }
            for (int group = 0; group < reusableItems.length; group++) {
                long amount = reusableCatalysts[recipe][group];
                int input = reusableItems[group];
                if (amount < 0L || input < 0 || input >= itemCount) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                if (amount > 0L && rankGroups[input] != rankGroups[output]) {
                    model.addLessThan(ranks[input], ranks[output])
                            .onlyEnforceIf(active[recipe]);
                }
            }
            // A recipe is replayed at its anchor output rank. Every other material output must
            // become visible later unless it belongs to the same contracted SCC; otherwise a
            // byproduct consumer could be ranked before the recipe that creates it.
            for (int producedItem = 0; producedItem < itemCount; producedItem++) {
                if (produced[recipe][producedItem] > 0L
                        && rankGroups[producedItem] != rankGroups[output]) {
                    model.addLessThan(ranks[output], ranks[producedItem])
                            .onlyEnforceIf(active[recipe]);
                }
            }
        }

        // Exact gross presence demand for unchanged catalysts. A shared catalyst is counted once at
        // the largest active requirement, not once per recipe, so it cannot justify unrelated extra
        // primary production.
        var catalystNeeds = new IntVar[itemCount];
        for (int item = 0; item < itemCount; item++) {
            long maximum = 0L;
            var arguments = new java.util.ArrayList<com.google.ortools.sat.LinearArgument>();
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                long amount = catalysts[recipe][item];
                if (amount <= 0L) continue;
                maximum = Math.max(maximum, amount);
                arguments.add(LinearExpr.affine(active[recipe], amount, 0L));
            }
            if (arguments.isEmpty()) continue;
            arguments.add(LinearExpr.constant(0L));
            IntVar need = model.newIntVar(0L, maximum, "catalyst_need_" + item);
            model.addMaxEquality(
                    need,
                    arguments.toArray(com.google.ortools.sat.LinearArgument[]::new));
            catalystNeeds[item] = need;
        }

        // Host-private catalysts are not ordinary logical stock. Each route gets its exact active
        // seed requirement (the maximum across recipes sharing that route), then assigns the
        // non-missing part over only its accepted physical variants. Physical capacity rows couple
        // all overlapping fuzzy routes, preventing one host stack from being counted twice.
        int reusableCount = reusableItems.length;
        var reusableMissing = new IntVar[reusableCount];
        var assignmentsByPhysical = new java.util.ArrayList<java.util.ArrayList<IntVar>>(
                reusablePhysicalStocks.length);
        for (int physical = 0; physical < reusablePhysicalStocks.length; physical++) {
            if (reusablePhysicalStocks[physical] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            assignmentsByPhysical.add(new java.util.ArrayList<>());
        }
        var reusableMissingByItem = new java.util.ArrayList<java.util.ArrayList<IntVar>>(itemCount);
        for (int item = 0; item < itemCount; item++) {
            reusableMissingByItem.add(new java.util.ArrayList<>());
        }
        var reusableUsed = new java.util.ArrayList<IntVar>();
        for (int group = 0; group < reusableCount; group++) {
            int item = reusableItems[group];
            if (item < 0 || item >= itemCount) return new long[] {MODEL_INVALID, 0L};
            long maximum = 0L;
            var needArguments = new java.util.ArrayList<com.google.ortools.sat.LinearArgument>();
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                long amount = reusableCatalysts[recipe][group];
                if (amount < 0L) return new long[] {MODEL_INVALID, 0L};
                if (amount > 0L) {
                    maximum = Math.max(maximum, amount);
                    needArguments.add(LinearExpr.affine(active[recipe], amount, 0L));
                }
            }
            if (needArguments.isEmpty()) return new long[] {MODEL_INVALID, 0L};
            needArguments.add(LinearExpr.constant(0L));
            IntVar need = model.newIntVar(0L, maximum, "reusable_need_" + group);
            model.addMaxEquality(
                    need,
                    needArguments.toArray(com.google.ortools.sat.LinearArgument[]::new));

            IntVar shortage = model.newIntVar(0L, maximum, "reusable_missing_" + group);
            reusableMissing[group] = shortage;
            reusableMissingByItem.get(item).add(shortage);

            int[] candidates = reusableCandidatePhysicals[group];
            var routeAssignments = new java.util.ArrayList<IntVar>(candidates.length);
            var seenPhysicals = new java.util.HashSet<Integer>();
            for (int candidate = 0; candidate < candidates.length; candidate++) {
                int physical = candidates[candidate];
                if (physical < 0
                        || physical >= reusablePhysicalStocks.length
                        || !seenPhysicals.add(physical)) {
                    return new long[] {MODEL_INVALID, 0L};
                }
                long upper = Math.min(maximum, reusablePhysicalStocks[physical]);
                IntVar assignment = model.newIntVar(
                        0L, upper, "reusable_assign_" + group + "_" + candidate);
                routeAssignments.add(assignment);
                assignmentsByPhysical.get(physical).add(assignment);
                reusableUsed.add(assignment);
            }
            var fulfilled = new java.util.ArrayList<IntVar>(routeAssignments);
            fulfilled.add(shortage);
            model.addEquality(LinearExpr.sum(fulfilled.toArray(IntVar[]::new)), need);
        }
        for (int physical = 0; physical < reusablePhysicalStocks.length; physical++) {
            var assignments = assignmentsByPhysical.get(physical);
            if (!assignments.isEmpty()) {
                model.addLessOrEqual(
                        LinearExpr.sum(assignments.toArray(IntVar[]::new)),
                        reusablePhysicalStocks[physical]);
            }
        }
        for (int item = 0; item < itemCount; item++) {
            var shortages = reusableMissingByItem.get(item);
            if (!shortages.isEmpty()) {
                model.addEquality(missing[item], LinearExpr.sum(shortages.toArray(IntVar[]::new)));
            }
        }

        // Every active recipe must allocate all but at most one batch remainder of its primary
        // output to real gross demand. The minimum served amount is
        // amount*firings-(amount-1)*active. Express it inline rather than with another long-domain
        // variable: besides being exact, this keeps OR-Tools' sum-of-domains overflow guard
        // independent of recipe ratios.
        for (int item = 0; item < itemCount; item++) {
            var demandVariables = new java.util.ArrayList<IntVar>();
            var demandCoefficients = new java.util.ArrayList<Long>();
            boolean hasPrimaryProducer = false;
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                if (primaryOutputItems[recipe] == item) {
                    demandVariables.add(firings[recipe]);
                    demandCoefficients.add(primaryOutputAmounts[recipe]);
                    if (primaryOutputAmounts[recipe] > 1L) {
                        demandVariables.add(active[recipe]);
                        demandCoefficients.add(1L - primaryOutputAmounts[recipe]);
                    }
                    hasPrimaryProducer = true;
                }
                if (consumed[recipe][item] > 0L) {
                    demandVariables.add(firings[recipe]);
                    demandCoefficients.add(-consumed[recipe][item]);
                }
                if (finiteUseBatches[recipe][item] != null) {
                    demandVariables.add(finiteUseBatches[recipe][item]);
                    demandCoefficients.add(-finiteUseAmounts[recipe][item]);
                }
            }
            if (!hasPrimaryProducer) continue;
            if (catalystNeeds[item] != null) {
                demandVariables.add(catalystNeeds[item]);
                demandCoefficients.add(-1L);
            }
            long directDemand = item == targetItem ? targetAmount : 0L;
            model.addLessOrEqual(
                    LinearExpr.weightedSum(
                            demandVariables.toArray(IntVar[]::new),
                            demandCoefficients.stream().mapToLong(Long::longValue).toArray()),
                    directDemand);
        }

        for (int item = 0; item < itemCount; item++) {
            long[] coefficients = new long[recipeCount];
            var finiteVariables = new java.util.ArrayList<IntVar>();
            var finiteCoefficients = new java.util.ArrayList<Long>();
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                long coefficient = produced[recipe][item] - consumed[recipe][item];
                coefficients[recipe] = coefficient;
                if (finiteUseBatches[recipe][item] != null) {
                    finiteVariables.add(finiteUseBatches[recipe][item]);
                    finiteCoefficients.add(-finiteUseAmounts[recipe][item]);
                }
            }
            long demand = item == targetItem ? targetAmount : 0L;
            long minimumNet = demand - stocks[item];
            int finiteCount = finiteVariables.size();
            IntVar[] balanceVariables = java.util.Arrays.copyOf(
                    firings, recipeCount + finiteCount + 1);
            long[] balanceCoefficients = java.util.Arrays.copyOf(
                    coefficients, recipeCount + finiteCount + 1);
            for (int finite = 0; finite < finiteCount; finite++) {
                balanceVariables[recipeCount + finite] = finiteVariables.get(finite);
                balanceCoefficients[recipeCount + finite] = finiteCoefficients.get(finite);
            }
            balanceVariables[recipeCount + finiteCount] = missing[item];
            balanceCoefficients[recipeCount + finiteCount] = 1L;
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(balanceVariables, balanceCoefficients), minimumNet);

            // used[item] >= demand - missing[item] - netProduction. Once the missing vector and
            // execution optimum are fixed, minimizing used is the exact initial-stock draw.
            IntVar[] stockUseVariables = new IntVar[recipeCount + finiteCount + 2];
            long[] stockUseCoefficients = new long[recipeCount + finiteCount + 2];
            stockUseVariables[0] = used[item];
            stockUseCoefficients[0] = 1L;
            stockUseVariables[1] = missing[item];
            stockUseCoefficients[1] = 1L;
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                stockUseVariables[2 + recipe] = firings[recipe];
                stockUseCoefficients[2 + recipe] = coefficients[recipe];
            }
            for (int finite = 0; finite < finiteCount; finite++) {
                stockUseVariables[2 + recipeCount + finite] = finiteVariables.get(finite);
                stockUseCoefficients[2 + recipeCount + finite] = finiteCoefficients.get(finite);
            }
            model.addGreaterOrEqual(
                    LinearExpr.weightedSum(stockUseVariables, stockUseCoefficients), demand);
        }
        model.addDecisionStrategy(
                firings,
                DecisionStrategyProto.VariableSelectionStrategy.CHOOSE_FIRST,
                DecisionStrategyProto.DomainReductionStrategy.SELECT_MIN_VALUE);
        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT ranked model: " + validation);
        }
        long deadlineNanos = deadlineNanos(maxSeconds);
        long branches = 0L;
        CpSolver solver = null;

        var allUsed = new java.util.ArrayList<IntVar>();
        for (int item = 0; item < itemCount; item++) {
            if (stocks[item] > 0L) {
                allUsed.add(used[item]);
            }
        }
        allUsed.addAll(reusableUsed);

        // The overwhelmingly common case has no shortage. Prove that once on a clone while already
        // minimizing executions; this avoids one optimization round per dependency depth on deep
        // Fibonacci DAGs. Only a genuinely starved graph pays for the graded Missing objectives.
        CpModel zeroMissingModel = model.getClone();
        zeroMissingModel.addEquality(LinearExpr.sum(missing), 0L);
        zeroMissingModel.minimize(LinearExpr.sum(firings));
        SolveAttempt zeroMissingAttempt = solveOptimal(zeroMissingModel, deadlineNanos);
        branches = saturatedAdd(branches, zeroMissingAttempt.branches());

        SolveAttempt executionAttempt;
        if (zeroMissingAttempt.status() == CpSolverStatus.OPTIMAL) {
            model.addEquality(LinearExpr.sum(missing), 0L);
            executionAttempt = zeroMissingAttempt;
        } else if (zeroMissingAttempt.status() == CpSolverStatus.INFEASIBLE) {
            var missingByDistance = new java.util.TreeMap<Integer, java.util.List<IntVar>>();
            for (int item = 0; item < itemCount; item++) {
                missingByDistance.computeIfAbsent(
                        itemDistances[item], ignored -> new java.util.ArrayList<>())
                        .add(missing[item]);
            }
            for (java.util.List<IntVar> tier : missingByDistance.values()) {
                IntVar[] tierVariables = tier.toArray(IntVar[]::new);
                model.minimize(LinearExpr.sum(tierVariables));
                SolveAttempt attempt = solveOptimal(model, deadlineNanos);
                branches = saturatedAdd(branches, attempt.branches());
                if (attempt.status() != CpSolverStatus.OPTIMAL) {
                    return new long[] {optimalStatusCode(attempt.status()), branches};
                }
                long optimum = 0L;
                try {
                    for (IntVar variable : tierVariables) {
                        optimum = Math.addExact(optimum, attempt.solver().value(variable));
                    }
                } catch (ArithmeticException overflow) {
                    return new long[] {MODEL_INVALID, branches};
                }
                model.addEquality(LinearExpr.sum(tierVariables), optimum);
                model.clearObjective();
            }
            model.minimize(LinearExpr.sum(firings));
            executionAttempt = solveOptimal(model, deadlineNanos);
            branches = saturatedAdd(branches, executionAttempt.branches());
        } else {
            return new long[] {
                    optimalStatusCode(zeroMissingAttempt.status()), branches
            };
        }
        branches = saturatedAdd(branches, executionAttempt.branches());
        if (executionAttempt.status() != CpSolverStatus.OPTIMAL) {
            return new long[] {
                    optimalStatusCode(executionAttempt.status()), branches
            };
        }
        long executionOptimum = 0L;
        try {
            for (IntVar firing : firings) {
                executionOptimum = Math.addExact(
                        executionOptimum, executionAttempt.solver().value(firing));
            }
        } catch (ArithmeticException overflow) {
            return new long[] {MODEL_INVALID, branches};
        }
        model.addEquality(LinearExpr.sum(firings), executionOptimum);
        model.clearObjective();

        SolveAttempt finalAttempt = executionAttempt;
        if (!allUsed.isEmpty()) {
            model.minimize(LinearExpr.sum(allUsed.toArray(IntVar[]::new)));
            finalAttempt = solveOptimal(model, deadlineNanos);
            branches = saturatedAdd(branches, finalAttempt.branches());
        }
        solver = finalAttempt.solver();
        long statusCode = optimalStatusCode(finalAttempt.status());
        int valueCount = recipeCount + itemCount + cycleStarts.length + itemCount
                + reusableCount;
        long[] result = new long[2 + (statusCode == SOLVED ? valueCount : 0)];
        result[0] = statusCode;
        result[1] = branches;
        if (statusCode == SOLVED) {
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                result[2 + recipe] = solver.value(firings[recipe]);
            }
            for (int item = 0; item < itemCount; item++) {
                result[2 + recipeCount + item] = solver.value(ranks[item]);
            }
            int cycleOffset = 2 + recipeCount + itemCount;
            for (int cycle = 0; cycle < cycleStarts.length; cycle++) {
                long selectedStart = -1L;
                for (int start = 0; start < cycleStarts[cycle].length; start++) {
                    if (solver.booleanValue(cycleStarts[cycle][start])) {
                        selectedStart = start;
                        break;
                    }
                }
                if (selectedStart < 0L) return new long[] {MODEL_INVALID, 0L};
                result[cycleOffset + cycle] = selectedStart;
            }
            int missingOffset = cycleOffset + cycleStarts.length;
            for (int item = 0; item < itemCount; item++) {
                result[missingOffset + item] = solver.value(missing[item]);
            }
            int reusableMissingOffset = missingOffset + itemCount;
            for (int group = 0; group < reusableCount; group++) {
                result[reusableMissingOffset + group] = solver.value(reusableMissing[group]);
            }
        }
        return result;
    }

    /**
     * Selects one already-proven feedback prefix for a fixed firing vector.
     *
     * <p>The caller supplies executable {@code required} markings generated by its Petri-net
     * certificate and an overflow-free lexicographic rank. CP-SAT materializes the selected
     * requirement and its shortage with one-hot Booleans; no firing sequence is time-expanded.</p>
     *
     * @return {@code [status, branches, selectedOption]}
     */
    public static long[] chooseFeedbackOption(
            long[][] requirements,
            long[] stocks,
            long[] scoreRanks,
            double maxSeconds) {
        int optionCount = requirements.length;
        if (optionCount == 0 || optionCount != scoreRanks.length) {
            return new long[] {MODEL_INVALID, 0L};
        }
        int itemCount = stocks.length;
        var model = new CpModel();
        var selected = new BoolVar[optionCount];
        for (int option = 0; option < optionCount; option++) {
            if (requirements[option].length != itemCount || scoreRanks[option] < 0L) {
                return new long[] {MODEL_INVALID, 0L};
            }
            selected[option] = model.newBoolVar("feedback_" + option);
        }
        model.addExactlyOne(selected);

        for (int item = 0; item < itemCount; item++) {
            long maximum = 0L;
            long[] coefficients = new long[optionCount];
            for (int option = 0; option < optionCount; option++) {
                long requirement = requirements[option][item];
                if (requirement < 0L) return new long[] {MODEL_INVALID, 0L};
                coefficients[option] = requirement;
                maximum = Math.max(maximum, requirement);
            }
            IntVar chosenRequirement = model.newIntVar(
                    0L, maximum, "feedback_required_" + item);
            model.addEquality(
                    chosenRequirement, LinearExpr.weightedSum(selected, coefficients));
            long maximumShortage = Math.max(0L, maximum - stocks[item]);
            IntVar shortage = model.newIntVar(
                    0L, maximumShortage, "feedback_shortage_" + item);
            model.addMaxEquality(
                    shortage,
                    new com.google.ortools.sat.LinearArgument[] {
                            LinearExpr.affine(chosenRequirement, 1L, -stocks[item]),
                            LinearExpr.constant(0L)
                    });
        }
        model.minimize(LinearExpr.weightedSum(selected, scoreRanks));

        String validation = model.validate();
        if (!validation.isEmpty()) {
            throw new IllegalArgumentException("invalid CP-SAT feedback model: " + validation);
        }
        var solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(maxSeconds);
        solver.getParameters().setNumWorkers(1);
        solver.getParameters().setRandomSeed(0);
        CpSolverStatus status = solver.solve(model);
        long statusCode = optimalStatusCode(status);
        if (statusCode != SOLVED) {
            return new long[] {statusCode, Math.max(0L, solver.numBranches())};
        }
        for (int option = 0; option < optionCount; option++) {
            if (solver.booleanValue(selected[option])) {
                return new long[] {SOLVED, Math.max(0L, solver.numBranches()), option};
            }
        }
        return new long[] {MODEL_INVALID, Math.max(0L, solver.numBranches())};
    }

    /** Objectives are certificates only after optimality, not merely after finding a feasible row. */
    private static long optimalStatusCode(CpSolverStatus status) {
        return switch (status) {
            case OPTIMAL -> SOLVED;
            case INFEASIBLE -> INFEASIBLE;
            case MODEL_INVALID -> MODEL_INVALID;
            default -> UNKNOWN;
        };
    }

    private static SolveAttempt solveOptimal(CpModel model, long deadlineNanos) {
        long remaining = deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return new SolveAttempt(null, CpSolverStatus.UNKNOWN, 0L);
        }
        var solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(remaining / 1_000_000_000.0D);
        solver.getParameters().setNumWorkers(1);
        solver.getParameters().setRandomSeed(0);
        CpSolverStatus status = solver.solve(model);
        return new SolveAttempt(solver, status, Math.max(0L, solver.numBranches()));
    }

    private static long deadlineNanos(double maxSeconds) {
        if (!(maxSeconds > 0.0D) || Double.isNaN(maxSeconds)) return System.nanoTime();
        double requestedNanos = maxSeconds * 1_000_000_000.0D;
        if (!Double.isFinite(requestedNanos) || requestedNanos >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        long budget = Math.max(1L, (long) requestedNanos);
        return budget >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + budget;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private record SolveAttempt(CpSolver solver, CpSolverStatus status, long branches) {
    }

}
