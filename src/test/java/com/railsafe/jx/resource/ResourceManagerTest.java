package com.railsafe.jx.resource;

import com.railsafe.jx.model.Train;
import com.railsafe.jx.util.EventLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ResourceManagerTest {
    @Test void segmentCannotBeOwnedByTwoTrains() {
        ResourceManager manager = new ResourceManager(new EventLogger());
        assertTrue(manager.tryAcquire("T1", "A"));
        assertFalse(manager.tryAcquire("T2", "A"));
        assertEquals("T1", manager.ownerOf("A"));
        assertTrue(manager.release("T1", "A"));
        assertTrue(manager.tryAcquire("T2", "A"));
        assertEquals("T2", manager.ownerOf("A"));
        assertTrue(manager.isOwnershipMapConsistent());
        manager.shutdown();
    }

    @Test void waitingTrainAcquiresOnlyAfterCurrentOwnerReleases() throws Exception {
        EventLogger logger = new EventLogger();
        ResourceManager manager = new ResourceManager(logger);
        Train first = train("T1");
        Train second = train("T2");
        CountDownLatch firstOwns = new CountDownLatch(1);
        CountDownLatch permitRelease = new CountDownLatch(1);
        CountDownLatch secondOwns = new CountDownLatch(1);
        AtomicBoolean acquiredEarly = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                assertEquals(AcquireResult.ACQUIRED, manager.acquire("T1", "A", first));
                firstOwns.countDown();
                try { assertTrue(permitRelease.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                manager.release("T1", "A");
            });
            assertTrue(firstOwns.await(1, TimeUnit.SECONDS));
            pool.submit(() -> {
                assertEquals(AcquireResult.ACQUIRED, manager.acquire("T2", "A", second));
                acquiredEarly.set(permitRelease.getCount() > 0);
                secondOwns.countDown();
                manager.release("T2", "A");
            });
            Thread.sleep(180);
            assertFalse(secondOwns.await(80, TimeUnit.MILLISECONDS), "second train must remain blocked while A is owned");
            permitRelease.countDown();
            assertTrue(secondOwns.await(1, TimeUnit.SECONDS));
            assertFalse(acquiredEarly.get());
            assertTrue(manager.isOwnershipMapConsistent());
        } finally {
            permitRelease.countDown();
            pool.shutdownNow();
            manager.shutdown();
        }
    }

    private static Train train(String id) {
        return new Train(id, id, 1, List.of("A", "B"), List.of(), "S1");
    }
}
