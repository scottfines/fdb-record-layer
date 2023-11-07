/*
 * ValuePredicate.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.plan.cascades.predicates;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.annotation.SpotBugsSuppressWarnings;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.PlanHashable;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.query.expressions.Comparisons.Comparison;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.TranslationMap;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;

/**
 * A predicate consisting of a {@link Value} and a {@link Comparison}.
 */
@API(API.Status.EXPERIMENTAL)
public class ValuePredicate extends AbstractQueryPredicate implements PredicateWithValue {
    @Nonnull
    private final Value value;
    @Nonnull
    private final Comparison comparison;

    public ValuePredicate(@Nonnull Value value, @Nonnull Comparison comparison) {
        super(false);
        this.value = value;
        this.comparison = comparison;
    }

    @Nonnull
    public Comparison getComparison() {
        return comparison;
    }

    @Nonnull
    @Override
    public Value getValue() {
        return value;
    }

    @Nonnull
    @Override
    public ValuePredicate withValue(@Nonnull final Value value) {
        return new ValuePredicate(value, comparison);
    }

    @Nullable
    @Override
    public <M extends Message> Boolean eval(@Nonnull final FDBRecordStoreBase<M> store, @Nonnull final EvaluationContext context) {
        return comparison.eval(store, context, value.eval(store, context));
    }

    @Nonnull
    @Override
    public Set<CorrelationIdentifier> getCorrelatedToWithoutChildren() {
        final var builder = ImmutableSet.<CorrelationIdentifier>builder();
        builder.addAll(value.getCorrelatedTo());
        builder.addAll(comparison.getCorrelatedTo());
        return builder.build();
    }

    @Nonnull
    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public QueryPredicate translateLeafPredicate(@Nonnull final TranslationMap translationMap) {
        final var translatedValue = value.translateCorrelations(translationMap);
        final Comparison newComparison;
        if (comparison.getCorrelatedTo().stream().anyMatch(translationMap::containsSourceAlias)) {
            newComparison = comparison.translateCorrelations(translationMap);
        } else {
            newComparison = comparison;
        }
        if (value != translatedValue || newComparison != comparison) { // reference comparison intended
            return new ValuePredicate(translatedValue, newComparison);
        }
        return this;
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @SpotBugsSuppressWarnings("EQ_UNUSUAL")
    @Override
    public boolean equals(final Object other) {
        return semanticEquals(other, AliasMap.identitiesFor(getCorrelatedTo()));
    }

    @Override
    public int hashCode() {
        return semanticHashCode();
    }

    @Override
    public int computeSemanticHashCode() {
        return PredicateWithValue.super.computeSemanticHashCode();
    }

    @Override
    public int hashCodeWithoutChildren() {
        return Objects.hash(value.semanticHashCode(), comparison.semanticHashCode());
    }

    @Override
    public boolean equalsWithoutChildren(@Nonnull final QueryPredicate other, @Nonnull final AliasMap aliasMap) {
        if (!PredicateWithValue.super.equalsWithoutChildren(other, aliasMap)) {
            return false;
        }
        final ValuePredicate that = (ValuePredicate)other;
        return value.semanticEquals(that.value, aliasMap) &&
               comparison.semanticEquals(that.comparison, aliasMap);
    }

    @Override
    public int planHash(@Nonnull final PlanHashMode mode) {
        return PlanHashable.objectsPlanHash(mode, value, comparison);
    }

    @Override
    public String toString() {
        return value + " " + comparison;
    }
}
