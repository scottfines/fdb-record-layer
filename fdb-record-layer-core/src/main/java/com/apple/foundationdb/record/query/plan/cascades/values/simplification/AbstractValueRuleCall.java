/*
 * AbstractValueRuleCall.java
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

package com.apple.foundationdb.record.query.plan.cascades.values.simplification;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.cascades.AliasMap;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * A rule call implementation for the simplification of {@link Value} trees. This rule call implements the logic for
 * handling new {@link Value}s as they are generated by a {@link AbstractValueRule#onMatch(PlannerRuleCall)} and
 * passed to the rule call via the {@link #yield(Object)} method.
 * @param <RESULT> the type parameter representing the type of result that is handed to {@link #yield(Object)}
 * @param <CALL> the type of `this`
 *
 */
@API(API.Status.EXPERIMENTAL)
public class AbstractValueRuleCall<RESULT, CALL extends AbstractValueRuleCall<RESULT, CALL>> extends AbstractRuleCall<RESULT, CALL, Value> {
    public AbstractValueRuleCall(@Nonnull final AbstractRule<RESULT, CALL, Value, ? extends Value> rule,
                                 @Nonnull final Value root,
                                 @Nonnull final Value current,
                                 @Nonnull final PlannerBindings bindings,
                                 @Nonnull final AliasMap equivalenceMap,
                                 @Nonnull final Set<CorrelationIdentifier> constantAliases) {
        super(rule, root, current, bindings, equivalenceMap, constantAliases);
    }
}
