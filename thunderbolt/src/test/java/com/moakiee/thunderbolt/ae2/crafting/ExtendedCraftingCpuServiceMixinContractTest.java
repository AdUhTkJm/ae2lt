package com.moakiee.thunderbolt.ae2.crafting;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

final class ExtendedCraftingCpuServiceMixinContractTest {
    @Test
    void automaticSelectionDoesNotOverrideExplicitThirdPartyCpu() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/"
                        + "ExtendedCraftingCpuServiceMixin.java"));

        int automaticHook = source.indexOf(
                "private void thunderbolt$submitToAutomaticExtendedCpuCluster(");
        int nextHook = source.indexOf("    @Inject(", automaticHook);
        String automaticHookSource = source.substring(automaticHook, nextHook);

        var explicitTargetGuardMatcher = Pattern.compile(
                "if\\s*\\(target != null\\)\\s*\\{\\s*return;\\s*\\}")
                .matcher(automaticHookSource);
        int explicitTargetGuard = explicitTargetGuardMatcher.find()
                ? explicitTargetGuardMatcher.start()
                : -1;
        int extendedCpuLookup = automaticHookSource.indexOf(
                "thunderbolt$findSuitableExtendedCpuCluster(");

        assertTrue(automaticHook >= 0, "automatic extended CPU submission hook must exist");
        assertTrue(explicitTargetGuard >= 0, "explicit third-party CPU targets must bypass automatic selection");
        assertTrue(explicitTargetGuard < extendedCpuLookup,
                "the explicit-target guard must run before selecting an extended CPU");
    }
}
