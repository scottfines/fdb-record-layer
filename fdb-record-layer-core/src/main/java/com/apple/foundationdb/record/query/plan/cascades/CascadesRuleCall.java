/*
 * CascadesRuleCall.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.EvaluationContext;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.query.plan.cascades.Quantifiers.AliasResolver;
import com.apple.foundationdb.record.query.plan.cascades.debug.Debugger;
import com.apple.foundationdb.record.query.plan.cascades.expressions.RelationalExpression;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A rule call implementation for the {@link CascadesPlanner}. This rule call implements the logic for handling new
 * expressions as they are generated by a {@link CascadesRule#onMatch(CascadesRuleCall)} and passed to the rule call
 * via the {@link #yield(ExpressionRef)} method, which consists primarily of manipulating the implicit a memo data
 * structure defined by {@link GroupExpressionRef}s and {@link RelationalExpression}s.
 * <br>
 * An invoked rule can in turn call {@link #yield} or {@link #yieldPartialMatch} to either declare a new resulting
 * expression or to declare a new partial match between the current expression and some candidate expression.
 *
 */
@API(API.Status.EXPERIMENTAL)
public class CascadesRuleCall implements PlannerRuleCall<ExpressionRef<? extends RelationalExpression>>, Memoizer {
    @Nonnull
    private final CascadesRule<?> rule;
    @Nonnull
    private final GroupExpressionRef<RelationalExpression> root;
    @Nonnull
    private final ExpressionRefTraversal traversal;
    @Nonnull
    private final PlannerBindings bindings;
    @Nonnull
    private final PlanContext context;
    @Nonnull
    private final LinkedIdentitySet<RelationalExpression> newExpressions;
    @Nonnull
    private final LinkedIdentitySet<PartialMatch> newPartialMatches;
    @Nonnull
    private final Set<ExpressionRef<? extends RelationalExpression>> referencesWithPushedRequirements;
    @Nonnull
    private final EvaluationContext evaluationContext;

    public CascadesRuleCall(@Nonnull PlanContext context,
                            @Nonnull CascadesRule<?> rule,
                            @Nonnull GroupExpressionRef<RelationalExpression> root,
                            @Nonnull ExpressionRefTraversal traversal,
                            @Nonnull PlannerBindings bindings,
                            @Nonnull final EvaluationContext evaluationContext) {
        this.context = context;
        this.rule = rule;
        this.root = root;
        this.traversal = traversal;
        this.bindings = bindings;
        this.newExpressions = new LinkedIdentitySet<>();
        this.newPartialMatches = new LinkedIdentitySet<>();
        this.referencesWithPushedRequirements = Sets.newLinkedHashSet();
        this.evaluationContext = evaluationContext;
    }

    public void run() {
        rule.onMatch(this);
    }

    @Nonnull
    public ExpressionRef<? extends RelationalExpression> getRoot() {
        return root;
    }

    @Nonnull
    public ExpressionRefTraversal getTraversal() {
        return traversal;
    }

    @Nonnull
    public AliasResolver newAliasResolver() {
        return new AliasResolver(traversal);
    }

    @Override
    @Nonnull
    public PlannerBindings getBindings() {
        return bindings;
    }

    /**
     * Get the planning context with metadata that might be relevant to the planner, such as the list of available
     * indexes.
     *
     * @return a {@link PlanContext} object with various metadata that could affect planning
     */
    @Nonnull
    public PlanContext getContext() {
        return context;
    }

    @Nonnull
    public <T> Optional<T> getPlannerConstraint(@Nonnull final PlannerConstraint<T> plannerConstraint) {
        if (rule.getConstraintDependencies().contains(plannerConstraint)) {
            return root.getConstraintsMap().getConstraintOptional(plannerConstraint);
        }

        throw new RecordCoreArgumentException("rule is not dependent on requested planner requirement");
    }

    @Nonnull
    public Set<ExpressionRef<? extends RelationalExpression>> getReferencesWithPushedRequirements() {
        return referencesWithPushedRequirements;
    }

