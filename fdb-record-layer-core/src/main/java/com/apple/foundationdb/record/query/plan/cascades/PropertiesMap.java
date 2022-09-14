/*
 * PropertiesMap.java
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

package com.apple.foundationdb.record.query.plan.cascades;

import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.properties.DistinctRecordsProperty;
import com.apple.foundationdb.record.query.plan.cascades.properties.OrderingProperty;
import com.apple.foundationdb.record.query.plan.cascades.properties.PrimaryKeyProperty;
import com.apple.foundationdb.record.query.plan.cascades.properties.StoredRecordProperty;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class to manage properties for plans.
 */
public class PropertiesMap {
    private static final Set<PlanProperty<?>> planProperties =
            ImmutableSet.<PlanProperty<?>>builder()
                    .add(OrderingProperty.ORDERING)
                    .add(DistinctRecordsProperty.DISTINCT_RECORDS)
                    .add(StoredRecordProperty.STORED_RECORD)
                    .add(PrimaryKeyProperty.PRIMARY_KEY)
                    .build();

    @Nonnull
    private final Deque<RecordQueryPlan> toBeInsertedPlans;
    @Nonnull
    private final Map<RecordQueryPlan, Map<PlanProperty<?>, ?>> planPropertiesMap;
    @Nonnull
    private final SetMultimap<Map<PlanProperty<?>, ?>, RecordQueryPlan> attributeGroupedPlansMap;

    public PropertiesMap(@Nonnull Collection<? extends RelationalExpression> relationalExpressions) {
        this.toBeInsertedPlans = new ArrayDeque<>();
        this.planPropertiesMap = new LinkedIdentityMap<>();
        this.attributeGroupedPlansMap = Multimaps.newSetMultimap(Maps.newLinkedHashMap(), LinkedIdentitySet::new);
        relationalExpressions
                .stream()
                .filter(relationalExpression -> relationalExpression instanceof RecordQueryPlan)
                .map(relationalExpression -> (RecordQueryPlan)relationalExpression)
                .forEach(this::add);
    }

    private void update() {
        while (!toBeInsertedPlans.isEmpty()) {
            final var recordQueryPlan = toBeInsertedPlans.pop();
            final var attributeMapBuilder = ImmutableMap.<PlanProperty<?>, Object>builder();
            for (final var planProperty : planProperties) {
                attributeMapBuilder.put(planProperty, computePropertyValue(planProperty, recordQueryPlan));
            }
            final var propertiesForPlanMap = attributeMapBuilder.build();
            add(recordQueryPlan, propertiesForPlanMap);
        }
    }

    /**
     * Returns the properties currently stored in the properties map for the given plan. Note that
     * {@link #update()} is not called prior to retrieving the properties.
     * @param recordQueryPlan the plan
     * @return a map of properties for the given plan, or {@code null} if the {@link  RecordQueryPlan} passed in is
     *         either stored in the properties map.
     */
    @Nullable
    public Map<PlanProperty<?>, ?> getPropertiesForPlan(@Nonnull final RecordQueryPlan recordQueryPlan) {
        update();
        return getCurrentPropertiesForPlan(recordQueryPlan);
    }

    /**
     * Returns the properties currently stored in the properties map for the given plan. Note that
     * {@link #update()} is not called prior to retrieving the properties.
     * @param recordQueryPlan the plan
     * @return a map of properties for the given plan, or {@code null} if the {@link  RecordQueryPlan} passed in is
     *         either not stored in the properties map or not yet stored in the map (it may be in the queue but is
     *         not yet processed).
     */
    @Nullable
    public Map<PlanProperty<?>, ?> getCurrentPropertiesForPlan(@Nonnull final RecordQueryPlan recordQueryPlan) {
        return planPropertiesMap.get(recordQueryPlan);
    }

    public void add(@Nonnull final RecordQueryPlan recordQueryPlan) {
        toBeInsertedPlans.add(recordQueryPlan);
    }

    public void add(@Nonnull final RecordQueryPlan recordQueryPlan, @Nonnull final Map<PlanProperty<?>, ?> propertiesForPlanMap) {
        Verify.verify(!planPropertiesMap.containsKey(recordQueryPlan));
        planPropertiesMap.put(recordQueryPlan, propertiesForPlanMap);
        attributeGroupedPlansMap.put(propertiesForPlanMap, recordQueryPlan);
    }

    @Nonnull
    private <P> P computePropertyValue(@Nonnull final PlanProperty<P> planProperty,
                                       @Nonnull final RecordQueryPlan recordQueryPlan) {
        final var propertyVisitor = planProperty.createVisitor();
        return propertyVisitor.visit(recordQueryPlan);
    }

    public void clear() {
        toBeInsertedPlans.clear();
        planPropertiesMap.clear();
        attributeGroupedPlansMap.clear();
    }

    @Nonnull
    public <P> Map<RecordQueryPlan, P> getPlannerAttributeForAllPlans(@Nonnull final PlanProperty<P> planProperty) {
        update();
        final var resultMap = new LinkedIdentityMap<RecordQueryPlan, P>();
        for (final var entry : planPropertiesMap.entrySet()) {
            resultMap.put(entry.getKey(), planProperty.narrowAttribute(entry.getValue().get(planProperty)));
        }

        return resultMap;
    }

    @SuppressWarnings("UnstableApiUsage")
    @Nonnull
    public List<PlanPartition> getPlanPartitions() {
        update();
        return PlanPartition.toPlanPartitions(Multimaps.asMap(attributeGroupedPlansMap));
    }

    @Nonnull
    public static Set<PlanProperty<?>> allAttributesExcept(final PlanProperty<?>... exceptAttributes) {
        final var exceptAttributesSet = ImmutableSet.copyOf(Arrays.asList(exceptAttributes));
        return ImmutableSet.copyOf(Sets.difference(planProperties, exceptAttributesSet));
    }
}
