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
        return builder.build();
    }
}
