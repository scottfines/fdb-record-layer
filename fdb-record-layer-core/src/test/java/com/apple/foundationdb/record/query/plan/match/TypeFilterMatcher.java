/*
 * TypeFilterMatcher.java
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

package com.apple.foundationdb.record.query.plan.match;

import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryTypeFilterPlan;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

import javax.annotation.Nonnull;

/**
 * A plan matcher for {@link RecordQueryTypeFilterPlan} with a matcher for the child plan.
 */
public class TypeFilterMatcher extends PlanMatcherWithChild {
    @Nonnull
    private final Matcher<Iterable<? extends String>> typeMatcher;

    public TypeFilterMatcher(@Nonnull Matcher<Iterable<? extends String>> typeMatcher,
                             @Nonnull Matcher<RecordQueryPlan> childMatcher) {
        super(childMatcher);
        this.typeMatcher = typeMatcher;
    }

    @Override
    public boolean matchesSafely(@Nonnull RecordQueryPlan plan) {
        return plan instanceof RecordQueryTypeFilterPlan &&
                typeMatcher.matches(((RecordQueryTypeFilterPlan) plan).getRecordTypes()) &&
                super.matchesSafely(plan);
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("TypeFilter(");
        typeMatcher.describeTo(description);
        description.appendText("; ");
        super.describeTo(description);
        description.appendText(")");
    }
}
