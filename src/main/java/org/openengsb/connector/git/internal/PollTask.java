package org.openengsb.connector.git.internal;

import org.springframework.security.core.context.SecurityContextHolder;

public class PollTask implements Runnable {

    GitServiceImpl service;

    PollTask(GitServiceImpl service) {
        this.service = service;
    }

    @Override
    public void run() {
        try {
            //authenticate();
            service.poll();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(null);
        }
    }
}
