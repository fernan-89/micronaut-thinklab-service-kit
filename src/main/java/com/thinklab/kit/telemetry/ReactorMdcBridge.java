package com.thinklab.kit.telemetry;

import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Infrastructure Component: Project Reactor to SLF4J MDC Bridge.
 *
 * <p><b>Architectural Role:</b>
 * Automatically synchronizes Project Reactor's reactive execution context ({@code Context}) with SLF4J's
 * thread-local Mapped Diagnostic Context ({@code MDC}) across asynchronous execution and thread boundaries
 * (e.g., from Netty event loops to worker thread pools).
 *
 * @author Thinklab Systems Engineering Team
 * @version 1.3.0
 * @since 1.0
 */
public final class ReactorMdcBridge {

    private static final String[] MDC_KEYS = {"traceId", "clientIp", "userAgent", "virtualHost", "clientOrigin", "externalClientHost"};

    private ReactorMdcBridge() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void register() {
        Hooks.onEachOperator("thinklab-mdc-bridge", Operators.lift((scannable, subscriber) -> new MdcCoreSubscriber<>(subscriber)));
    }

    private static class MdcCoreSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<T> actual;

        MdcCoreSubscriber(CoreSubscriber<T> actual) {
            this.actual = actual;
        }

        @Override
        public void onSubscribe(Subscription s) {
            actual.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            syncMdc(actual.currentContext());
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            syncMdc(actual.currentContext());
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            syncMdc(actual.currentContext());
            actual.onComplete();
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        private void syncMdc(Context context) {
            for (String key : MDC_KEYS) {
                if (context.hasKey(key)) {
                    MDC.put(key, context.get(key));
                } else {
                    MDC.remove(key);
                }
            }
        }
    }
}
