/*
 * LuceneIndexValidationTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2024 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.record.IndexEntry;
import com.apple.foundationdb.record.LoggableTimeoutException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TestRecordsGroupedParentChildProto;
import com.apple.foundationdb.record.TestRecordsTextProto;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.lucene.directory.AgilityContext;
import com.apple.foundationdb.record.lucene.directory.FDBDirectory;
import com.apple.foundationdb.record.lucene.directory.FDBDirectoryLockFactory;
import com.apple.foundationdb.record.lucene.directory.FDBDirectoryWrapper;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.JoinedRecordTypeBuilder;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.metadata.expressions.ThenKeyExpression;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreConcurrentTestBase;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerState;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintenanceFilter;
import com.apple.foundationdb.record.provider.foundationdb.OnlineIndexer;
import com.apple.foundationdb.record.provider.foundationdb.properties.RecordLayerPropertyStorage;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.plan.QueryPlanner;
import com.apple.foundationdb.record.util.pair.Pair;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.RandomizedTestUtils;
import com.apple.test.SuperSlow;
import com.apple.test.Tags;
import com.apple.test.TestConfigurationUtils;
import org.apache.lucene.store.Lock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.apple.foundationdb.record.lucene.LuceneIndexOptions.INDEX_PARTITION_BY_FIELD_NAME;
import static com.apple.foundationdb.record.lucene.LuceneIndexOptions.INDEX_PARTITION_HIGH_WATERMARK;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concat;
import static com.apple.foundationdb.record.metadata.Key.Expressions.field;
import static com.apple.foundationdb.record.metadata.Key.Expressions.function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of the consistency of the Lucene Index.
 */
