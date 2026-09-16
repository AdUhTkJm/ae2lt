package com.moakiee.thunderbolt.api.crafting.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

class BatchCraftingProviderContractTest {

    @Test
    void jobOverloadDelegatesToTheEstablishedFlatContract() {
        var provider = proxy((proxy, method, args) -> {
            if (method.getName().equals("pushBatch") && method.getParameterCount() == 3) {
                return 4L;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return defaultValue(method.getReturnType());
        });

        assertEquals(4L, provider.pushBatch(null, null, 7L, null));
    }

    @Test
    void sharedInputsRequireExplicitOptIn() {
        var provider = proxy((proxy, method, args) -> {
            if (method.getName().equals("supportsSharedBatchInputs")) {
                return true;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return defaultValue(method.getReturnType());
        });

        assertTrue(provider.supportsSharedBatchInputs());
    }

    @Test
    void providerWithoutAnOptInKeepsOrdinaryInputSemantics() {
        var ordinaryProvider = proxy((proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return defaultValue(method.getReturnType());
        });

        assertFalse(ordinaryProvider.supportsSharedBatchInputs());
    }

    private static IBatchCraftingProvider proxy(InvocationHandler handler) {
        return (IBatchCraftingProvider) Proxy.newProxyInstance(
                BatchCraftingProviderContractTest.class.getClassLoader(),
                new Class<?>[]{IBatchCraftingProvider.class},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
