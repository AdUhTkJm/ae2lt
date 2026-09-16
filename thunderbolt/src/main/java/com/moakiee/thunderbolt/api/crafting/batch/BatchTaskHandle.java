package com.moakiee.thunderbolt.api.crafting.batch;

import appeng.api.crafting.IPatternDetails;

public interface BatchTaskHandle {
    IPatternDetails details();

    long getValue();

    void setValue(long value);
}
