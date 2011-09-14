package org.openengsb.connector.git.internal;

import org.openengsb.core.api.context.ContextHolder;

public class PollTask implements Runnable {

    private GitServiceImpl service;
    private String contextId;

    PollTask(GitServiceImpl service, String ctx) {
        this.service = service;
        this.contextId = ctx;
    }

    @Override
    public void run() {
        try {
            /* TODO: Do I have to authenticate? */
            String oldCtx = ContextHolder.get().getCurrentContextId();
            ContextHolder.get().setCurrentContextId(contextId);
            service.poll();
            ContextHolder.get().setCurrentContextId(oldCtx);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
