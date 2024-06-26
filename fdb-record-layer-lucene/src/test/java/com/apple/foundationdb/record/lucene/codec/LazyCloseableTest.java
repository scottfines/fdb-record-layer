/*
 * LazyCloseableTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2023 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.lucene.codec;

import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.async.MoreAsyncUtil;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated // To avoid contention on the thread pool with other tests running in parallel
class LazyCloseableTest {

    @Nonnull
    static <T> Deque<T> collectFromMultipleThreads(int concurrency, @Nonnull Supplier<T> supplier) throws InterruptedException {
        // Set up one thread for each concurrent creation we want to execute
        final List<Thread> threads = new ArrayList<>(concurrency);
        final CountDownLatch latch = new CountDownLatch(concurrency);
        Deque<T> results = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < concurrency; i++) {
            Thread t = new Thread(() -> {
                try {
                    // Decrement the thread started latch to signal that this thread has begun, then
                    // wait for all of the other threads to begin before proceeding
                    latch.countDown();
                    latch.await();

                    T value = supplier.get();
                    results.add(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.setName("collectFromMultipleThreads-" + i);
            t.start();
            threads.add(t);
        }

        // Once we've set up all the threads, wait on them all to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }
        MatcherAssert.assertThat(results, Matchers.hasSize(concurrency));
        return results;
    }

    @Test
    void testOpensLazilyExactlyOnce() throws IOException {
        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        try (LazyCloseable<CountingCloseable> opener = LazyCloseable.supply(
                () -> new CountingCloseable(openCounter, closeCounter))) {
            assertEquals(0, openCounter.get());
            assertEquals(1, opener.get().openCounts);
            assertEquals(1, opener.get().openCounts);
            assertEquals(1, opener.getUnchecked().openCounts);
            assertSame(opener.get(), opener.get());
            assertEquals(1, openCounter.get());
        }
        assertEquals(1, closeCounter.get());
    }

    @Test
    void testOpensLazilyExactlyOnceThreaded() throws IOException, InterruptedException {
        AtomicInteger starts = new AtomicInteger(0);
        AtomicInteger ends = new AtomicInteger(0);
        AtomicInteger opens = new AtomicInteger(0);
        AtomicInteger closes = new AtomicInteger(0);
        final int concurrency = 100;
        final LazyCloseable<CountingCloseable> opener = LazyCloseable.supply(() -> {
            starts.incrementAndGet();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError("Timed out waiting for latch");
            }
            ends.incrementAndGet();
            return new CountingCloseable(opens, closes);
        });

        ConcurrentHashMap<Thread, Integer> threads = new ConcurrentHashMap<>();
        final Deque<CountingCloseable> allValues = collectFromMultipleThreads(concurrency, () -> {
            // Get the value from the opener before updating the threads map just to
            // avoid map accesses incidentally serializing accesses to the CountingCloseable
            CountingCloseable value = opener.getUnchecked();
            threads.compute(Thread.currentThread(), (k, v) -> v == null ? 1 : v + 1);
            return value;
        });

        CountingCloseable closeable = allValues.getFirst();
        assertNotNull(closeable);
        MatcherAssert.assertThat(allValues, Matchers.everyItem(Matchers.sameInstance(closeable)));

        assertEquals(1, starts.get());
        assertEquals(1, ends.get());
        assertEquals(1, opens.get());
        assertEquals(0, closes.get());
        for (CountingCloseable allValue : allValues) {
            allValue.close();
        }
        assertEquals(concurrency, closes.get());

        // Make sure that each execution happens in its own thread
        MatcherAssert.assertThat(threads.keySet(), Matchers.hasSize(concurrency));
        MatcherAssert.assertThat(threads.values(), Matchers.everyItem(Matchers.equalTo(1)));
    }

    @Test
    void testForkJoinPoolDeadlock() throws ExecutionException, InterruptedException, TimeoutException {
        // Lucene heavily uses `LazyCloseable` to Lazily open inputs from FDB. For StoredFieldsFormat (at the time
        // LazyCloseable was added), Lucene would "open" one, but not actually call get, it would then fork a bunch of
        // threads, in a forkJoinPool, each of which would in turn, try to call get. This created a deadlock situation when
        // LazyOpener was implemented with Suppliers.memoize. This test explicitly tests this, and with the previous
        // attempt that had Suppliers.memoize, this test would timeout, instead of taking the 2 seconds in the delayed
        // future.
        final ForkJoinPool forkJoinPool = new ForkJoinPool(2);
        final AtomicInteger openCounter = new AtomicInteger(0);
        LazyCloseable<Closeable> initial = LazyCloseable.supply(() -> {
            int openCount = openCounter.incrementAndGet();
            try {
                return CompletableFuture.runAsync(() -> { }, forkJoinPool)
                        .thenCompose(ignored -> MoreAsyncUtil.delayedFuture(2, TimeUnit.SECONDS))
                        .thenApplyAsync(v -> new Closeable() {
                            @Override
                            public void close() throws IOException {

                            }

                            @Override
                            public String toString() {
                                return "Opened " + openCount;
                            }
                        }, forkJoinPool).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        final List<CompletableFuture<String>> result = IntStream.range(0, 50).parallel()
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> initial.getUnchecked().toString() + " " + i, forkJoinPool))
                .collect(Collectors.toList());
        final List<String> strings = AsyncUtil.getAll(result).get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat(strings, Matchers.containsInAnyOrder(IntStream.range(0, 50)
                .mapToObj(i -> Matchers.is("Opened 1 " + i)).collect(Collectors.toList())));
    }

    @Test
    void testCloseDoesNotOpen() throws IOException {
        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        LazyCloseable.supply(() -> new CountingCloseable(openCounter, closeCounter)).close();
        assertEquals(0, openCounter.get());
    }

    @Test
    void testCloseCloses() throws IOException {
        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        try (LazyCloseable<CountingCloseable> opener = LazyCloseable.supply(() -> new CountingCloseable(openCounter, closeCounter))) {
            opener.get();
        }
        assertEquals(1, openCounter.get());
        assertEquals(1, closeCounter.get());
    }

    @Test
    void testCloseMultipleTimes() throws IOException {
        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        LazyCloseable<CountingCloseable> opener = LazyCloseable.supply(() -> new CountingCloseable(openCounter, closeCounter));
        try {
            opener.get();
        } finally {
            for (int i = 0; i < 5; i++) {
                opener.close();
            }
        }
        assertEquals(1, openCounter.get());
        assertEquals(5, closeCounter.get());
    }

    @Test
    void testCloseFails() throws IOException {
        final AtomicInteger openCounter = new AtomicInteger(0);
        final AtomicInteger closeCounter = new AtomicInteger(0);
        LazyCloseable<CountingCloseable> opener = LazyCloseable.supply(() -> new CountingCloseable(openCounter, closeCounter, true));
        try {
            opener.get();
        } finally {
            assertThrows(IOException.class, opener::close, "an error");
            assertThrows(IOException.class, opener::close, "an error");
        }
        assertEquals(1, openCounter.get());
        assertEquals(0, closeCounter.get());
    }

    @Test
    void testThrowsIoException() throws IOException {
        IOException thrownException = new IOException("test foo");
        try (LazyCloseable<Closeable> opener = failingOpener(thrownException)) {
            final IOException resultingException = assertThrows(IOException.class, opener::get);
            assertSame(thrownException, resultingException);
        } // close should not throw
    }

    @Test
    void testThrowsUncheckedIoException() throws IOException {
        IOException thrownException = new IOException("test foo");
        try (LazyCloseable<Closeable> opener = failingOpener(thrownException)) {
            UncheckedIOException resultingException = assertThrows(UncheckedIOException.class, opener::getUnchecked);
            assertSame(thrownException, resultingException.getCause());
        } // close should not throw
    }

    @Test
    void testUnusedDoesNotThrowOnClose() {
        IOException thrownException = new IOException("test foo");
        LazyCloseable<Closeable> opener = failingOpener(thrownException);
        assertDoesNotThrow(opener::close);
    }

    @Nonnull
    private static LazyCloseable<Closeable> failingOpener(final IOException thrownException) {
        return LazyCloseable.supply(() -> {
            throw thrownException;
        });
    }

    private static class CountingCloseable implements Closeable {
        final int openCounts;
        final AtomicInteger closeCounter;
        final boolean failOnClose;

        private CountingCloseable(final AtomicInteger openCounter, final AtomicInteger closeCounter) {
            this.openCounts = openCounter.incrementAndGet();
            this.closeCounter = closeCounter;
            this.failOnClose = false;
        }

        private CountingCloseable(final AtomicInteger openCounter, final AtomicInteger closeCounter, boolean failOnClose) {
            this.openCounts = openCounter.incrementAndGet();
            this.closeCounter = closeCounter;
            this.failOnClose = failOnClose;
        }

        @Override
        public void close() throws IOException {
            if (failOnClose) {
                throw new IOException("an error");
            } else {
                closeCounter.incrementAndGet();
            }
        }
    }
}