    @Override
    @SuppressWarnings({"unchecked", "PMD.CompareObjectsWithEquals"}) // deliberate use of == equality check for short-circuit condition
    public void yield(@Nonnull ExpressionRef<? extends RelationalExpression> expressionReference) {
        if (expressionReference == root) {
            return;
        }
        if (expressionReference instanceof GroupExpressionRef) {
            GroupExpressionRef<RelationalExpression> groupExpressionRef = (GroupExpressionRef<RelationalExpression>) expressionReference;
            for (RelationalExpression member : groupExpressionRef.getMembers()) {
                if (root.insertFrom(member, groupExpressionRef)) {
                    newExpressions.add(member);
                    traversal.addExpression(root, member);
                }
            }
        } else {
            throw new RecordCoreArgumentException("found a non-group reference in an expression used by the Cascades planner");
        }
    }

    public void yield(@Nonnull Set<? extends RelationalExpression> expressions) {
        for (RelationalExpression member : expressions) {
            if (root.insert(member)) {
                newExpressions.add(member);
                traversal.addExpression(root, member);
            }
        }
    }

    public void yield(@Nonnull RelationalExpression expression) {
        if (root.insert(expression)) {
            newExpressions.add(expression);
            traversal.addExpression(root, expression);
        }
    }

    /**
     * Notify the planner's data structures that a new partial match has been produced by the rule. This method may be
     * called zero or more times by the rule's <code>onMatch()</code> method.
     *
     * @param boundAliasMap the alias map of bound correlated identifiers between query and candidate
     * @param matchCandidate the match candidate
     * @param queryExpression the query expression
     * @param candidateRef the matching reference on the candidate side
     * @param matchInfo an auxiliary structure to keep additional information about the match
     */
    public void yieldPartialMatch(@Nonnull final AliasMap boundAliasMap,
                                  @Nonnull final MatchCandidate matchCandidate,
                                  @Nonnull final RelationalExpression queryExpression,
                                  @Nonnull final ExpressionRef<? extends RelationalExpression> candidateRef,
                                  @Nonnull final MatchInfo matchInfo) {
        final PartialMatch newPartialMatch =
                new PartialMatch(boundAliasMap,
                        matchCandidate,
                        root,
                        queryExpression,
                        candidateRef,
                        matchInfo);
        root.addPartialMatchForCandidate(matchCandidate, newPartialMatch);
        newPartialMatches.add(newPartialMatch);
    }

    @SuppressWarnings({"PMD.CompareObjectsWithEquals"}) // deliberate use of id equality check for short-circuit condition
    public <T> void pushConstraint(@Nonnull final ExpressionRef<? extends RelationalExpression> reference,
                                   @Nonnull final PlannerConstraint<T> plannerConstraint,
                                   @Nonnull final T requirement) {
        Verify.verify(root != reference);
        final ConstraintsMap requirementsMap = reference.getConstraintsMap();
        if (requirementsMap.pushProperty(plannerConstraint, requirement).isPresent()) {
            referencesWithPushedRequirements.add(reference);
        }
    }

    @Nonnull
    public Collection<RelationalExpression> getNewExpressions() {
        return Collections.unmodifiableCollection(newExpressions);
    }

    @Nonnull
    public Set<PartialMatch> getNewPartialMatches() {
        return newPartialMatches;
    }

    @Nonnull
    public EvaluationContext getEvaluationContext() {
        return evaluationContext;
    }

