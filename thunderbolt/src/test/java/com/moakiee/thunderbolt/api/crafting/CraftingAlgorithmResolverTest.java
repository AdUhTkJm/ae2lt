package com.moakiee.thunderbolt.api.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

class CraftingAlgorithmResolverTest {
    private static final ResourceLocation PUBLIC_HIGH = id("public_high");
    private static final ResourceLocation PUBLIC_LOW = id("public_low");
    private static final ResourceLocation PRIVATE_HIGH = id("private_high");
    private static final ResourceLocation PRIVATE_LOW = id("private_low");

    @BeforeAll
    static void registerEngines() {
        CraftingPlanningEngines.register(new TestEngine(PUBLIC_HIGH), 100, true);
        CraftingPlanningEngines.register(new TestEngine(PUBLIC_LOW), 10, true);
        CraftingPlanningEngines.register(new TestEngine(PRIVATE_HIGH), 80, false);
        CraftingPlanningEngines.register(new TestEngine(PRIVATE_LOW), 20, false);
    }

    @Test
    void publicAlgorithmsNeedNoProviderAndVanillaIsLast() {
        var ids = engineIds(CraftingAlgorithmResolver.resolve(List.of()));

        assertEquals(List.of(PUBLIC_HIGH, PUBLIC_LOW), ids);
        assertFalse(ids.contains(PRIVATE_HIGH));
    }

    @Test
    void playerPriorityWinsThenAlgorithmPriorityBreaksTies() {
        var resolved = CraftingAlgorithmResolver.resolve(List.of(
                new CraftingAlgorithmSelection(PRIVATE_LOW, 5),
                new CraftingAlgorithmSelection(PRIVATE_HIGH, 5),
                new CraftingAlgorithmSelection(PUBLIC_HIGH, -1)));

        assertEquals(PlanningChoice.engine(PRIVATE_HIGH), resolved.get(0));
        assertEquals(PlanningChoice.engine(PRIVATE_LOW), resolved.get(1));
        assertEquals(PlanningChoice.engine(PUBLIC_LOW), resolved.get(2));
        // Explicit -1 replaces PUBLIC_HIGH's implicit public priority 0. Vanilla at 0 is
        // terminal, so this is a deliberate way to place the algorithm below the fallback.
        assertFalse(resolved.contains(PlanningChoice.engine(PUBLIC_HIGH)));
        assertEquals(PlanningChoice.VANILLA, resolved.getLast());
    }

    @Test
    void highestProviderPriorityDeduplicatesSameAlgorithm() {
        var resolved = CraftingAlgorithmResolver.resolve(List.of(
                new CraftingAlgorithmSelection(PRIVATE_LOW, 1),
                new CraftingAlgorithmSelection(PRIVATE_LOW, 30)));

        assertEquals(PlanningChoice.engine(PRIVATE_LOW), resolved.get(0));
        assertEquals(1, resolved.stream()
                .filter(PlanningChoice.engine(PRIVATE_LOW)::equals)
                .count());
    }

    @Test
    void providerCanExplicitlySelectPublicVanilla() {
        var resolved = CraftingAlgorithmResolver.resolve(List.of(
                new CraftingAlgorithmSelection(CraftingPlanningEngines.VANILLA_ID, 100)));

        assertEquals(ResourceLocation.fromNamespaceAndPath("ae2", "vanilla"),
                CraftingPlanningEngines.VANILLA_ID);
        assertEquals(List.of(PlanningChoice.VANILLA), resolved);
        assertTrue(CraftingPlanningEngines.isPublic(CraftingPlanningEngines.VANILLA_ID));
        assertTrue(CraftingPlanningEngines.getPublic().contains(
                CraftingPlanningEngines.VANILLA_ID));
        var missing = id("missing_registration");
        assertEquals(missing.toString(), CraftingPlanningEngines.getName(missing).getString());
    }

