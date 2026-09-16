package com.moakiee.thunderbolt.core.channel;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import appeng.blockentity.networking.ControllerBlockEntity;

import com.moakiee.thunderbolt.api.channel.ChannelSourceRegistry;

public final class ControllerMachineNodeLookup {

    private ControllerMachineNodeLookup() {
    }

    public static boolean hasRegisteredSourceNodes(Map<Class<?>, ? extends Collection<?>> machines) {
        return hasMatchingControllerNodes(machines, ControllerMachineNodeLookup::isRegisteredSourceClass);
    }

    static boolean hasMatchingControllerNodes(Map<Class<?>, ? extends Collection<?>> machines,
                                              Predicate<Class<?>> controllerClassMatcher) {
        for (var entry : machines.entrySet()) {
            if (controllerClassMatcher.test(entry.getKey()) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static Set<Class<?>> normalizedMachineClasses(Map<Class<?>, ? extends Collection<?>> machines) {
        return normalizedMachineClasses(
                machines,
                ControllerMachineNodeLookup::isRegisteredSourceClass,
                ControllerBlockEntity.class);
    }

    static Set<Class<?>> normalizedMachineClasses(Map<Class<?>, ? extends Collection<?>> machines,
                                                  Predicate<Class<?>> controllerClassMatcher,
                                                  Class<?> controllerBaseClass) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        boolean hasRegisteredSources = false;

        for (var machineClass : machines.keySet()) {
            if (controllerClassMatcher.test(machineClass)) {
                hasRegisteredSources = true;
            } else {
                classes.add(machineClass);
            }
        }

        if (hasRegisteredSources) {
            classes.add(controllerBaseClass);
        }

        return classes;
    }

    public static <T> List<T> controllerNodes(Map<Class<?>, ? extends Collection<T>> machines) {
        return controllerNodes(
                machines,
                ControllerMachineNodeLookup::isRegisteredSourceClass,
                ControllerBlockEntity.class);
    }

    static <T> List<T> controllerNodes(Map<Class<?>, ? extends Collection<T>> machines,
                                       Predicate<Class<?>> controllerClassMatcher,
                                       Class<?> controllerBaseClass) {
        Set<T> nodes = new LinkedHashSet<>();

        var vanillaControllers = machines.get(controllerBaseClass);
        if (vanillaControllers != null) {
            nodes.addAll(vanillaControllers);
        }

        for (var entry : machines.entrySet()) {
            if (controllerClassMatcher.test(entry.getKey())) {
                nodes.addAll(entry.getValue());
            }
        }

        return List.copyOf(nodes);
    }

    private static boolean isRegisteredSourceClass(Class<?> machineClass) {
        return ChannelSourceRegistry.isChannelSourceClass(machineClass);
    }
}
