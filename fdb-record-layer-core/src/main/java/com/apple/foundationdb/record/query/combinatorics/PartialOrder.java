/*
 * PartialOrder.java
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

package com.apple.foundationdb.record.query.combinatorics;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class PartialOrder<T> {
    @Nonnull
    private final ImmutableSet<T> set;
    @Nonnull
    private final ImmutableSetMultimap<T, T> dependencyMap;

    public PartialOrder(@Nonnull final Set<T> set, @Nonnull final SetMultimap<T, T> dependencyMap) {
        this.set = ImmutableSet.copyOf(set);
        this.dependencyMap = ImmutableSetMultimap.copyOf(dependencyMap);
    }

    @Nonnull
    public ImmutableSet<T> getSet() {
        return set;
    }


    @Nonnull
    public ImmutableSetMultimap<T, T> getDependencyMap() {
        return dependencyMap;
    }

    int size() {
        return set.size();
    }

    public PartialOrder<T> invertDependencies() {
        return PartialOrder.of(set, dependencyMap.inverse());
    }

    @Nonnull
    public EligibleSet<T> eligibleSet() {
        return new EligibleSet<>(this);
    }

    /**
     * A class allowing to compute a subset of elements in a partial order that are said to be <em>eligible</em>eligible.
     * An <em>eligible</em> element is an element that is smallest with respect to the partial order. In total order,
     * there would only ever be exactly one such element. In a partial order there can be many such elements.
     *
     * Objects of this class should be initially created by calling {@link #eligibleSet()}. The current set of
     * eligible elements can then be retrieved using {@link #eligibleElements()}. In order to advance <em>iteration</em>
     * the caller can remove elements from the set of currently eligible elements, thus creating a new,
     * albeit smaller in cardinality, partial order.
     *
     * By repeatedly removing elements, a caller can consumer the entire partial order in a well-defined way. This
     * concept of iteration is a little more complex than the standard Java {@link java.util.Iterator} approach,
     * as the next step of the iteration is defined by removing a particular subset of elements that is determined
     * by the caller.
     *
     * @param <T> type
     */
    public static class EligibleSet<T> {
        @Nonnull
        private final PartialOrder<T> partialOrder;

        @Nonnull
        private final Map<T, Integer> inDegreeMap;

        @Nonnull
        private final Supplier<Set<T>> eligibleElementsSupplier;

        private EligibleSet(@Nonnull final PartialOrder<T> partialOrder) {
            this.partialOrder = partialOrder;
            this.inDegreeMap = computeInDegreeMap(partialOrder);
            this.eligibleElementsSupplier = Suppliers.memoize(this::computeEligibleElements);
        }

        @Nonnull
        public PartialOrder<T> getPartialOrder() {
            return partialOrder;
        }

        public boolean isEmpty() {
            return eligibleElements().isEmpty();
        }

        @Nonnull
        public Set<T> eligibleElements() {
            return eligibleElementsSupplier.get();
        }

        @Nonnull
        private Set<T> computeEligibleElements() {
            return this.inDegreeMap
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .collect(ImmutableSet.toImmutableSet());
        }

        public EligibleSet<T> removeEligibleElements(@Nonnull final Set<T> toBeRemovedEligibleElements) {
            Preconditions.checkArgument(eligibleElements().containsAll(toBeRemovedEligibleElements));

            final var set = partialOrder.getSet();
            final var newSetBuilder = ImmutableSet.<T>builder();
            for (var element : set) {
                if (!toBeRemovedEligibleElements.contains(element)) {
                    newSetBuilder.add(element);
                }
            }

            final var dependencies = partialOrder.getDependencyMap();
            final var newDependencies = ImmutableSetMultimap.<T, T>builder();
            for (final var entry : dependencies.entries()) {
                Verify.verify(!toBeRemovedEligibleElements.contains(entry.getKey())); // no dependency pointing to the entry
                if (!toBeRemovedEligibleElements.contains(entry.getValue())) {
                    newDependencies.put(entry);
                }
            }

            // create a new eligible set
            return new EligibleSet<>(PartialOrder.of(newSetBuilder.build(), newDependencies.build()));
        }

        @Nonnull
        @SuppressWarnings("java:S3398")
        private static <T> Map<T, Integer> computeInDegreeMap(@Nonnull final PartialOrder<T> partialOrder) {
            final HashMap<T, Integer> result = Maps.newLinkedHashMapWithExpectedSize(partialOrder.size());
            partialOrder.getSet().forEach(element -> result.put(element, 0));

            for (final Map.Entry<T, T> entry : partialOrder.getDependencyMap().entries()) {
                result.compute(entry.getKey(), (t, v) -> Objects.requireNonNull(v) + 1);
            }
            return result;
        }
    }

    @Nonnull
    static <T> ImmutableSetMultimap<T, T> fromFunctionalDependencies(@Nonnull final Set<T> set, @Nonnull final Function<T, Set<T>> dependsOnFn) {
        final ImmutableSetMultimap.Builder<T, T> builder = ImmutableSetMultimap.builder();

        for (final T element : set) {
            final Set<T> dependsOnElements = dependsOnFn.apply(element);
            for (final T dependsOnElement : dependsOnElements) {
                if (set.contains(dependsOnElement)) {
                    builder.put(element, dependsOnElement);
                }
            }
        }
        return builder.build();
    }

    @Nonnull
    static <T> ImmutableSetMultimap<T, T> invertFromFunctionalDependencies(@Nonnull final Set<T> set, @Nonnull final Function<T, Set<T>> dependsOnFn) {
        // invert the dependencies
        final ImmutableSetMultimap.Builder<T, T> builder = ImmutableSetMultimap.builder();

        for (final T element : set) {
            final Set<T> dependsOnElements = dependsOnFn.apply(element);
            for (final T dependsOnElement : dependsOnElements) {
                if (set.contains(dependsOnElement)) {
                    builder.put(dependsOnElement, element);
                }
            }
        }
        return builder.build();
    }

    public static <T> PartialOrder<T> of(@Nonnull final Set<T> set, @Nonnull final SetMultimap<T, T> dependencyMap) {
        return new PartialOrder<>(set, dependencyMap);
    }

    @Nonnull
    public static <T> PartialOrder<T> of(@Nonnull final Set<T> set, @Nonnull final Function<T, Set<T>> dependsOnFn) {
        return of(set, fromFunctionalDependencies(set, dependsOnFn));
    }

    @Nonnull
    public static <T> PartialOrder<T> ofInverted(@Nonnull final Set<T> set, @Nonnull final SetMultimap<T, T> dependencyMap) {
        return ofInverted(set, dependencyMap::get);
    }

    @Nonnull
    public static <T> PartialOrder<T> ofInverted(@Nonnull final Set<T> set, @Nonnull final Function<T, Set<T>> dependsOnFn) {
        return of(set, invertFromFunctionalDependencies(set, dependsOnFn));
    }

    public static <T> PartialOrder.Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        @Nonnull
        private final ImmutableSet.Builder<T> setBuilder;
        @Nonnull
        private final ImmutableSetMultimap.Builder<T, T> dependencyMapBuilder;

        private Builder() {
            this.setBuilder = ImmutableSet.builder();
            this.dependencyMapBuilder = ImmutableSetMultimap.builder();
        }

        public Builder<T> addSet(@Nonnull final Set<T> additionalElements) {
            setBuilder.addAll(additionalElements);
            return this;
        }

        public Builder<T> addListWithDependencies(@Nonnull final List<T> additionalElements) {
            setBuilder.addAll(additionalElements);

            final var iterator = additionalElements.iterator();
            if (iterator.hasNext()) {
                var last = iterator.next();
                while (iterator.hasNext()) {
                    final var current = iterator.next();
                    dependencyMapBuilder.put(current, last);
                    last = current;
                }
            }

            return this;
        }

        public PartialOrder<T> build() {
            return PartialOrder.of(setBuilder.build(), dependencyMapBuilder.build());
        }
    }
}
