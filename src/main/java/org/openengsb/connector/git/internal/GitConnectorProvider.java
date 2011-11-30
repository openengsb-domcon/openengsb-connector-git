/**
 * Licensed to the Austrian Association for Software Tool Integration (AASTI)
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The AASTI licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openengsb.connector.git.internal;

import org.openengsb.core.api.descriptor.ServiceDescriptor;
import org.openengsb.core.api.descriptor.ServiceDescriptor.Builder;
import org.openengsb.core.common.AbstractConnectorProvider;

public class GitConnectorProvider extends AbstractConnectorProvider {

    @Override
    public ServiceDescriptor getDescriptor() {
        Builder builder = ServiceDescriptor.builder(strings);
        builder.id(this.id);
        builder.name("service.name").description("service.description");
        builder.attribute(builder.newAttribute().id("repository").name("service.repository.name")
                .description("service.repository.description").build());
        builder.attribute(builder.newAttribute().id("workspace").name("service.workspace.name")
                .description("service.workspace.description").build());
        builder.attribute(builder.newAttribute().id("branch").name("service.branch.name")
                .description("service.branch.description").build());
        builder.attribute(builder.newAttribute().id("submodulesHack").name("service.submodulesHack.name")
            .description("service.submodulesHack.description").asBoolean().build());
        builder.attribute(builder.newAttribute().id("pollInterval").name("service.pollInterval.name")
            .description("service.pollInterval.description").build());
        return builder.build();
    }
}
