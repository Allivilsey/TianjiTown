package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvisionCoordinatorTest {
    @Test
    void mergesDuplicateRequestsUntilProvisionFinishes() {
        ProvisionCoordinator coordinator = new ProvisionCoordinator();
        UUID applicationId = UUID.randomUUID();

        assertTrue(coordinator.tryBegin(applicationId));
        assertFalse(coordinator.tryBegin(applicationId));

        coordinator.finish(applicationId);
        assertTrue(coordinator.tryBegin(applicationId));
    }

    @Test
    void admitsOnlyOneConcurrentRequestForAnApplication() throws Exception {
        ProvisionCoordinator coordinator = new ProvisionCoordinator();
        UUID applicationId = UUID.randomUUID();
        int requestCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.tryBegin(applicationId);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        } finally {
            executor.shutdownNow();
        }
    }
}
