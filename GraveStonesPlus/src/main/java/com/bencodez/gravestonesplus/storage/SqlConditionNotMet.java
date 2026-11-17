package com.bencodez.gravestonesplus.storage;

/** A confirmed existing-row predicate miss, distinct from an ambiguous transport failure. */
public final class SqlConditionNotMet extends IllegalStateException {
    public SqlConditionNotMet() { super("Existing SQL grave predicate was not satisfied"); }
}
