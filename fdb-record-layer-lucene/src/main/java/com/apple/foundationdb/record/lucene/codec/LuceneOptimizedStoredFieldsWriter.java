/*
 * LuceneOptimizedStoredFieldsWriter.java
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

import com.apple.foundationdb.record.lucene.LuceneStoredFieldsProto;
import com.apple.foundationdb.record.lucene.directory.FDBDirectory;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.ByteString;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.index.DocIDMerger;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

/**
 * This class wraps a StoreFieldsWriter.
 *
 */
public class LuceneOptimizedStoredFieldsWriter extends StoredFieldsWriter {
    private static final Logger LOG = LoggerFactory.getLogger(LuceneOptimizedStoredFieldsWriter.class);
    private LuceneStoredFieldsProto.LuceneStoredFields.Builder storedFields;
    private final FDBDirectory directory;
    private final Tuple keyTuple;
    private int docId;
    private Queue<CompletableFuture<Integer>> blockingQueue;

    @SuppressWarnings("PMD.CloseResource")
    public LuceneOptimizedStoredFieldsWriter(final Directory directory, final SegmentInfo si) {
        Directory delegate = FilterDirectory.unwrap(directory);
        if (delegate instanceof FDBDirectory) {
            this.directory = (FDBDirectory) delegate;
        } else {
            throw new RuntimeException("Expected FDB Directory " + delegate.getClass());
        }
        this.docId = 0;
        this.keyTuple = Tuple.from(si.name);
        // TODO: What is this for?
        this.blockingQueue = new ArrayBlockingQueue<>(20);
    }

    @Override
    public void startDocument() throws IOException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("startDocument");
        }
        // TODO: Protect from double-open?
        storedFields = LuceneStoredFieldsProto.LuceneStoredFields.newBuilder();
    }

    @Override
    public void finishDocument() throws IOException {
        try {
            if (this.blockingQueue.size() == 20) {
                blockingQueue.remove().get();
            }
            // TODO: This will fail if capacity reached, returning FALSE
            blockingQueue.offer(directory.writeStoredFields(keyTuple.add(docId), storedFields.build().toByteArray()));
            // TODO: So docs are assumed to have increasing doc IDs, but not directly set?
            docId++;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void writeField(final FieldInfo info, final IndexableField field) throws IOException {
        LuceneStoredFieldsProto.StoredField.Builder builder = LuceneStoredFieldsProto.StoredField.newBuilder();
        builder.setFieldNumber(info.number);
        Number number = field.numericValue();
        if (number != null) {
            if (number instanceof Byte || number instanceof Short || number instanceof Integer) {
                builder.setIntValue(number.intValue());
            } else if (number instanceof Long) {
                builder.setLongValue(number.longValue());
            } else if (number instanceof Float) {
                builder.setFloatValue(number.floatValue());
            } else if (number instanceof Double) {
                builder.setDoubleValue(number.doubleValue());
            } else {
                throw new IllegalArgumentException("cannot store numeric type " + number.getClass());
            }
        } else {
            BytesRef bytes = field.binaryValue();
            if (bytes != null) {
                builder.setBytesValue(ByteString.copyFrom(bytes.bytes, bytes.offset, bytes.length));
            } else {
                String string = field.stringValue();
                if (string == null) {
                    throw new IllegalArgumentException("field " + field.name() + " is stored but does not have binaryValue, stringValue nor numericValue");
                }
                builder.setStringValue(string);
            }
        }
        storedFields.addStoredFields(builder);
        while (blockingQueue.size() > 0) {
            try {
                blockingQueue.remove().get();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @Override
    public void finish(final FieldInfos fis, final int numDocs) throws IOException {
        // TODO: This can verify that the number of docs matches what we actually wrote.
        // TODO: Shouldn't this clear the queue by calling get()?
    }

    @SuppressWarnings("PMD.CloseResource")
    @Override
    public int merge(final MergeState mergeState) throws IOException {
        List<StoredFieldsMergeSub> subs = new ArrayList<>();
        for (int i = 0; i < mergeState.storedFieldsReaders.length; i++) {
            StoredFieldsReader storedFieldsReader = mergeState.storedFieldsReaders[i];
            if (storedFieldsReader instanceof LuceneOptimizedStoredFieldsReader) {
                ((LuceneOptimizedStoredFieldsReader) storedFieldsReader).visitDocumentViaScan(); // Performs Scan vs. Bunch of Single Fetches
            }
            subs.add(new StoredFieldsMergeSub(new MergeVisitor(mergeState, i), mergeState.docMaps[i], storedFieldsReader, mergeState.maxDocs[i]));
        }

        final DocIDMerger<StoredFieldsMergeSub> docIDMerger = DocIDMerger.of(subs, mergeState.needsIndexSort);

        int docCount = 0;
        while (true) {
            StoredFieldsMergeSub sub = docIDMerger.next();
            if (sub == null) {
                break;
            }
            assert sub.mappedDocID == docCount;
            startDocument();
            sub.reader.visitDocument(sub.docID, sub.visitor);
            finishDocument();
            docCount++;
        }
        /*
        Cleanup Readers that were merged...
        for (int i = 0; i < mergeState.storedFieldsReaders.length; i++) {
            if (mergeState.storedFieldsReaders[i] instanceof LuceneOptimizedStoredFieldsReader) {
                directory.deleteStoredFields(
                    ((LuceneOptimizedStoredFieldsReader) mergeState.storedFieldsReaders[i]).getKeyTuple()
                );
            }
        }
         */
        finish(mergeState.mergeFieldInfos, docCount);
        return docCount;
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public long ramBytesUsed() {
        return 1; // ToDo Fix
    }

    @Override
    public Collection<Accountable> getChildResources() {
        return super.getChildResources();
    }

    private static class StoredFieldsMergeSub extends DocIDMerger.Sub {
        private final StoredFieldsReader reader;
        private final int maxDoc;
        private final MergeVisitor visitor;
        int docID = -1;

        public StoredFieldsMergeSub(MergeVisitor visitor, MergeState.DocMap docMap, StoredFieldsReader reader, int maxDoc) {
            super(docMap);
            this.maxDoc = maxDoc;
            this.reader = reader;
            this.visitor = visitor;
        }

        @Override
        public int nextDoc() {
            docID++;
            if (docID == maxDoc) {
                return NO_MORE_DOCS;
            } else {
                return docID;
            }
        }

    }

}
