package com.thinklab.kit.health;

import io.micronaut.context.event.ShutdownEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationShutdownObserverTest {

    @Test
    @DisplayName("Should inject SYSTEM-SHUTDOWN telemetry into MDC on shutdown event")
    void testShutdownTelemetry() {
        ApplicationShutdownObserver observer = new ApplicationShutdownObserver();
        ShutdownEvent shutdownEvent = Mockito.mock(ShutdownEvent.class);

        assertDoesNotThrow(() -> observer.onApplicationEvent(shutdownEvent));

        assertEquals("SYSTEM-SHUTDOWN", MDC.get("traceId"));
        assertEquals("Micronaut-Engine/Shutdown", MDC.get("userAgent"));
    }
}
