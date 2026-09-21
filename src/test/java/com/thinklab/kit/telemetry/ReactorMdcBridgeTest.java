package com.thinklab.kit.telemetry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactorMdcBridgeTest {

    @BeforeAll
    static void setup() {
        Hooks.enableAutomaticContextPropagation();
        ReactorMdcBridge.register();
    }

    @Test
    @DisplayName("Should propagate traceId and clientIp across reactive thread hops")
    void shouldPropagateMdcAcrossThreadHops() {
        Mono<String> pipeline = Mono.just("payload")
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.parallel())
                .map(item -> {
                    String traceId = MDC.get("traceId");
                    String clientIp = MDC.get("clientIp");
                    return traceId + ":" + clientIp;
                })
                .contextWrite(Context.of("traceId", "test-trace-12345", "clientIp", "192.168.1.***"));

        StepVerifier.create(pipeline)
                .expectNext("test-trace-12345:192.168.1.***")
                .verifyComplete();
    }

    @Test
    @DisplayName("Keys missing from the Reactor context are removed from the MDC instead of leaking")
    void shouldClearMdcKeysAbsentFromContext() {
        MDC.put("virtualHost", "stale-host");

        Mono<String> pipeline = Mono.just("payload")
                .map(item -> String.valueOf(MDC.get("virtualHost")))
                .contextWrite(Context.of("traceId", "t-1"));

        StepVerifier.create(pipeline).expectNext("null").verifyComplete();
    }

    @Test
    @DisplayName("The MDC is synchronised on the error signal too")
    void shouldSyncMdcOnError() {
        Mono<String> pipeline = Mono.<String>error(new IllegalStateException("boom"))
                .doOnError(error -> MDC.put("seenOnError", String.valueOf(MDC.get("traceId"))))
                .contextWrite(Context.of("traceId", "err-trace"));

        StepVerifier.create(pipeline).expectError(IllegalStateException.class).verify();

        assertEquals("err-trace", MDC.get("seenOnError"));
    }

    @Test
    @DisplayName("The utility class cannot be instantiated")
    void privateConstructor() throws Exception {
        Constructor<ReactorMdcBridge> constructor = ReactorMdcBridge.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);

        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }
}