    @Test
    void defaultStatePreservesUnknownNbtAndNotifiesOnlyOnValidEdits() {
        var changes = new AtomicInteger();
        var state = new DefaultCraftingAlgorithmProviderState(
                PRIVATE_HIGH, 4, changes::incrementAndGet);
        var unknown = id("temporarily_missing");
        state.setSelection(new CraftingAlgorithmSelection(PUBLIC_HIGH, -7));
        state.setSelection(new CraftingAlgorithmSelection(PUBLIC_HIGH, -7));

        var tag = new CompoundTag();
        tag.putString("Algorithm", unknown.toString());
        tag.putInt("Priority", -7);
        var loaded = new DefaultCraftingAlgorithmProviderState(
                PRIVATE_HIGH, 0, changes::incrementAndGet);
        loaded.readFromNBT(tag);

        assertEquals(new CraftingAlgorithmSelection(unknown, -7), loaded.snapshot());
        assertEquals(1, changes.get());
    }

    @Test
    void providerCanSelectItsOwnedAlgorithmsOrAPublicAlgorithm() {
        var state = new DefaultCraftingAlgorithmProviderState(
                PRIVATE_HIGH, List.of(PRIVATE_HIGH, PRIVATE_LOW), 0, () -> { });

        state.setSelection(new CraftingAlgorithmSelection(PUBLIC_LOW, 8));
        assertEquals(new CraftingAlgorithmSelection(PUBLIC_LOW, 8), state.snapshot());

        state.setSelection(new CraftingAlgorithmSelection(PRIVATE_LOW, 9));

        assertEquals(PRIVATE_HIGH, state.getProvidedAlgorithm());
        assertEquals(List.of(PRIVATE_HIGH, PRIVATE_LOW), state.getProvidedAlgorithms());
        assertEquals(new CraftingAlgorithmSelection(PRIVATE_LOW, 9), state.snapshot());
        assertThrows(IllegalArgumentException.class, () -> state.setSelection(
                new CraftingAlgorithmSelection(id("another_private"), 9)));
    }

    @Test
    void providerMenuChoicesExcludeOtherNodesPrivateAlgorithms() {
        var selectable = CraftingPlanningEngines.selectableFor(PRIVATE_LOW);

        assertEquals(List.of(
                PUBLIC_HIGH,
                PRIVATE_LOW,
                PUBLIC_LOW,
                CraftingPlanningEngines.VANILLA_ID), selectable);
        assertFalse(selectable.contains(PRIVATE_HIGH));
    }

    @Test
    void providerMenuChoicesIncludeEveryOwnedPrivateAlgorithm() {
        var unavailableOptional = id("unavailable_optional");
        var selectable = CraftingPlanningEngines.selectableFor(
                List.of(PRIVATE_HIGH, PRIVATE_LOW, unavailableOptional));

        assertEquals(List.of(
                PUBLIC_HIGH,
                PRIVATE_HIGH,
                PRIVATE_LOW,
                PUBLIC_LOW,
                CraftingPlanningEngines.VANILLA_ID), selectable);
        assertFalse(selectable.contains(unavailableOptional));
    }

    @Test
    void duplicateRegistrationMetadataCannotSilentlyChange() {
        var engine = new TestEngine(id("duplicate_metadata"));
        CraftingPlanningEngines.register(engine, 1, false);
        assertThrows(IllegalArgumentException.class,
                () -> CraftingPlanningEngines.register(engine, 2, true));
    }

    @Test
    void allFailedDisplaySentinelCannotBeRegisteredOrSelected() {
        assertThrows(IllegalArgumentException.class, () -> CraftingPlanningEngines.register(
                new TestEngine(CraftingPlanningEngines.ALL_FAILED_ID), 1, true));
        assertFalse(CraftingPlanningEngines.isKnown(CraftingPlanningEngines.ALL_FAILED_ID));
        assertFalse(CraftingPlanningEngines.getPublic().contains(
                CraftingPlanningEngines.ALL_FAILED_ID));
    }

    private static List<ResourceLocation> engineIds(List<PlanningChoice> choices) {
        assertEquals(PlanningChoice.VANILLA, choices.getLast());
        return choices.stream()
                .filter(choice -> choice.kind() == PlanningChoice.Kind.ENGINE)
                .map(PlanningChoice::engineId)
                .toList();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("thunderbolt_test", path);
    }

    private record TestEngine(ResourceLocation id) implements CraftingPlanningEngine {
        @Override
        public boolean check(IGrid grid, PlanningRequest request) {
            return true;
        }

        @Override
        public PlanningEngineSession createSession(
                PlanningRequest request,
                @Nullable Object capturedInput,
                PlanningAttemptContext context) {
            throw new UnsupportedOperationException("resolver test does not execute engines");
        }
    }
}
