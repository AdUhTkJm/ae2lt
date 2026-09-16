package com.moakiee.thunderbolt.api.channel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Stable registry for controller owners that inject channel capacity into the AE2 pathing graph. */
public final class ChannelSourceRegistry {

    private static final Map<String, Class<?>> SOURCES = new ConcurrentHashMap<>();

    private ChannelSourceRegistry() {
    }

    /**
     * Registers a source using a stable implementation ID.
     *
     * <p>Registering the same ID twice is rejected even if the classes match. Closing the returned
     * handle removes only the exact registration created by this call.
     */
    public static Registration registerController(String id, Class<?> controllerClass) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(controllerClass, "controllerClass");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Class<?> previous = SOURCES.putIfAbsent(id, controllerClass);
        if (previous != null) {
            throw new IllegalStateException("Channel source already registered: " + id);
        }
        return new Registration(id, controllerClass);
    }

    /** Compatibility shortcut whose stable ID is the controller's binary class name. */
    public static Registration registerController(Class<?> controllerClass) {
        Objects.requireNonNull(controllerClass, "controllerClass");
        return registerController(controllerClass.getName(), controllerClass);
    }

    public static boolean isChannelSource(Object owner) {
        if (owner == null) {
            return false;
        }
        return SOURCES.values().stream().anyMatch(type -> type.isInstance(owner));
    }

    public static boolean isChannelSourceClass(Class<?> ownerClass) {
        if (ownerClass == null) {
            return false;
        }
        return SOURCES.values().stream().anyMatch(type -> type.isAssignableFrom(ownerClass));
    }

    /** Idempotent lifecycle handle for one channel-source registration. */
    public static final class Registration implements AutoCloseable {
        private String id;
        private final Class<?> controllerClass;

        private Registration(String id, Class<?> controllerClass) {
            this.id = id;
            this.controllerClass = controllerClass;
        }

        @Override
        public void close() {
            String current = id;
            if (current != null) {
                SOURCES.remove(current, controllerClass);
                id = null;
            }
        }
    }
}
