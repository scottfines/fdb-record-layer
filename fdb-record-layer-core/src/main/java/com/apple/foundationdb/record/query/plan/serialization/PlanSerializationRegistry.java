/*
 * PlanSerializationRegistry.java
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

package com.apple.foundationdb.record.query.plan.serialization;

import com.apple.foundationdb.record.PlanDeserializer;
import com.google.protobuf.Message;

import javax.annotation.Nonnull;

/**
 * Plugin interface to be used by the plan serialization framework to dispatch serialization/deserialiation
 * invocations properly.
 */
public interface PlanSerializationRegistry {

    @Nonnull
    String getTypeUrlPrefix();

    @Nonnull
    <M extends Message> PlanDeserializer<M, ?> lookUpFromProto(@Nonnull Class<M> messageClass);

    @Nonnull
    Class<? extends Message> lookUpMessageClass(@Nonnull String typeUrl);
}
