/*
 * FDBTransactionContext.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.provider.common.StoreTimer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Wrapper class for an open FDB {@link Transaction}.
 *
 * @see FDBRecordContext
 */
@API(API.Status.STABLE)
public class FDBTransactionContext {
    private final Executor executor;

    @Nonnull
    protected final FDBDatabase database;
    @Nullable
    protected Transaction transaction;
    @Nullable
    protected FDBStoreTimer timer;

    protected FDBTransactionContext(@Nonnull FDBDatabase database, @Nonnull Transaction transaction, @Nullable FDBStoreTimer timer) {
        this.database = database;
        this.transaction = transaction;
        this.executor = transaction.getExecutor();
        this.timer = timer;

        if (timer != null) {
            timer.increment(FDBStoreTimer.Counts.OPEN_CONTEXT);
        }
    }

    @Nonnull
    public FDBDatabase getDatabase() {
        return database;
    }

    @Nonnull
    public Transaction ensureActive() {
        return transaction;
    }

    @Nonnull
    public Executor getExecutor() {
        return executor;
    }

    @Nonnull
    public CompletableFuture<Long> getApproximateTransactionSize() {
        return transaction.getApproximateSize();
    }

    /**
     * Get the FDB API version associated with this transaction. This is an internal
     * method that should be used within the Record Layer to accommodate changes in
     * underlying FDB behavior that are dictated by the API version.
     *
     * @return the transaction's associated FDB API version
     * @see APIVersion
     */
    @API(API.Status.INTERNAL)
    public APIVersion getAPIVersion() {
        return database.getAPIVersion();
    }

    /**
     * Determine whether the API version of this transaction is at least as new as
     * the provided API version. This is an internal method that should be used
     * to gate features requiring certain FDB API versions for support from the database.
     *
     * @param apiVersion the FDB API version to compare against
     * @return whether the transaction's API version is at least as new as the provided API version
     * @see #getAPIVersion()
     * @see APIVersion
     */
    @API(API.Status.INTERNAL)
    public boolean isAPIVersionAtLeast(@Nonnull APIVersion apiVersion) {
        return getAPIVersion().isAtLeast(apiVersion);
    }

    @Nullable
    public FDBStoreTimer getTimer() {
        return timer;
    }

    public void setTimer(@Nullable FDBStoreTimer timer) {
        this.timer = timer;
    }

    public <T> CompletableFuture<T> instrument(StoreTimer.Event event, CompletableFuture<T> future) {
        if (timer != null) {
            future = timer.instrument(event, future, getExecutor());
        }
        return future;
    }

    public <T> CompletableFuture<T> instrument(Set<StoreTimer.Event> event, CompletableFuture<T> future) {
        if (timer != null) {
            future = timer.instrument(event, future, getExecutor());
        }
        return future;
    }

    public <T> CompletableFuture<T> instrument(StoreTimer.Event event, CompletableFuture<T> future, long startTime) {
        if (timer != null) {
            future = timer.instrument(event, future, getExecutor(), startTime);
        }
        return future;
    }

    public <T> RecordCursor<T> instrument(StoreTimer.Event event, RecordCursor<T> inner) {
        if (timer != null) {
            inner = timer.instrument(event, inner);
        }
        return inner;
    }

    /**
     * Record the amount of time an event took to run.
     *
     * @param event the event being recorded
     * @param timeDelta the time the event took to complete
     * @see StoreTimer#record(StoreTimer.Event, long) StoreTimer.record()
     */
    public void record(@Nonnull StoreTimer.Event event, long timeDelta) {
        if (timer != null) {
            timer.record(event, timeDelta);
        }
    }

    /**
     * Record that an event occurred one time.
     *
     * @param count the event being recorded
     * @see StoreTimer#increment(StoreTimer.Count) StoreTimer.increment()
     */
    public void increment(@Nonnull StoreTimer.Count count) {
        if (timer != null) {
            timer.increment(count);
        }
    }

    /**
     * Record that an event occurred one or more times.
     *
     * @param count the event being recorded
     * @param amount the amount to increment the event
     * @see StoreTimer#increment(StoreTimer.Count, int) StoreTimer.increment()
     */
    public void increment(@Nonnull StoreTimer.Count count, int amount) {
        if (timer != null) {
            timer.increment(count, amount);
        }
    }
}
