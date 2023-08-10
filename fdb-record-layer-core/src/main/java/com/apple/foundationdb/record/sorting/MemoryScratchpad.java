/*
 * MemoryScratchpad.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.sorting;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorContinuation;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.sorting.MemorySortAdapter.MemorySortComparator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Collect keyed values into an in-memory data structure.
 * @param <K> type of key
 * @param <V> type of value
 * @param <M> type of data structure which must at least be a {@link Map}.
 */
@API(API.Status.EXPERIMENTAL)
public abstract class MemoryScratchpad<K, V, M extends Map<K, V>> {
    @Nonnull
    private final M map;
    @Nonnull
    private final MemorySortAdapter<K, V> adapter;
    @Nullable
    private final StoreTimer timer;

    private LoadResult<K> loadResult;

    protected MemoryScratchpad(@Nonnull final MemorySortAdapter<K, V> adapter,
                               @Nonnull final M map,
                               @Nullable final StoreTimer timer) {
        this.adapter = adapter;
        this.map = map;
        this.timer = timer;
    }

    @Nonnull
    public M getMap() {
        return map;
    }

    @Nonnull
    public MemorySortAdapter<K, V> getAdapter() {
        return adapter;
    }

    public void addKeyValue(K key, V value) {
        map.put(key, value);
    }

    public void addValue(V value) {
        addKeyValue(adapter.generateKey(value), value);
    }

    /** How the {@code getMaxRecordCountInMemory} is interpreted for {@link #load}. */
    public enum RecordCountInMemoryLimitMode {
        /** Map retains top {@code getMaxRecordCountInMemory} values and discards additional values. */
        DISCARD,
        /** Map retains {@code getMaxRecordCountInMemory} values and then stops. */
        STOP
    }

    /** The result of {@link #load}.
     * @param <K> the type of key
     */
    public static class LoadResult<K> {
        private final boolean full;
        @Nullable
        private final K nextMinimumKey;
        @Nonnull
        private final RecordCursorContinuation sourceContinuation;
        @Nonnull
        private final RecordCursor.NoNextReason sourceNoNextReason;

        public LoadResult(final boolean full, @Nullable final K nextMinimumKey, @Nonnull RecordCursorContinuation sourceContinuation, @Nonnull RecordCursor.NoNextReason sourceNoNextReason) {
            this.full = full;
            this.nextMinimumKey = nextMinimumKey;
            this.sourceContinuation = sourceContinuation;
            this.sourceNoNextReason = sourceNoNextReason;
        }

        public boolean isFull() {
            return full;
        }

        @Nullable
        public K getNextMinimumKey() {
            return nextMinimumKey;
        }

        @Nonnull
        public RecordCursorContinuation getSourceContinuation() {
            return sourceContinuation;
        }

        @Nonnull
        public RecordCursor.NoNextReason getSourceNoNextReason() {
            return sourceNoNextReason;
        }
    }

    /**
     * Load and sort records from a cursor.
     * @param source source cursor
     * @param minimumKey ignore cursor entries that are not greater than this key
     * @return the reason the load stopped
     */
    public CompletableFuture<LoadResult<K>> load(@Nonnull RecordCursor<V> source, @Nullable K minimumKey) {
        loadResult = null;
        final MemorySortComparator<K> comparator = adapter.getComparator(minimumKey);
        return AsyncUtil.whileTrue(() -> source.onNext().thenApply(sourceResult -> {
            if (!sourceResult.hasNext()) {
                loadResult = new LoadResult<>(false, comparator.nextMinimumKey(), sourceResult.getContinuation(), sourceResult.getNoNextReason());
                return false;
            }
            final long startTime = System.nanoTime();
            try {
                V value = sourceResult.get();
                K key = adapter.generateKey(value);

                if (comparator.compareToMinimumKey(key) > 0) {
                    addKeyValue(key, value);
                }

                if (map.size() <= adapter.getMaxRecordCountInMemory()) {
                    return true;
                }
                switch (adapter.getRecordCountInMemoryLimitMode()) {
                    case DISCARD:
                        removeLast(key);
                        return true;
                    case STOP:
                        // TODO: Need some more NoNextReason's
                        loadResult = new LoadResult<>(true, comparator.nextMinimumKey(), sourceResult.getContinuation(), RecordCursor.NoNextReason.SCAN_LIMIT_REACHED);
                        return false;
                    default:
                        throw new RecordCoreArgumentException("Unknown size limit mode: " + adapter.getRecordCountInMemoryLimitMode());
                }
            } finally {
                if (timer != null) {
                    timer.recordSinceNanoTime(SortEvents.Events.MEMORY_SORT_STORE_RECORD, startTime);
                }
            }
        })).thenApply(vignore -> loadResult);
    }

    public abstract void removeLast(@Nonnull K currentKey);

    @Nonnull
    public abstract Collection<V> tailValues(@Nullable K minimumKey);
}
