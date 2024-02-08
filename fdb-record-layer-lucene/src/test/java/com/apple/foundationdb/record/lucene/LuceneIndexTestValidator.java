/*
 * LuceneIndexValidator.java
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

import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.lucene.directory.FDBDirectoryManager;
import com.apple.foundationdb.record.lucene.search.LuceneOptimizedIndexSearcher;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.IndexMaintainerState;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.plan.ScanComparisons;
import com.apple.foundationdb.tuple.Tuple;
import com.google.common.base.Verify;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A utility for validating the consistency and contents of a lucene index.
 */
public class LuceneIndexTestValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneIndexTestValidator.class);
    private final Supplier<FDBRecordContext> contextProvider;
    private final Function<FDBRecordContext, FDBRecordStore> schemaSetup;

    public LuceneIndexTestValidator(Supplier<FDBRecordContext> contextProvider, Function<FDBRecordContext, FDBRecordStore> schemaSetup) {
        this.contextProvider = contextProvider;
        this.schemaSetup = schemaSetup;
    }

    /**
     * A broad validation of the lucene index, asserting consistency, and that various operations did what they were
     * supposed to do.
     * <p>
     *     This has a lot of validation that could be added, and it would be good to be able to control whether it's
     *     expected that `mergeIndex` had been run or not; right now it assumes it has been run.
     * </p>
     * @param index the index to validate
     * @param expectedDocumentInformation a map from group to primaryKey to timestamp
     * @param repartitionCount the configured repartition count
     * @param universalSearch a search that will return all the documents
     * @throws IOException if there is any issue interacting with lucene
     */
    void validate(Index index, final Map<Integer, Map<Tuple, Long>> expectedDocumentInformation,
                  final int repartitionCount, final String universalSearch) throws IOException {
        boolean isGrouped = index.getRootExpression() instanceof GroupingKeyExpression;
        final int partitionHighWatermark = Integer.parseInt(index.getOption(LuceneIndexOptions.INDEX_PARTITION_HIGH_WATERMARK));
        // If there is less than repartitionCount of free space in the older partition, we'll create a new partition
        // rather than moving fewer than repartitionCount
        int maxPerPartition = partitionHighWatermark;

        for (final Map.Entry<Integer, Map<Tuple, Long>> entry : expectedDocumentInformation.entrySet()) {
            final Integer group = entry.getKey();
            LOGGER.debug(KeyValueLogMessage.of("Validating group",
                    "group", group,
                    "expectedCount", entry.getValue().size()));

            final List<Tuple> records = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            final Tuple groupingKey = isGrouped ? Tuple.from(group) : Tuple.from();
            List<LucenePartitionInfoProto.LucenePartitionInfo> partitionInfos = getPartitionMeta(index, groupingKey);
            partitionInfos.sort(Comparator.comparing(info -> Tuple.fromBytes(info.getFrom().toByteArray())));
            Set<Integer> usedPartitionIds = new HashSet<>();
            Tuple lastToTuple = null;
            int visitedCount = 0;
            String allCounts = partitionInfos.stream()
                    .map(info -> String.valueOf(info.getCount()))
                    .collect(Collectors.joining(",", "[", "]"));

            try (FDBRecordContext context = contextProvider.get()) {
                final FDBRecordStore recordStore = schemaSetup.apply(context);

                for (int i = 0; i < partitionInfos.size(); i++) {
                    final LucenePartitionInfoProto.LucenePartitionInfo partitionInfo = partitionInfos.get(i);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Group: " + group + " PartitionInfo[" + partitionInfo.getId() +
                                "]: count:" + partitionInfo.getCount() + " " +
                                Tuple.fromBytes(partitionInfo.getFrom().toByteArray()) + "-> " +
                                Tuple.fromBytes(partitionInfo.getTo().toByteArray()));
                    }

                    int minPerPartition;
                    if (partitionInfos.size() == 1) {
                        // if there is only one partition, it should have exactly the number of documents, which is
                        // verified below
                        minPerPartition = 1;
                    } else if (i == 0) {
                        // if it is the oldest, it could have fewer if the test inserted max into the most recent, and
                        // then inserted a couple that were older
                        // If we add tests that don't try at all to order the timestamps this could become more
                        // complicated
                        minPerPartition = 1;
                    } else if (i == partitionInfos.size() - 2) {
                        // The second to last should have at least the repartitionCount that would have been moved out
                        // of the most recent
                        minPerPartition = Math.min(repartitionCount, partitionHighWatermark);
                    } else {
                        // Everything else should be filled as much as it can, but may have had repartitionCount moved
                        // out.
                        minPerPartition = Math.max(1, partitionHighWatermark - repartitionCount);
                    }
                    assertThat("Group: " + group + " - " + allCounts, partitionInfo.getCount(),
                            Matchers.allOf(lessThanOrEqualTo(maxPerPartition), greaterThanOrEqualTo(minPerPartition)));
                    assertTrue(usedPartitionIds.add(partitionInfo.getId()), () -> "Duplicate id: " + partitionInfo);
                    final Tuple fromTuple = Tuple.fromBytes(partitionInfo.getFrom().toByteArray());
                    if (i > 0) {
                        assertThat(fromTuple, greaterThan(lastToTuple));
                    }
                    lastToTuple = Tuple.fromBytes(partitionInfo.getTo().toByteArray());
                    assertThat(fromTuple, lessThanOrEqualTo(lastToTuple));

                    LOGGER.debug(KeyValueLogMessage.of("Visited partition",
                            "group", group,
                            "documentsSoFar", visitedCount,
                            "documentsInGroup", records.size(),
                            "partitionInfo.count", partitionInfo.getCount()));
                    validateDocsInPartition(recordStore, index, partitionInfo.getId(), groupingKey,
                            Set.copyOf(records.subList(visitedCount, visitedCount + partitionInfo.getCount())),
                            universalSearch);
                    visitedCount += partitionInfo.getCount();
                }
            }

        }
    }

    private List<LucenePartitionInfoProto.LucenePartitionInfo> getPartitionMeta(Index index,
                                                                                Tuple groupingKey) {
        try (FDBRecordContext context = contextProvider.get()) {
            final FDBRecordStore recordStore = schemaSetup.apply(context);
            LuceneIndexMaintainer indexMaintainer = (LuceneIndexMaintainer) recordStore.getIndexMaintainer(index);
            return indexMaintainer.getPartitioner().getAllPartitionMetaInfo(groupingKey).join();
        }
    }


    public static void validateDocsInPartition(final FDBRecordStore recordStore, Index index, int partitionId, Tuple groupingKey,
                                               Set<Tuple> expectedPrimaryKeys, final String universalSearch) throws IOException {
        LuceneScanQuery scanQuery;
        if (groupingKey.isEmpty()) {
            scanQuery = (LuceneScanQuery) LuceneIndexTestUtils.fullSortTextSearch(recordStore, index, universalSearch, null);
        } else {
            scanQuery = (LuceneScanQuery) groupedSortedTextSearch(recordStore, index,
                    universalSearch,
                    null,
                    groupingKey.getLong(0));
        }
        final IndexReader indexReader = getIndexReader(recordStore, index, partitionId, groupingKey);
        LuceneOptimizedIndexSearcher searcher = new LuceneOptimizedIndexSearcher(indexReader);
        TopDocs newTopDocs = searcher.search(scanQuery.getQuery(), Integer.MAX_VALUE);

        assertNotNull(newTopDocs);
        assertNotNull(newTopDocs.scoreDocs);
        assertEquals(expectedPrimaryKeys.size(), newTopDocs.scoreDocs.length);

        Set<String> fields = Set.of(LuceneIndexMaintainer.PRIMARY_KEY_FIELD_NAME);
        Assertions.assertEquals(expectedPrimaryKeys.stream().sorted().collect(Collectors.toList()), Arrays.stream(newTopDocs.scoreDocs)
                        .map(scoreDoc -> {
                            try {
                                Document document = searcher.doc(scoreDoc.doc, fields);
                                IndexableField primaryKey = document.getField(LuceneIndexMaintainer.PRIMARY_KEY_FIELD_NAME);
                                return Tuple.fromBytes(primaryKey.binaryValue().bytes);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .sorted()
                        .collect(Collectors.toList()),
                () -> index.getRootExpression() + " " + groupingKey + ":" + partitionId);
    }

    public static IndexReader getIndexReader(final FDBRecordStore recordStore, final Index index, final int partitionId, final Tuple groupingKey) throws IOException {
        IndexMaintainerState state = new IndexMaintainerState(recordStore, index, recordStore.getIndexMaintenanceFilter());
        return FDBDirectoryManager.getManager(state).getIndexReader(groupingKey, partitionId);
    }

    public static LuceneScanBounds groupedSortedTextSearch(final FDBRecordStoreBase<?> recordStore, Index index, String search, Sort sort, Object group) {
        LuceneScanParameters scan = new LuceneScanQueryParameters(
                Verify.verifyNotNull(ScanComparisons.from(new Comparisons.SimpleComparison(Comparisons.Type.EQUALS, group))),
                new LuceneQuerySearchClause(LuceneQueryType.QUERY, search, false),
                sort,
                null,
                null,
                null);

        return scan.bind(recordStore, index, EvaluationContext.EMPTY);
    }
}