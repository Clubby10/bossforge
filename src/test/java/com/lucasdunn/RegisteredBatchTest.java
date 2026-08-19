package com.lucasdunn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RegisteredBatchTest {

    @Test
    public void copiedPhysicalStacksCannotExceedRegistryAllowance() {
        RegisteredBatch batch = new RegisteredBatch("void_shard", 64);

        assertEquals(64, batch.spendableAmount(128));
        batch.consume(16);
        assertEquals(48, batch.spendableAmount(112));
        batch.consume(48);
        assertEquals(0, batch.spendableAmount(64));
    }

    @Test(expected = IllegalArgumentException.class)
    public void consumptionCannotExceedRemainingAllowance() {
        RegisteredBatch batch = new RegisteredBatch("void_shard", 16);
        batch.consume(17);
    }
}
