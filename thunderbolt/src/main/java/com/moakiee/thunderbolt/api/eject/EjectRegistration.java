package com.moakiee.thunderbolt.api.eject;

/** Idempotent registration handle. Closing it unregisters that endpoint. */
@FunctionalInterface
public interface EjectRegistration extends AutoCloseable {

    @Override
    void close();
}
