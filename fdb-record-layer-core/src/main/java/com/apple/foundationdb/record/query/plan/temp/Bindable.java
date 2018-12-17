/*
 * Bindable.java
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

package com.apple.foundationdb.record.query.plan.temp;

import com.apple.foundationdb.record.query.plan.temp.matchers.ExpressionMatcher;
import com.apple.foundationdb.record.query.plan.temp.matchers.PlannerBindings;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * A planner type that supports rule binding. Both {@link PlannerExpression} and {@link ExpressionRef} implement
 * <code>Bindable</code> to allow binding to both concrete expressions and groups.
 */
public interface Bindable {
    /**
     * Attempt to match the binding to this bindable object.
     * @param binding the binding to match against
     * @return a map of bindings if the match succeeded, or an empty <code>Optional</code> if it failed
     */
    @Nonnull
    default Optional<PlannerBindings> bindWith(@Nonnull ExpressionMatcher<? extends Bindable> binding) {
        return bindWithExisting(binding, new PlannerBindings());
    }

    /**
     * Attempt to match the binding to this bindable object, returning a map consisting of the old keys along with new
     * bindings from this <code>Bindable</code>.
     * The returned map is guaranteed to contain all of the keys from <code>existing</code>.
     * This method is meant to make it easier to implement recursive matching.
     * When implementing a new <code>Bindable</code>, implementing this method is sufficient.
     * When implementing this method, take care to ensure that the the existing bindings are not mutated and that new
     * bindings are added only when a match is certain.
     * @param binding the binding to match against
     * @param existing an existing map of bindings
     * @return a map of bindings if the match succeeded, or an empty <code>Optional</code> if it failed
     */
    @Nonnull
    Optional<PlannerBindings> bindWithExisting(@Nonnull ExpressionMatcher<? extends Bindable> binding, @Nonnull PlannerBindings existing);
}