package com.moakiee.thunderbolt.core.crafting.support;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;

import com.moakiee.thunderbolt.core.crafting.pattern.IBatchExecutionPattern;
import com.moakiee.thunderbolt.core.crafting.pattern.IProviderLookupPattern;

class CraftingPatternDelegatesTest {

    @Test
    void executionStopsAtThePhysicalSemanticPatternWhileProviderLookupContinues() {
        var provider = pattern();
        var execution = providerWrapper(() -> provider);
        var task = taskWrapper(() -> execution, () -> execution);

        assertSame(execution, CraftingPatternDelegates.forBatchExecution(task));
        assertSame(provider, CraftingPatternDelegates.forProviderLookup(task));
    }

    @Test
    void ordinaryPatternsKeepTheirIdentityForBothRoles() {
        var ordinary = pattern();

        assertSame(ordinary, CraftingPatternDelegates.forBatchExecution(ordinary));
        assertSame(ordinary, CraftingPatternDelegates.forProviderLookup(ordinary));
    }

    @Test
    void invalidExecutionDelegationFailsClosed() {
        var self = new IPatternDetails[1];
        self[0] = executionWrapper(() -> self[0]);
        assertThrows(IllegalStateException.class,
                () -> CraftingPatternDelegates.forBatchExecution(self[0]));

        var cycle = new IPatternDetails[2];
        cycle[0] = executionWrapper(() -> cycle[1]);
        cycle[1] = executionWrapper(() -> cycle[0]);
        assertThrows(IllegalStateException.class,
                () -> CraftingPatternDelegates.forBatchExecution(cycle[0]));

        var missing = executionWrapper(() -> null);
        assertThrows(NullPointerException.class,
                () -> CraftingPatternDelegates.forBatchExecution(missing));
        assertThrows(NullPointerException.class,
                () -> CraftingPatternDelegates.forBatchExecution(null));
    }

    private static IPatternDetails pattern() {
        return proxy(new Class<?>[]{IPatternDetails.class}, () -> null, () -> null);
    }

    private static IPatternDetails providerWrapper(Supplier<IPatternDetails> providerDelegate) {
        return proxy(
                new Class<?>[]{IPatternDetails.class, IProviderLookupPattern.class},
                () -> null,
                providerDelegate);
    }

    private static IPatternDetails executionWrapper(Supplier<IPatternDetails> executionDelegate) {
        return proxy(
                new Class<?>[]{IPatternDetails.class, IBatchExecutionPattern.class},
                executionDelegate,
                () -> null);
    }

    private static IPatternDetails taskWrapper(
            Supplier<IPatternDetails> executionDelegate,
            Supplier<IPatternDetails> providerDelegate) {
        return proxy(
                new Class<?>[]{
                        IPatternDetails.class,
                        IBatchExecutionPattern.class,
                        IProviderLookupPattern.class
                },
                executionDelegate,
                providerDelegate);
    }

    private static IPatternDetails proxy(
            Class<?>[] interfaces,
            Supplier<IPatternDetails> executionDelegate,
            Supplier<IPatternDetails> providerDelegate) {
        return (IPatternDetails) Proxy.newProxyInstance(
                CraftingPatternDelegatesTest.class.getClassLoader(),
                interfaces,
                (proxy, method, args) -> switch (method.getName()) {
                    case "batchExecutionPattern" -> executionDelegate.get();
                    case "providerLookupPattern" -> providerDelegate.get();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "pattern@" + Integer.toHexString(System.identityHashCode(proxy));
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
