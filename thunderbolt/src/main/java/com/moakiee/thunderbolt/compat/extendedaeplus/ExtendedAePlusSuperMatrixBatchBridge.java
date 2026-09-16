package com.moakiee.thunderbolt.compat.extendedaeplus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;

import com.moakiee.thunderbolt.core.util.MixinReflectionSupport;

/** Reflection bridge to ExtendedAE Plus' development-only Super Assembler Matrix. */
public final class ExtendedAePlusSuperMatrixBatchBridge {
    private static final @Nullable Class<?> SCALED_PATTERN_CLASS = findClass(
            "com.extendedae_plus.api.crafting.ScaledMolecularAssemblerPattern");
    private static final @Nullable Constructor<?> SCALED_PATTERN_CONSTRUCTOR = findConstructor(
            SCALED_PATTERN_CLASS,
            IMolecularAssemblerSupportedPattern.class,
            long.class);
    private static final @Nullable Method GET_ORIGINAL = findMethod(SCALED_PATTERN_CLASS, "getOriginal");
    private static final @Nullable Method GET_MULTIPLIER = findMethod(SCALED_PATTERN_CLASS, "getMultiplier");
    private static final boolean AVAILABLE = SCALED_PATTERN_CLASS != null
            && SCALED_PATTERN_CONSTRUCTOR != null
            && GET_ORIGINAL != null
            && GET_MULTIPLIER != null;

    private ExtendedAePlusSuperMatrixBatchBridge() {
    }

    /** Older EAEP versions have no Super Assembler Matrix and remain completely untouched. */
    public static long capacity(IPatternDetails details) {
        if (!AVAILABLE || !(details instanceof IMolecularAssemblerSupportedPattern)) {
            return 1L;
        }

        long capacity = Long.MAX_VALUE;
        for (var input : details.getInputs()) {
            long amount = input.getMultiplier();
            if (amount > 0L) {
                capacity = Math.min(capacity, Long.MAX_VALUE / amount);
            }
        }
        for (var output : details.getOutputs()) {
            if (output != null && output.amount() > 0L) {
                capacity = Math.min(capacity, Long.MAX_VALUE / output.amount());
            }
        }

        var unwrapped = unwrap(details);
        if (unwrapped == null) {
            return 1L;
        }
        return Math.min(capacity, Long.MAX_VALUE / unwrapped.multiplier());
    }

    public static long pushBatch(
            ICraftingProvider provider,
            IPatternDetails details,
            KeyCounter[] oneCopyTemplate,
            long maxCraft) {
        if (maxCraft <= 0L) {
            return 0L;
        }
        if (!AVAILABLE
                || provider == null
                || details == null
                || oneCopyTemplate == null
                || !(details instanceof IMolecularAssemblerSupportedPattern)) {
            return maxCraft;
        }

        long capacity = capacity(details);
        long requested = Math.min(maxCraft, capacity);
        if (requested <= 0L) {
            return maxCraft;
        }

        var unwrapped = unwrap(details);
        if (unwrapped == null || !(unwrapped.pattern() instanceof IMolecularAssemblerSupportedPattern base)) {
            return maxCraft;
        }

        final long combinedMultiplier;
        try {
            combinedMultiplier = Math.multiplyExact(unwrapped.multiplier(), requested);
        } catch (ArithmeticException ignored) {
            return maxCraft;
        }

        KeyCounter[] scaledInputs = scaleTemplate(oneCopyTemplate, requested);
        if (scaledInputs == null) {
            return maxCraft;
        }

        final Object scaledPattern;
        try {
            scaledPattern = SCALED_PATTERN_CONSTRUCTOR.newInstance(base, combinedMultiplier);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MixinReflectionSupport.logReflectionFailure(
                    "construct EAEP scaled molecular-assembler pattern",
                    exception);
            return maxCraft;
        }

        boolean accepted;
        try {
            accepted = provider.pushPattern((IPatternDetails) scaledPattern, scaledInputs);
        } catch (RuntimeException exception) {
            MixinReflectionSupport.logReflectionFailure(
                    "dispatch EAEP Super Assembler Matrix batch",
                    exception);
            return maxCraft;
        }
        return accepted ? maxCraft - requested : maxCraft;
    }

    /** Returns an owned scaled copy and never mutates the borrowed one-copy template. */
    static @Nullable KeyCounter[] scaleTemplate(KeyCounter[] oneCopyTemplate, long copies) {
        if (oneCopyTemplate == null || copies <= 0L) {
            return null;
        }
        var scaled = new KeyCounter[oneCopyTemplate.length];
        try {
            for (int slot = 0; slot < oneCopyTemplate.length; slot++) {
                var source = oneCopyTemplate[slot];
                var target = new KeyCounter();
                scaled[slot] = target;
                if (source == null) {
                    continue;
                }
                for (var entry : source) {
                    target.add(entry.getKey(), Math.multiplyExact(entry.getLongValue(), copies));
                }
            }
        } catch (ArithmeticException ignored) {
            return null;
        }
        return scaled;
    }

    private static @Nullable UnwrappedPattern unwrap(IPatternDetails details) {
        IPatternDetails current = details;
        long multiplier = 1L;
        try {
            while (SCALED_PATTERN_CLASS.isInstance(current)) {
                Object next = GET_ORIGINAL.invoke(current);
                Object factor = GET_MULTIPLIER.invoke(current);
                if (!(next instanceof IPatternDetails original) || !(factor instanceof Number number)) {
                    return null;
                }
                multiplier = Math.multiplyExact(multiplier, number.longValue());
                current = original;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            MixinReflectionSupport.logReflectionFailure(
                    "unwrap EAEP scaled molecular-assembler pattern",
                    exception);
            return null;
        }
        return new UnwrappedPattern(current, multiplier);
    }

    private static @Nullable Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Constructor<?> findConstructor(
            @Nullable Class<?> owner,
            Class<?>... parameterTypes) {
        if (owner == null) return null;
        try {
            var constructor = owner.getConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable Method findMethod(@Nullable Class<?> owner, String name) {
        if (owner == null) return null;
        try {
            var method = owner.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private record UnwrappedPattern(IPatternDetails pattern, long multiplier) {
    }
}
