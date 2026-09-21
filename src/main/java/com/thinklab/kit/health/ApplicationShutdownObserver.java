package com.thinklab.kit.health;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import com.thinklab.kit.support.HostInfo;
import java.util.Objects;

/**
 * Infrastructure Component: Intercepts application termination to provide structured shutdown telemetry.
 *
 * <p><b>Architectural Role:</b>
 * Ensures that the application lifecycle logs maintain strict SRE observability standards even during
 * graceful shutdowns, preventing blank or `NONE` telemetry markers when container orchestrators
 * (e.g., Kubernetes) issue a SIGTERM signal.
 *
 * @author Thinklab Systems Engineering Team
 * @version 1.1.0-NASA-SRE
 * @since 1.0
 */
@Singleton
@Slf4j
public class ApplicationShutdownObserver implements ApplicationEventListener<ShutdownEvent> {

    private final String infrastructureIpAddress;

    public ApplicationShutdownObserver() {
        this.infrastructureIpAddress = HostInfo.ipAddress("127.0.0.1");
    }

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: ShutdownEvent cannot be null.");

        injectShutdownContext();

        log.warn("[APPLICATION_SHUTDOWN] Graceful teardown signal received. Draining connection pools and unregistering from routing mesh...");
    }

    private void injectShutdownContext() {
        HostInfo.injectSystemContext("SYSTEM-SHUTDOWN", this.infrastructureIpAddress, "Micronaut-Engine/Shutdown");
    }

}
