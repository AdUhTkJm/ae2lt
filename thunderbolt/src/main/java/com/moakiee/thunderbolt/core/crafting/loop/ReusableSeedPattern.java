package com.moakiee.thunderbolt.core.crafting.loop;

import appeng.api.stacks.AEKey;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockPattern;
import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Contracted pattern that requires one reusable seed pool per submitted job. */
public interface ReusableSeedPattern extends ReusableStockPattern {

    Object reusableSeedStorageScope();

    UUID reusableSeedGroupId();

    Set<AEKey> reusableSeedCycleKeys();

    boolean hasSingleSeedInputPerMember();

    Map<AEKey, Long> totalReusableSeedRequirements();

    default boolean acceptsReusableSeedVariant(AEKey planned, AEKey actual) {
        return planned != null && planned.equals(actual);
    }

    default ReusableStockSource reusableStockSource() {
        Object storage = Objects.requireNonNull(
                reusableSeedStorageScope(), "reusableSeedStorageScope");
        Object pool = hasSingleSeedInputPerMember()
                ? new SharedPool(storage)
                : new DedicatedPool(storage, reusableSeedGroupId());
        return new ReusableStockSource(storage, pool, reusableSeedGroupId());
    }

    @Override
    default Map<AEKey, Long> reusableStockRequirements() {
        return totalReusableSeedRequirements();
    }

    @Override
    default boolean acceptsReusableStockVariant(AEKey planned, AEKey actual) {
        return acceptsReusableSeedVariant(planned, actual);
    }

    @Override
    default Map<AEKey, Long> availableReusableStockSnapshot() {
        return availableReusableSeedSnapshot();
    }

    default Map<AEKey, Long> availableReusableSeedSnapshot() {
        return Map.of();
    }

    record SharedPool(Object storageScope) {
        public SharedPool {
            Objects.requireNonNull(storageScope, "storageScope");
        }
    }

    record DedicatedPool(Object storageScope, UUID groupId) {
        public DedicatedPool {
            Objects.requireNonNull(storageScope, "storageScope");
            Objects.requireNonNull(groupId, "groupId");
        }
    }
}
