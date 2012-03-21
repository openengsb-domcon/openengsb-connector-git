package org.openengsb.connector.git.internal;

import org.openengsb.core.security.BundleAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class PollTask implements Runnable {

    GitServiceImpl service;
    private AuthenticationManager authenticationManager;

    PollTask(GitServiceImpl service, AuthenticationManager am) {
        this.service = service;
        this.authenticationManager = am;
    }

    @Override
    public void run() {

        Authentication oldauth = SecurityContextHolder.getContext().getAuthentication();
        try {
            authenticate();
            service.poll();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(oldauth);
        }
    }

    private void authenticate() {
        SecurityContextHolder.clearContext();
        Authentication token =
            authenticationManager.authenticate(new BundleAuthenticationToken(
                "openengsb-connector-git", ""));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
