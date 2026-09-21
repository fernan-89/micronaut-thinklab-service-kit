package com.thinklab.kit.support;

import org.slf4j.MDC;

import java.io.File;
import java.net.InetAddress;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Resolves the identity of the node the service runs on (IP, hostname, execution environment) and writes
 * the system MDC context used by the lifecycle observers. Every probe has an injectable seam so each
 * fallback branch is unit-testable without touching the real machine.
 */
public final class HostInfo {

    /** Resolves the local address; a functional seam over {@link InetAddress#getLocalHost()}. */
    @FunctionalInterface
    public interface AddressResolver {
        InetAddress resolve() throws Exception;
    }

    private HostInfo() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static String ipAddress(String fallback) {
        return ipAddress(InetAddress::getLocalHost, fallback);
    }

    public static String ipAddress(AddressResolver resolver, String fallback) {
        try {
            return resolver.resolve().getHostAddress();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String hostname() {
        return hostname(System::getenv, InetAddress::getLocalHost);
    }

    public static String hostname(UnaryOperator<String> env, AddressResolver resolver) {
        String envHost = env.apply("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) {
            return envHost;
        }
        String winHost = env.apply("COMPUTERNAME");
        if (winHost != null && !winHost.isBlank()) {
            return winHost;
        }
        try {
            return resolver.resolve().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    public static String executionEnvironment() {
        return executionEnvironment(
                System.getProperty("os.name", "Unknown OS"),
                System.getProperty("os.arch", "Unknown Arch"),
                System::getenv,
                path -> new File(path).exists());
    }

    public static String executionEnvironment(String osName, String osArch, UnaryOperator<String> env, Predicate<String> fileExists) {
        String osContext = String.format("(%s %s)", osName, osArch);
        try {
            if (env.apply("KUBERNETES_SERVICE_HOST") != null) {
                return "Kubernetes Pod " + osContext;
            }
            if (fileExists.test("/.dockerenv") || fileExists.test("/run/.containerenv")) {
                return "Docker Container " + osContext;
            }
            return "Bare-Metal / Local OS " + osContext;
        } catch (SecurityException e) {
            return "Restricted Environment " + osContext;
        }
    }

    /** Writes the system MDC block ({@code traceId}, {@code clientIp}, {@code userAgent}, {@code ip}, {@code client}). */
    public static void injectSystemContext(String traceId, String ip, String agent) {
        MDC.put("traceId", traceId);
        MDC.put("clientIp", ip);
        MDC.put("userAgent", agent);
        MDC.put("ip", ip);
        MDC.put("client", agent);
    }
}
