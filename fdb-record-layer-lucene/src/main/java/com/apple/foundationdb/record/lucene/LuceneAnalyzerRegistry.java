/*
 * LuceneAnalyzerRegistry.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.record.metadata.Index;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Registry for {@link AnalyzerChooser}s. This registry allows for full-text indexes to specify
 * their analyzer combination through an index options.
 * The registry will then be queried for the analyzer combination provider.
 *
 * <p>
 * Note that the way of adding elements to the analyzer registry is to use the
 * {@link com.google.auto.service.AutoService AutoService} annotation to mark a
 * {@link LuceneAnalyzerFactory} implementation as one that should be loaded into the registry.
 * </p>
 */
@SuppressWarnings("unused")
public interface LuceneAnalyzerRegistry {

    @Nonnull
    LuceneAnalyzerCombinationProvider getLuceneAnalyzerCombinationProvider(@Nonnull Index index,
                                                                           @Nonnull LuceneAnalyzerType type,
                                                                           @Nonnull Map<String, LuceneIndexExpressions.DocumentFieldDerivation> auxiliaryFieldInfo);
}
