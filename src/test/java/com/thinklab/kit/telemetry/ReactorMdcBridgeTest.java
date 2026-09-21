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
}
