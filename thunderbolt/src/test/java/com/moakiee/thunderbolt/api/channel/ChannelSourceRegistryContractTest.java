package com.moakiee.thunderbolt.api.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChannelSourceRegistryContractTest {

    @Test
    void thirdPartyRegistrationHasStableIdentityAndClosableLifecycle() {
        var owner = new ThirdPartyController();
        var registration = ChannelSourceRegistry.registerController(
                "contract-test:third-party-controller", ThirdPartyController.class);
        try {
            assertTrue(ChannelSourceRegistry.isChannelSource(owner));
            assertTrue(ChannelSourceRegistry.isChannelSourceClass(ThirdPartySubclass.class));
            assertThrows(IllegalStateException.class, () -> ChannelSourceRegistry.registerController(
                    "contract-test:third-party-controller", OtherController.class));
        } finally {
            registration.close();
            registration.close();
        }
        assertFalse(ChannelSourceRegistry.isChannelSource(owner));
    }

    private static class ThirdPartyController {
    }

    private static final class ThirdPartySubclass extends ThirdPartyController {
    }

    private static final class OtherController {
    }
}