@Tag(Tags.RequiresFDB)
public class LuceneIndexMaintenanceTest extends FDBRecordStoreConcurrentTestBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneIndexMaintenanceTest.class);

    public LuceneIndexMaintenanceTest() {
        super(null);
    }

    static Stream<Arguments> configurationArguments() {
        // This has found situations that should have explicit tests:
        //      1. Multiple groups
        //      2. When the size of first partition is exactly highWatermark+repartitionCount
        return Stream.concat(
                Stream.of(
                        // there's not much special about which flags are enabled and the numbers are used, it's just
                        // to make sure we have some variety, and make sure we have a test with each boolean true, and
                        // false.
                        // For partitionHighWatermark vs repartitionCount it is important to have both an even factor,
                        // and not.
                        Arguments.of(true, false, false, 13, 3, 20, 9237590782644L),
                        Arguments.of(true, true, true, 10, 2, 23, -644766138635622644L),
                        Arguments.of(false, true, true, 11, 4, 20, -1089113174774589435L),
                        Arguments.of(false, false, false, 5, 1, 18, 6223372946177329440L),
                        Arguments.of(true, false, false, 14, 6, 0, 2451719304283565963L)),
                RandomizedTestUtils.randomArguments(random ->
                        Arguments.of(random.nextBoolean(),
                                random.nextBoolean(),
                                random.nextBoolean(),
                                random.nextInt(20) + 2,
                                random.nextInt(10) + 1,
                                0,
                                random.nextLong())));
    }

    @ParameterizedTest(name = "randomizedRepartitionTest({argumentsWithNames})")
    @MethodSource("configurationArguments")
    void randomizedRepartitionTest(boolean isGrouped,
                                   boolean isSynthetic,
                                   boolean primaryKeySegmentIndexEnabled,
                                   int partitionHighWatermark,
                                   int repartitionCount,
                                   int minDocumentCount,
                                   long seed) throws IOException {
        // TODO run with both
        Random random = new Random(seed);
        final Map<String, String> options = Map.of(
                LuceneIndexOptions.INDEX_PARTITION_BY_FIELD_NAME, isSynthetic ? "parent.timestamp" : "timestamp",
                LuceneIndexOptions.INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(partitionHighWatermark),
                LuceneIndexOptions.PRIMARY_KEY_SEGMENT_INDEX_V2_ENABLED, String.valueOf(primaryKeySegmentIndexEnabled));
        LOGGER.info(KeyValueLogMessage.of("Running randomizedRepartitionTest",
                "isGrouped", isGrouped,
                "isSynthetic", isSynthetic,
                "repartitionCount", repartitionCount,
                "options", options,
                "seed", seed));

        final RecordMetaDataBuilder metaDataBuilder = createBaseMetaDataBuilder();
        final KeyExpression rootExpression = createRootExpression(isGrouped, isSynthetic);
        Index index = addIndex(isSynthetic, rootExpression, options, metaDataBuilder);
        final RecordMetaData metadata = metaDataBuilder.build();
        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context -> createOrOpenRecordStore(context, metadata);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, repartitionCount)
                .addProp(LuceneRecordContextProperties.LUCENE_MAX_DOCUMENTS_TO_MOVE_DURING_REPARTITIONING, random.nextInt(1000) + repartitionCount)
                .addProp(LuceneRecordContextProperties.LUCENE_MERGE_SEGMENTS_PER_TIER, (double)random.nextInt(10) + 2) // it must be at least 2.0
                .build();

        // Generate random documents
        final Map<Tuple, Map<Tuple, Tuple>> ids = new HashMap<>();
        generateDocuments(isGrouped, isSynthetic, minDocumentCount, random, contextProps, schemaSetup, random.nextInt(15) + 1, ids);

        explicitMergeIndex(index, contextProps, schemaSetup);

        new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                .validate(index, ids, repartitionCount, isSynthetic ? "child_str_value:forth" : "text_value:about");

        if (isGrouped) {
            validateDeleteWhere(isSynthetic, repartitionCount, ids, contextProps, schemaSetup, index);
        }
    }

    static Stream<Arguments> manyDocumentsArgumentsSlow() {
        return Stream.concat(
                Stream.of(Arguments.of(true, true, true, 80, 2, 200, 234809),
                // I don't know why, but this took over an hour, I'm hoping my laptop slept, but I don't see it
                Arguments.of(false, true, false, 50, 8, 212, 3125111852333110588L)),
                RandomizedTestUtils.randomArguments(random ->
                        Arguments.of(random.nextBoolean(),
                                random.nextBoolean(),
                                random.nextBoolean(),
                                // We want to have a high partitionHighWatermark so that the underlying lucene indexes
                                // actually end up with many records, and so that we don't end up with a ton of partitions
                                random.nextInt(300) + 50,
                                random.nextInt(10) + 1,
                                random.nextInt(200) + 100,
                                random.nextLong())));
    }

    @ParameterizedTest
    @MethodSource("manyDocumentsArgumentsSlow")
    @SuperSlow
    void manyDocumentSlow(boolean isGrouped,
                          boolean isSynthetic,
                          boolean primaryKeySegmentIndexEnabled,
                          int partitionHighWatermark,
                          int repartitionCount,
                          int loopCount,
                          long seed) throws IOException {
        manyDocument(isGrouped, isSynthetic, primaryKeySegmentIndexEnabled, partitionHighWatermark,
                repartitionCount, loopCount, 10, seed);
    }


    static Stream<Arguments> manyDocumentsArguments() {
        return Stream.concat(
                Stream.concat(
                        Stream.of(Arguments.of(true,  true,  true,  20, 4, 50, 3, -644766138635622644L)),
                        TestConfigurationUtils.onlyNightly(
                                Stream.of(
                                        Arguments.of(true,  false, false, 21, 3, 55, 3, 9237590782644L),
                                        Arguments.of(false, true,  true,  18, 3, 46, 3, -1089113174774589435L),
                                        Arguments.of(false, false, false, 24, 6, 59, 3, 6223372946177329440L),
                                        Arguments.of(true,  false, false, 27, 9, 48, 3, 2451719304283565963L)))),
                RandomizedTestUtils.randomArguments(random ->
                        Arguments.of(random.nextBoolean(),
                                random.nextBoolean(),
                                random.nextBoolean(),
                                // We want to have a high partitionHighWatermark so that the underlying lucene indexes
                                // actually end up with many records
                                random.nextInt(150) + 2,
                                random.nextInt(10) + 1,
                                random.nextInt(100) + 50,
                                3,
                                random.nextLong())));
    }

    @ParameterizedTest
    @MethodSource("manyDocumentsArguments")
    void manyDocument(boolean isGrouped,
                      boolean isSynthetic,
                      boolean primaryKeySegmentIndexEnabled,
                      int partitionHighWatermark,
                      int repartitionCount,
                      int loopCount,
                      int maxTransactionsPerLoop,
                      long seed) throws IOException {
        Random random = new Random(seed);
        final Map<String, String> options = Map.of(
                LuceneIndexOptions.INDEX_PARTITION_BY_FIELD_NAME, isSynthetic ? "parent.timestamp" : "timestamp",
                LuceneIndexOptions.INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(partitionHighWatermark),
                LuceneIndexOptions.PRIMARY_KEY_SEGMENT_INDEX_V2_ENABLED, String.valueOf(primaryKeySegmentIndexEnabled));
        LOGGER.info(KeyValueLogMessage.of("Running randomizedRepartitionTest",
                "isGrouped", isGrouped,
                "isSynthetic", isSynthetic,
                "repartitionCount", repartitionCount,
                "options", options,
                "seed", seed,
                "loopCount", loopCount));

        final RecordMetaDataBuilder metaDataBuilder = createBaseMetaDataBuilder();
        final KeyExpression rootExpression = createRootExpression(isGrouped, isSynthetic);
        Index index = addIndex(isSynthetic, rootExpression, options, metaDataBuilder);
        final RecordMetaData metadata = metaDataBuilder.build();
        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context -> createOrOpenRecordStore(context, metadata);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, repartitionCount)
                .addProp(LuceneRecordContextProperties.LUCENE_MAX_DOCUMENTS_TO_MOVE_DURING_REPARTITIONING, random.nextInt(1000) + repartitionCount)
                .addProp(LuceneRecordContextProperties.LUCENE_MERGE_SEGMENTS_PER_TIER, (double)random.nextInt(10) + 2) // it must be at least 2.0
                .build();
        final Map<Tuple, Map<Tuple, Tuple>> ids = new HashMap<>();
        for (int i = 0; i < loopCount; i++) {
            LOGGER.info(KeyValueLogMessage.of("ManyDocument loop",
                    "iteration", i,
                    "groupCount", ids.size(),
                    "docCount", ids.values().stream().mapToInt(Map::size).sum(),
                    "docMinPerGroup", ids.values().stream().mapToInt(Map::size).min(),
                    "docMaxPerGroup", ids.values().stream().mapToInt(Map::size).max()));
            generateDocuments(isGrouped, isSynthetic, 1, random,
                    contextProps, schemaSetup, random.nextInt(maxTransactionsPerLoop - 1) + 1, ids);

            explicitMergeIndex(index, contextProps, schemaSetup);
        }

        final LuceneIndexTestValidator luceneIndexTestValidator = new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()));
        luceneIndexTestValidator.validate(index, ids, repartitionCount, isSynthetic ? "child_str_value:forth" : "text_value:about");

        if (isGrouped) {
            validateDeleteWhere(isSynthetic, repartitionCount, ids, contextProps, schemaSetup, index);
        }
    }


    static Stream<Arguments> flakyMergeArguments() {
        return Stream.concat(
                Stream.of(
                        Arguments.of(true, true, true, 31, -644766138635622644L, true)),
                Stream.concat(
                        // all of these permutations take multiple minutes, but probably are not all needed as part of
                        // PRB, so put 3 fixed configuration that we know will fail merges in a variety of places into
                        // the nightly build
                        TestConfigurationUtils.onlyNightly(
                                Stream.of(
                                        Arguments.of(true, false, false, 50, 9237590782644L, true),
                                        Arguments.of(false, true, true, 33, -1089113174774589435L, true),
                                        Arguments.of(false, false, false, 35, 6223372946177329440L, true))
                        ),
                        RandomizedTestUtils.randomArguments(random ->
                                Arguments.of(random.nextBoolean(), // isGrouped
                                        random.nextBoolean(), // isSynthetic
                                        random.nextBoolean(), // primaryKeySegmentIndexEnabled
                                        random.nextInt(40) + 2, // minDocumentCount
                                        random.nextLong(), // seed for other randomness
                                        false)))); // require failure
    }

    /**
     * Test that the index is in a good state if the merge operation has errors.
     * @param isGrouped whether the index has a grouping key
     * @param isSynthetic whether the index is on a synthetic type
     * @param primaryKeySegmentIndexEnabled whether to enable the primaryKeySegmentIndex
     * @param minDocumentCount the minimum document count required for each group
     * @param seed seed used for extra, less important randomness
     * @param requireFailure whether it is expected that the merge will fail. Useful for ensuring tha PRBs actually
     * reproduce the issue, but hard to guarantee for randomly generated parameters
     * @throws IOException from lucene, probably
     */
    @ParameterizedTest(name = "flakyMerge({argumentsWithNames})")
    @MethodSource("flakyMergeArguments")
    @Tag(Tags.Slow)
    void flakyMerge(boolean isGrouped,
                    boolean isSynthetic,
                    boolean primaryKeySegmentIndexEnabled,
                    int minDocumentCount,
                    long seed,
                    boolean requireFailure) throws IOException {
        Random random = new Random(seed);
        final Map<String, String> options = Map.of(
                LuceneIndexOptions.INDEX_PARTITION_BY_FIELD_NAME, isSynthetic ? "parent.timestamp" : "timestamp",
                LuceneIndexOptions.INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(Integer.MAX_VALUE),
                LuceneIndexOptions.PRIMARY_KEY_SEGMENT_INDEX_V2_ENABLED, String.valueOf(primaryKeySegmentIndexEnabled));
        LOGGER.info(KeyValueLogMessage.of("Running flakyMerge test",
                "isGrouped", isGrouped,
                "isSynthetic", isSynthetic,
                "options", options,
                "seed", seed));

        final RecordMetaDataBuilder metaDataBuilder = createBaseMetaDataBuilder();
        final KeyExpression rootExpression = createRootExpression(isGrouped, isSynthetic);
        Index index = addIndex(isSynthetic, rootExpression, options, metaDataBuilder);
        final RecordMetaData metadata = metaDataBuilder.build();
        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context -> createOrOpenRecordStore(context, metadata);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_MERGE_SEGMENTS_PER_TIER, 2.0)
                .addProp(LuceneRecordContextProperties.LUCENE_AGILE_COMMIT_TIME_QUOTA, 1) // commit as often as possible
                .addProp(LuceneRecordContextProperties.LUCENE_AGILE_COMMIT_SIZE_QUOTA, 1) // commit as often as possible
                .addProp(LuceneRecordContextProperties.LUCENE_FILE_LOCK_TIME_WINDOW_MILLISECONDS, (int)TimeUnit.SECONDS.toMillis(10) + 1) // TODO figure out how to fix this
                .build();

        // Generate random documents
        final int transactionCount = random.nextInt(15) + 10;
        final Map<Tuple, Map<Tuple, Tuple>> ids = new HashMap<>();
        generateDocuments(isGrouped, isSynthetic, minDocumentCount, random, contextProps, schemaSetup, transactionCount, ids);

        final Function<StoreTimer.Wait, org.apache.commons.lang3.tuple.Pair<Long, TimeUnit>> oldAsyncToSyncTimeout = fdb.getAsyncToSyncTimeout();
        AtomicInteger waitCounts = new AtomicInteger();
        try {
            final Function<StoreTimer.Wait, org.apache.commons.lang3.tuple.Pair<Long, TimeUnit>> asyncToSyncTimeout = (wait) -> {
                if (wait.getClass().equals(LuceneEvents.Waits.class) &&
                        // don't have the timeout on FILE_LOCK_CLEAR because that will leave the file lock around,
                        // and the next iteration will fail on that.
                        wait != LuceneEvents.Waits.WAIT_LUCENE_FILE_LOCK_CLEAR &&
                        // if we timeout on setting, AgilityContext may commit in the background, but Lucene won't have
                        // the Lock reference to close, and clear the lock.
                        wait != LuceneEvents.Waits.WAIT_LUCENE_FILE_LOCK_SET &&
                        waitCounts.getAndDecrement() == 0) {

                    return org.apache.commons.lang3.tuple.Pair.of(1L, TimeUnit.NANOSECONDS);
                } else {
                    return oldAsyncToSyncTimeout == null ? org.apache.commons.lang3.tuple.Pair.of(1L, TimeUnit.DAYS) : oldAsyncToSyncTimeout.apply(wait);
                }
            };
            for (int i = 0; i < 100; i++) {
                fdb.setAsyncToSyncTimeout(asyncToSyncTimeout);
                waitCounts.set(i);
                boolean success = false;
                try {
                    LOGGER.info(KeyValueLogMessage.of("Merge started",
                            "iteration", i));
                    explicitMergeIndex(index, contextProps, schemaSetup);
                    LOGGER.info(KeyValueLogMessage.of("Merge completed",
                            "iteration", i));
                    assertFalse(requireFailure && i < 15, i + " merge should have failed");
                    success = true;
                } catch (RecordCoreException e) {
                    final LoggableTimeoutException timeoutException = findTimeoutException(e);
                    LOGGER.info(KeyValueLogMessage.of("Merge failed",
                            "iteration", i,
                            "cause", e.getClass(),
                            "message", e.getMessage(),
                            "timeout", timeoutException != null));
                    if (timeoutException == null) {
                        throw e;
                    }
                    assertEquals(1L, timeoutException.getLogInfo().get(LogMessageKeys.TIME_LIMIT.toString()), i + " " + e.getMessage());
                    assertEquals(TimeUnit.NANOSECONDS, timeoutException.getLogInfo().get(LogMessageKeys.TIME_UNIT.toString()), i + " " + e.getMessage());
                }
                fdb.setAsyncToSyncTimeout(oldAsyncToSyncTimeout);
                dbExtension.checkForOpenContexts(); // validate after every loop that we didn't leave any contexts open
                LOGGER.debug(KeyValueLogMessage.of("Validating",
                        "iteration", i));
                new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                        .validate(index, ids, Integer.MAX_VALUE, isSynthetic ? "child_str_value:forth" : "text_value:about", !success);
                LOGGER.debug(KeyValueLogMessage.of("Done Validating",
                        "iteration", i));
                dbExtension.checkForOpenContexts(); // just in case the validation code leaks a context
            }
        } finally {
            fdb.setAsyncToSyncTimeout(oldAsyncToSyncTimeout);
            if (LOGGER.isDebugEnabled()) {
                ids.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> LOGGER.debug(entry.getKey() + ": " + entry.getValue().keySet()));
            }
        }
    }

    // Lock a directory, and commit, then try to update a record, or save a record, or do a search, and assert that nothing
    // is corrupted (or that the user request fails)
    @Test
    void lockCommitThenValidateTest() throws IOException {
        final Map<String, String> options = Map.of(
                INDEX_PARTITION_BY_FIELD_NAME, "timestamp",
                INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(8));
        Index index = complexPartitionedIndex(options);

        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context ->
                LuceneIndexTestUtils.rebuildIndexMetaData(context, path, TestRecordsTextProto.ComplexDocument.getDescriptor().getName(), index, useCascadesPlanner);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, 8)
                .build();

        long timestamp = System.currentTimeMillis();
        Map<Tuple, Map<Tuple, Tuple>> insertedDocs = new HashMap<>();
        // save a record
        createComplexRecords(1, insertedDocs, contextProps, schemaSetup);

        // explicitly lock directory then commit
        try (FDBRecordContext context = openContext(contextProps)) {
            FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());

            // low-level lock the directory:
            // subspace for (group 1, partition data subspace, partition 0, file lock subspace)
            Subspace subspace = recordStore.indexSubspace(index).subspace(Tuple.from(1, LucenePartitioner.PARTITION_DATA_SUBSPACE, 0, FDBDirectory.FILE_LOCK_SUBSPACE));
            byte[] fileLockKey = subspace.pack(Tuple.from("write.lock"));
            FDBDirectoryLockFactory lockFactory = new FDBDirectoryLockFactory(null, 10_000);

            Lock testLock = lockFactory.obtainLock(new AgilityContext.NonAgile(context), fileLockKey, "write.lock");
            testLock.ensureValid();
            commit(context);
        }

        // search should work
        try (FDBRecordContext context = openContext(contextProps)) {
            FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());

            try (RecordCursor<IndexEntry> cursor = recordStore.scanIndex(
                    index,
                    LuceneIndexTestValidator.groupedSortedTextSearch(recordStore, index, "text:word", null, 1), null, ScanProperties.FORWARD_SCAN)) {
                List<Tuple> primaryKeys = cursor.asList()
                        .join()
                        .stream()
                        .map(IndexEntry::getPrimaryKey)
                        .collect(Collectors.toList());
                assertEquals(1, primaryKeys.size());
                assertEquals(Tuple.from(1, 1000L), primaryKeys.get(0));
            }
        }

        // create another record, this should fail
        try (FDBRecordContext context = openContext(contextProps)) {
            FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());

            TestRecordsTextProto.ComplexDocument cd = TestRecordsTextProto.ComplexDocument.newBuilder()
                    .setGroup(1)
                    .setDocId(2000L)
                    .setIsSeen(true)
                    .setText("A word about what I want to say")
                    .setTimestamp(timestamp + 2000)
                    .setHeader(TestRecordsTextProto.ComplexDocument.Header.newBuilder().setHeaderId(1999L))
                    .build();
            assertThrows(RecordCoreException.class, () -> recordStore.saveRecord(cd), "Lock failed: already locked by another entity");
        }

        // validate index
        new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                .validate(index, insertedDocs, Integer.MAX_VALUE, "text:about", false);
    }


    // A chaos test of two threads, one constantly trying to merge, and one trying to update a record, or save a new record or do a search.
    // At the end the index should be validated for consistency.
    @Test
    void chaosMergeAndUpdateTest() throws InterruptedException, IOException {
        final Map<String, String> options = Map.of(
                INDEX_PARTITION_BY_FIELD_NAME, "timestamp",
                INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(100));
        Index index = complexPartitionedIndex(options);

        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context ->
                LuceneIndexTestUtils.rebuildIndexMetaData(context, path, TestRecordsTextProto.ComplexDocument.getDescriptor().getName(), index, useCascadesPlanner);

        assertNotNull(index);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, 8)
                .build();

        final int countReps = 20;

        long timestamp = System.currentTimeMillis();
        Map<Tuple, Map<Tuple, Tuple>> insertedDocs = new HashMap<>();
        CountDownLatch firstRecordInserted = new CountDownLatch(1);
        Thread inserter = new Thread(() -> {
            for (int i = 0; i < countReps; i++) {
                // create a record then query
                try (FDBRecordContext context = openContext(contextProps)) {
                    FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());
                    recordStore.getIndexDeferredMaintenanceControl().setAutoMergeDuringCommit(false);
                    TestRecordsTextProto.ComplexDocument cd = TestRecordsTextProto.ComplexDocument.newBuilder()
                            .setGroup(1)
                            .setDocId(i + 1000L)
                            .setIsSeen(true)
                            .setText("A word about what I want to say")
                            .setTimestamp(timestamp + i)
                            .setHeader(TestRecordsTextProto.ComplexDocument.Header.newBuilder().setHeaderId(1000L - i))
                            .build();
                    try {
                        final Tuple primaryKey = recordStore.saveRecord(cd).getPrimaryKey();

                        try (RecordCursor<IndexEntry> cursor = recordStore.scanIndex(
                                index,
                                LuceneIndexTestValidator.groupedSortedTextSearch(recordStore, index, "text:word", null, 1), null, ScanProperties.FORWARD_SCAN)) {
                            List<IndexEntry> matches = cursor.asList().join();
                            assertFalse(matches.isEmpty());
                        }

                        commit(context);
                        insertedDocs.computeIfAbsent(Tuple.from(1), k -> new HashMap<>()).put(primaryKey, Tuple.from(timestamp + i));
                        // after first record is committed, signal to merger record to start attempting to merge
                        firstRecordInserted.countDown();
                    } catch (Exception e) {
                        // commit failed due to conflict with other thread. Continue trying to create docs.
                        LOGGER.debug("couldn't commit for key {}", (1000L + i));
                    }
                }
            }
        });

        Thread merger = new Thread(() -> {
            // wait till first record is committed before attempting to merge
            try {
                firstRecordInserted.await();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }

            // busy merge
            for (int i = 0; i < countReps; i++) {
                explicitMergeIndex(index, contextProps, schemaSetup);
            }
        });

        inserter.start();
        merger.start();

        inserter.join();
        merger.join();

        // validate index is sane
        new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                .validate(index, insertedDocs, Integer.MAX_VALUE, "text:about", false);
    }

    // A test where there are multiple threads trying to do merges. At the end the index should be validated for consistency.
    @Test
    void multipleConcurrentMergesTest() throws IOException, InterruptedException {
        final Map<String, String> options = Map.of(
                INDEX_PARTITION_BY_FIELD_NAME, "timestamp",
                INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(100));

        Index index = complexPartitionedIndex(options);

        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context ->
                LuceneIndexTestUtils.rebuildIndexMetaData(context, path, TestRecordsTextProto.ComplexDocument.getDescriptor().getName(), index, useCascadesPlanner);
        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, 8)
                .build();

        final int countReps = 20;
        final int threadCount = 10;

        Map<Tuple, Map<Tuple, Tuple>> insertedDocs = new HashMap<>();

        // create a bunch of docs
        createComplexRecords(countReps, insertedDocs, contextProps, schemaSetup);

        final CountDownLatch readyToMerge = new CountDownLatch(1);
        final CountDownLatch doneMerging = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    readyToMerge.await();
                    explicitMergeIndex(index, contextProps, schemaSetup);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneMerging.countDown();
                }
            }).start();
        }
        readyToMerge.countDown();
        doneMerging.await();

        new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                .validate(index, insertedDocs, Integer.MAX_VALUE, "text:about", false);
    }

    static Stream<Arguments> mergeLosesLockTest() {
        return Stream.concat(
                Stream.of( 65).map(Arguments::of), // fixed 65% lock failure rate
                RandomizedTestUtils.randomArguments(random -> Arguments.of(random.nextInt(101)))); //  0-100%
    }

    // A test of what lucene does when a merge loses its lock
    @ParameterizedTest
    @MethodSource
    void mergeLosesLockTest(int failurePercentage) throws IOException {
        final Map<String, String> options = Map.of(
                INDEX_PARTITION_BY_FIELD_NAME, "timestamp",
                INDEX_PARTITION_HIGH_WATERMARK, String.valueOf(200));
        Index index = complexPartitionedIndex(options);

        Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup = context ->
                LuceneIndexTestUtils.rebuildIndexMetaData(context, path, TestRecordsTextProto.ComplexDocument.getDescriptor().getName(), index, useCascadesPlanner);

        final RecordLayerPropertyStorage contextProps = RecordLayerPropertyStorage.newBuilder()
                .addProp(LuceneRecordContextProperties.LUCENE_REPARTITION_DOCUMENT_COUNT, 8)
                .addProp(LuceneRecordContextProperties.LUCENE_MERGE_SEGMENTS_PER_TIER, 2.0)
                .addProp(LuceneRecordContextProperties.LUCENE_AGILE_COMMIT_TIME_QUOTA, 1) // 1ms
                .build();

        final int docCount = 100;
        Map<Tuple, Map<Tuple, Tuple>> insertedDocs = new HashMap<>();
        // create a bunch of docs
        createComplexRecords(docCount, insertedDocs, contextProps, schemaSetup);

        // try a couple of times
        for (int l = 0; l < 2; l++) {
            try (FDBRecordContext context = openContext(contextProps)) {
                FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());

                // directory key for group 1/partition 0
                Tuple directoryKey = Tuple.from(1, LucenePartitioner.PARTITION_DATA_SUBSPACE, 0);
                IndexMaintainerState state = new IndexMaintainerState(recordStore, index, IndexMaintenanceFilter.NORMAL);

                // custom test directory that returns a lucene lock that's never valid (Lock.ensureValid() throws IOException)
                FDBDirectory fdbDirectory = new InvalidLockTestFDBDirectory(recordStore.indexSubspace(index).subspace(directoryKey), context, options, failurePercentage);
                FDBDirectoryWrapper fdbDirectoryWrapper = new FDBDirectoryWrapper(state, fdbDirectory, directoryKey, 1, AgilityContext.agile(context, 1L, 1L));

                final var fieldInfos = LuceneIndexExpressions.getDocumentFieldDerivations(state.index, state.store.getRecordMetaData());
                LuceneAnalyzerCombinationProvider indexAnalyzerSelector = LuceneAnalyzerRegistryImpl.instance().getLuceneAnalyzerCombinationProvider(state.index, LuceneAnalyzerType.FULL_TEXT, fieldInfos);

                assertThrows(IOException.class, () -> fdbDirectoryWrapper.mergeIndex(indexAnalyzerSelector.provideIndexAnalyzer(""), new Exception()), "invalid lock");
                commit(context);
            }
        }

        // validate that the index is still sane
        new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                .validate(index, insertedDocs, Integer.MAX_VALUE, "text:about", false);
    }

    /**
     * a test FDBDirectory class that returns a {@link Lock} that is not valid.
     */
    static class InvalidLockTestFDBDirectory extends FDBDirectory {
        private final int percentFailure;

        public InvalidLockTestFDBDirectory(@Nonnull Subspace subspace,
                                           @Nonnull FDBRecordContext context,
                                           @Nullable Map<String, String> indexOptions,
                                           final int percentFailure) {
            super(subspace, context, indexOptions);
            this.percentFailure = percentFailure;
        }

        @Override
        @Nonnull
        public Lock obtainLock(@Nonnull final String lockName) throws IOException {
            final Lock lock = super.obtainLock(lockName);
            return new Lock() {
                @Override
                public void close() throws IOException {
                    lock.close();
                }

                @Override
                public void ensureValid() throws IOException {
                    // 0 <= 0-99 < 100
                    if (ThreadLocalRandom.current().nextInt(100) < percentFailure) {
                        throw new IOException("invalid lock");
                    } else {
                        lock.ensureValid();
                    }
                }
            };
        }
    }

    private void createComplexRecords(int count,
                                      Map<Tuple, Map<Tuple, Tuple>> insertedKeys,
                                      RecordLayerPropertyStorage contextProps,
                                      Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup) {
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            try (FDBRecordContext context = openContext(contextProps)) {
                FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());
                recordStore.getIndexDeferredMaintenanceControl().setAutoMergeDuringCommit(false);
                TestRecordsTextProto.ComplexDocument cd = TestRecordsTextProto.ComplexDocument.newBuilder()
                        .setGroup(1)
                        .setDocId(i + 1000L)
                        .setIsSeen(true)
                        .setText("A word about what I want to say")
                        .setTimestamp(timestamp + i)
                        .setHeader(TestRecordsTextProto.ComplexDocument.Header.newBuilder().setHeaderId(1000L - i))
                        .build();
                Tuple primaryKey = recordStore.saveRecord(cd).getPrimaryKey();
                insertedKeys.computeIfAbsent(Tuple.from(1), k -> new HashMap<>()).put(primaryKey, Tuple.from(timestamp));
                commit(context);
            }
        }
    }

    private static LoggableTimeoutException findTimeoutException(final RecordCoreException e) {
        Map<Throwable, String> visited = new IdentityHashMap<>();
        ArrayDeque<Throwable> toVisit = new ArrayDeque<>();
        toVisit.push(e);
        while (!toVisit.isEmpty()) {
            Throwable cause = toVisit.removeFirst();
            if (!visited.containsKey(cause)) {
                if (cause instanceof LoggableTimeoutException) {
                    return (LoggableTimeoutException) cause;
                }
                if (cause.getCause() != null) {
                    toVisit.addLast(cause.getCause());
                }
                for (final Throwable suppressed : cause.getSuppressed()) {
                    toVisit.addLast(suppressed);
                }
                visited.put(cause, "");
            }
        }
        return null;
    }

    private void generateDocuments(final boolean isGrouped,
                                   final boolean isSynthetic,
                                   final int minDocumentCount,
                                   final Random random,
                                   final RecordLayerPropertyStorage contextProps,
                                   final Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup,
                                   final int transactionCount,
                                   final Map<Tuple, Map<Tuple, Tuple>> ids) {
        final long start = Instant.now().toEpochMilli();
        int i = 0;
        while (i < transactionCount ||
                // keep inserting data until at least two groups have at least minDocumentCount
                ids.values().stream()
                        .map(Map::size)
                        .sorted(Comparator.reverseOrder())
                        .limit(2).skip(isGrouped ? 1 : 0).findFirst()
                        .orElse(0) < minDocumentCount) {
            final int docCount = random.nextInt(10) + 1;
            try (FDBRecordContext context = openContext(contextProps)) {
                FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());
                recordStore.getIndexDeferredMaintenanceControl().setAutoMergeDuringCommit(false);
                for (int j = 0; j < docCount; j++) {
                    final int group = isGrouped ? random.nextInt(random.nextInt(10) + 1) : 0; // irrelevant if !isGrouped
                    final Tuple groupTuple = isGrouped ? Tuple.from(group) : Tuple.from();
                    final int countInGroup = ids.computeIfAbsent(groupTuple, key -> new HashMap<>()).size();
                    long timestamp = start + countInGroup + random.nextInt(20) - 5;
                    final Tuple primaryKey = saveRecords(recordStore, isSynthetic, group, countInGroup, timestamp, random);
                    ids.computeIfAbsent(groupTuple, key -> new HashMap<>()).put(primaryKey, Tuple.from(timestamp).addAll(primaryKey));
                }
                commit(context);
            }
            i++;
        }
    }

    @Nonnull
    private static RecordMetaDataBuilder createBaseMetaDataBuilder() {
        RecordMetaDataBuilder metaDataBuilder = RecordMetaData.newBuilder()
                .setRecords(TestRecordsGroupedParentChildProto.getDescriptor());
        metaDataBuilder.getRecordType("MyParentRecord")
                .setPrimaryKey(Key.Expressions.concatenateFields("group", "rec_no"));
        metaDataBuilder.getRecordType("MyChildRecord")
                .setPrimaryKey(Key.Expressions.concatenateFields("group", "rec_no"));
        return metaDataBuilder;
    }

    @Nonnull
    public static Index complexPartitionedIndex(final Map<String, String> options) {
        return new Index("Complex$partitioned",
                concat(function(LuceneFunctionNames.LUCENE_TEXT, field("text")),
                        function(LuceneFunctionNames.LUCENE_SORTED, field("timestamp"))).groupBy(field("group")),
                LuceneIndexTypes.LUCENE,
                options);
    }

    private void validateDeleteWhere(final boolean isSynthetic,
                                     final int repartitionCount,
                                     final Map<Tuple, Map<Tuple, Tuple>> ids,
                                     final RecordLayerPropertyStorage contextProps,
                                     final Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup,
                                     final Index index) throws IOException {
        final List<Tuple> groups = List.copyOf(ids.keySet());
        for (final Tuple group : groups) {
            try (FDBRecordContext context = openContext(contextProps)) {
                FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());
                recordStore.deleteRecordsWhere(Query.field("group").equalsValue(group.getLong(0)));
                context.commit();
            }
            ids.remove(group);
            new LuceneIndexTestValidator(() -> openContext(contextProps), context -> Objects.requireNonNull(schemaSetup.apply(context).getLeft()))
                    .validate(index, ids, repartitionCount, isSynthetic ? "child_str_value:forth" : "text_value:about");
        }
    }

    @Nonnull
    private Tuple saveRecords(final FDBRecordStore recordStore,
                              final boolean isSynthetic,
                              final int group,
                              final int countInGroup,
                              final long timestamp,
                              final Random random) {
        var parent = TestRecordsGroupedParentChildProto.MyParentRecord.newBuilder()
                .setGroup(group)
                .setRecNo(1001L + countInGroup)
                .setTimestamp(timestamp)
                .setTextValue("A word about what I want to say")
                .setIntValue(random.nextInt())
                .setChildRecNo(1000L - countInGroup)
                .build();
        Tuple primaryKey;
        if (isSynthetic) {
            var child = TestRecordsGroupedParentChildProto.MyChildRecord.newBuilder()
                    .setGroup(group)
                    .setRecNo(1000L - countInGroup)
                    .setStrValue("Four score and seven years ago our fathers brought forth")
                    .setOtherValue(random.nextInt())
                    .build();
            final Tuple syntheticRecordTypeKey = recordStore.getRecordMetaData()
                    .getSyntheticRecordType("JoinChildren")
                    .getRecordTypeKeyTuple();
            primaryKey = Tuple.from(syntheticRecordTypeKey.getItems().get(0),
                    recordStore.saveRecord(parent).getPrimaryKey().getItems(),
                    recordStore.saveRecord(child).getPrimaryKey().getItems());
        } else {
            primaryKey = recordStore.saveRecord(parent).getPrimaryKey();
        }
        return primaryKey;
    }

    @Nonnull
    private static Index addIndex(final boolean isSynthetic, final KeyExpression rootExpression, final Map<String, String> options, final RecordMetaDataBuilder metaDataBuilder) {
        Index index;
        index = new Index("joinNestedConcat", rootExpression, LuceneIndexTypes.LUCENE, options);

        if (isSynthetic) {
            final JoinedRecordTypeBuilder joinBuilder = metaDataBuilder.addJoinedRecordType("JoinChildren");
            joinBuilder.addConstituent("parent", "MyParentRecord");
            joinBuilder.addConstituent("child", "MyChildRecord");
            joinBuilder.addJoin("parent", Key.Expressions.field("group"),
                    "child", Key.Expressions.field("group"));
            joinBuilder.addJoin("parent", Key.Expressions.field("child_rec_no"),
                    "child", Key.Expressions.field("rec_no"));
            metaDataBuilder.addIndex("JoinChildren", index);
        } else {
            metaDataBuilder.addIndex("MyParentRecord", index);
        }
        return index;
    }

    @Nonnull
    private static KeyExpression createRootExpression(final boolean isGrouped, final boolean isSynthetic) {
        ThenKeyExpression baseExpression;
        KeyExpression groupingExpression;
        if (isSynthetic) {
            baseExpression = Key.Expressions.concat(
                    Key.Expressions.field("parent")
                            .nest(Key.Expressions.function(LuceneFunctionNames.LUCENE_STORED,
                                    Key.Expressions.field("int_value"))),
                    Key.Expressions.field("child")
                            .nest(Key.Expressions.function(LuceneFunctionNames.LUCENE_TEXT,
                                    Key.Expressions.field("str_value"))),
                    Key.Expressions.field("parent")
                            .nest(Key.Expressions.function(LuceneFunctionNames.LUCENE_SORTED,
                                    Key.Expressions.field("timestamp")))
            );
            groupingExpression = Key.Expressions.field("parent").nest("group");
        } else {
            baseExpression = Key.Expressions.concat(
                    Key.Expressions.function(LuceneFunctionNames.LUCENE_STORED,
                            Key.Expressions.field("int_value")),
                    Key.Expressions.function(LuceneFunctionNames.LUCENE_TEXT,
                            Key.Expressions.field("text_value")),
                    Key.Expressions.function(LuceneFunctionNames.LUCENE_SORTED,
                            Key.Expressions.field("timestamp"))
            );
            groupingExpression = Key.Expressions.field("group");
        }
        KeyExpression rootExpression;
        if (isGrouped) {
            rootExpression = baseExpression.groupBy(groupingExpression);
        } else {
            rootExpression = baseExpression;
        }
        return rootExpression;
    }


    private void explicitMergeIndex(Index index,
                                    RecordLayerPropertyStorage contextProps,
                                    Function<FDBRecordContext, Pair<FDBRecordStore, QueryPlanner>> schemaSetup) {
        try (FDBRecordContext context = openContext(contextProps)) {
            FDBRecordStore recordStore = Objects.requireNonNull(schemaSetup.apply(context).getLeft());
            try (OnlineIndexer indexBuilder = OnlineIndexer.newBuilder()
                    .setRecordStore(recordStore)
                    .setIndex(index)
                    .setTimer(timer)
                    .build()) {
                indexBuilder.mergeIndex();
            }
        }
    }

    protected RecordLayerPropertyStorage.Builder addDefaultProps(final RecordLayerPropertyStorage.Builder props) {
        return super.addDefaultProps(props).addProp(LuceneRecordContextProperties.LUCENE_INDEX_COMPRESSION_ENABLED, true);
    }
}