    @Nonnull
    @Override
    public ExpressionRef<? extends RelationalExpression> memoizeExpression(@Nonnull final RelationalExpression expression) {
        if (expression.getQuantifiers().isEmpty()) {
            return memoizeLeafExpression(expression);
        }

        Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.BEGIN)));
        try {
            Preconditions.checkArgument(!(expression instanceof RecordQueryPlan));

            final var referencePathsList =
                    expression.getQuantifiers()
                            .stream()
                            .map(Quantifier::getRangesOver)
                            .map(traversal::getParentRefPaths)
                            .collect(ImmutableList.toImmutableList());

            final var expressionToReferenceMap = new LinkedIdentityMap<RelationalExpression, ExpressionRef<? extends RelationalExpression>>();
            referencePathsList.stream()
                    .flatMap(Collection::stream)
                    .forEach(referencePath -> {
                        final var referencingExpression = referencePath.getExpression();
                        if (expressionToReferenceMap.containsKey(referencingExpression)) {
                            if (expressionToReferenceMap.get(referencingExpression) != referencePath.getReference()) {
                                throw new RecordCoreException("expression used in multiple references");
                            }
                        } else {
                            expressionToReferenceMap.put(referencePath.getExpression(), referencePath.getReference());
                        }
                    });

            final var referencingExpressions =
                    referencePathsList.stream()
                            .map(referencePaths -> referencePaths.stream().map(ExpressionRefTraversal.ReferencePath::getExpression).collect(LinkedIdentitySet.toLinkedIdentitySet()))
                            .collect(ImmutableList.toImmutableList());

            final var referencingExpressionsIterator = referencingExpressions.iterator();
            final var commonReferencingExpressions = new LinkedIdentitySet<>(referencingExpressionsIterator.next());
            while (referencingExpressionsIterator.hasNext()) {
                commonReferencingExpressions.retainAll(referencingExpressionsIterator.next());
            }

            for (final var commonReferencingExpression : commonReferencingExpressions) {
                if (GroupExpressionRef.containsInMember(commonReferencingExpression, expression)) {
                    Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.REUSED)));
                    return Verify.verifyNotNull(expressionToReferenceMap.get(commonReferencingExpression));
                }
            }
            Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.NEW)));
            final var newRef = GroupExpressionRef.of(expression);
            traversal.addExpression(newRef, expression);
            return newRef;
        } finally {
            Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.END)));
        }
    }

    @Nonnull
    @Override
    public ExpressionRef<? extends RelationalExpression> memoizeLeafExpression(@Nonnull final RelationalExpression expression) {
        Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.BEGIN)));
        try {
            Preconditions.checkArgument(!(expression instanceof RecordQueryPlan));
            Preconditions.checkArgument(expression.getQuantifiers().isEmpty());

            final var leafRefs = traversal.getLeafReferences();

            for (final var leafRef : leafRefs) {
                for (final var member : leafRef.getMembers()) {
                    if (GroupExpressionRef.containsInMember(expression, member)) {
                        Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.REUSED)));
                        return leafRef;
                    }
                }
            }
            Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.NEW)));
            final var newRef = GroupExpressionRef.of(expression);
            traversal.addExpression(newRef, expression);
            return newRef;
        } finally {
            Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.END)));
        }
    }

    @Nonnull
    @Override
    public ExpressionRef<? extends RelationalExpression> memoizeMemberPlans(@Nonnull ExpressionRef<? extends RelationalExpression> reference,
                                                                            @Nonnull final Collection<? extends RecordQueryPlan> plans) {
        return memoizeExpressionsExactly(plans, reference::referenceFromMembers);
    }

    @Nonnull
    @Override
    public ExpressionRef<? extends RecordQueryPlan> memoizePlans(@Nonnull RecordQueryPlan... plans) {
        return memoizePlans(Arrays.asList(plans));
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    @Override
    public ExpressionRef<? extends RecordQueryPlan> memoizePlans(@Nonnull final Collection<? extends RecordQueryPlan> plans) {
        return (ExpressionRef<? extends RecordQueryPlan>)memoizeExpressionsExactly(plans, GroupExpressionRef::from);
    }

    @Nonnull
    @Override
    public ExpressionRef<? extends RelationalExpression> memoizeReference(@Nonnull final ExpressionRef<? extends RelationalExpression> reference) {
        return memoizeExpressionsExactly(reference.getMembers(), members -> reference);
    }

    @Nonnull
    private ExpressionRef<? extends RelationalExpression> memoizeExpressionsExactly(@Nonnull final Collection<? extends RelationalExpression> expressions,
                                                                                    @Nonnull Function<Set<? extends RelationalExpression>, ExpressionRef<? extends RelationalExpression>> referenceCreator) {
        Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.BEGIN)));
        try {
            final var expressionSet = new LinkedIdentitySet<>(expressions);

            if (expressionSet.size() == 1) {
                final Optional<ExpressionRef<? extends RelationalExpression>> memoizedRefMaybe = findExpressionsInMemo(expressionSet);
                if (memoizedRefMaybe.isPresent()) {
                    Debugger.withDebugger(debugger ->
                            expressionSet.forEach(plan -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.REUSED))));
                    return memoizedRefMaybe.get();
                }
            }

            final var newRef = referenceCreator.apply(expressionSet);
            for (final var plan : expressionSet) {
                Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.NEW)));
                traversal.addExpression(newRef, plan);
            }
            return newRef;
        } finally {
            Debugger.withDebugger(debugger -> debugger.onEvent(new Debugger.InsertIntoMemoEvent(Debugger.Location.END)));
        }
    }

    @Nonnull
    private Optional<ExpressionRef<? extends RelationalExpression>> findExpressionsInMemo(final LinkedIdentitySet<? extends RelationalExpression> planSet) {
        final var planIterator = planSet.iterator();
        Verify.verify(planIterator.hasNext());
        final var refsContainingAllPlans = new LinkedIdentitySet<>(traversal.getRefsContaining(planIterator.next()));
        while (planIterator.hasNext()) {
            final var currentRefsContainingPlan = traversal.getRefsContaining(planIterator.next());
            refsContainingAllPlans.retainAll(currentRefsContainingPlan);
        }

        //
        // There should only at most be one exact match which is the ref that contains exactly all plans and nothing else.
        //
        return refsContainingAllPlans.stream()
                .filter(refContainingAllPlans -> refContainingAllPlans.getMembers().size() == planSet.size())
                .findFirst();
    }

    @Nonnull
    @Override
    public ReferenceBuilder memoizeExpressionBuilder(@Nonnull final RelationalExpression expression) {
        return new ReferenceBuilder() {
            @Nonnull
            @Override
            public ExpressionRef<? extends RelationalExpression> reference() {
                return memoizeExpression(expression);
            }

            @Nonnull
            @Override
            public Set<? extends RelationalExpression> members() {
                return LinkedIdentitySet.of(expression);
            }
        };
    }

    @Nonnull
    @Override
    public ReferenceBuilder memoizeMemberPlansBuilder(@Nonnull ExpressionRef<? extends RelationalExpression> reference,
                                                      @Nonnull final Collection<? extends RecordQueryPlan> plans) {
        return memoizeExpressionsBuilder(plans, reference::referenceFromMembers);
    }

    @Nonnull
    @Override
    public ReferenceBuilder memoizePlansBuilder(@Nonnull RecordQueryPlan... plans) {
        return memoizePlansBuilder(Arrays.asList(plans));
    }

    @Nonnull
    @Override
    public ReferenceBuilder memoizePlansBuilder(@Nonnull final Collection<? extends RecordQueryPlan> plans) {
        return memoizeExpressionsBuilder(plans, GroupExpressionRef::from);
    }

    @Nonnull
    private ReferenceBuilder memoizeExpressionsBuilder(@Nonnull final Collection<? extends RelationalExpression> expressions,
                                                       @Nonnull Function<Set<? extends RelationalExpression>, ExpressionRef<? extends RelationalExpression>> refAction) {
        final var expressionSet = new LinkedIdentitySet<>(expressions);
        return new ReferenceBuilder() {
            @Nonnull
            @Override
            public ExpressionRef<? extends RelationalExpression> reference() {
                return memoizeExpressionsExactly(expressions, refAction);
            }

            @Nonnull
            @Override
            public Set<? extends RelationalExpression> members() {
                return expressionSet;
            }
        };
    }
}
