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

import java.util.Map;

import org.openengsb.core.api.Connector;
import org.openengsb.core.common.AbstractConnectorInstanceFactory;
import org.openengsb.domain.scm.ScmDomainEvents;

public class GitServiceInstanceFactory extends AbstractConnectorInstanceFactory<GitServiceImpl> {

    private ScmDomainEvents scmEvents;

    @Override
    public Connector createNewInstance(String id) {
        return new GitServiceImpl(id, scmEvents);
    }

    @Override
    public void doApplyAttributes(GitServiceImpl instance, Map<String, String> attributes) {
        if (attributes.containsKey("repository")) {
            instance.setRemoteLocation(attributes.get("repository"));
        }
        if (attributes.containsKey("workspace")) {
            instance.setLocalWorkspace(attributes.get("workspace"));
        }
        if (attributes.containsKey("branch")) {
            instance.setWatchBranch(attributes.get("branch"));
        }
        if (attributes.containsKey("submodulesHack")) {
            instance.setSubmodulesHack(attributes.get("submodulesHack"));
        }
        if (attributes.containsKey("pollInterval")) {
            instance.setPollInterval(attributes.get("pollInterval"));
        }
        instance.startPoller();
    }

    public void setScmEvents(ScmDomainEvents scmEvents) {
        this.scmEvents = scmEvents;
    }

}
