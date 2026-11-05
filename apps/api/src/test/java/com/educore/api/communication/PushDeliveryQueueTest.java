package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PushDeliveryQueueTest {
    @Test
    void retriesUseBoundedExponentialBackoffAndStableJitter() {
        UUID token=new UUID(0,59);
        assertThat(PushDeliveryQueue.retryDelay(1,0,token)).isEqualTo(119);
        assertThat(PushDeliveryQueue.retryDelay(2,0,token)).isEqualTo(179);
        assertThat(PushDeliveryQueue.retryDelay(7,0,token)).isEqualTo(3899);
        assertThat(PushDeliveryQueue.retryDelay(8,0,token)).isEqualTo(3899);
        assertThat(PushDeliveryQueue.retryDelay(1,3600,token)).isEqualTo(3600);
        assertThat(PushDeliveryQueue.retryDelay(1,Long.MAX_VALUE,token)).isEqualTo(86400);
        assertThat(PushDeliveryQueue.retryDelay(1,Long.MIN_VALUE,new UUID(0,Long.MIN_VALUE))).isBetween(60L,119L);
    }
}
