package com.thinklab.kit.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostInfoTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    @DisplayName("the resolved address is returned, and the fallback when resolution fails")
    void ipAddress() throws Exception {
        assertEquals("127.0.0.1", HostInfo.ipAddress(() -> InetAddress.getByName("127.0.0.1"), "fallback"));
        assertEquals("fallback", HostInfo.ipAddress(() -> {
            throw new java.net.UnknownHostException("no");
        }, "fallback"));
        assertNotNull(HostInfo.ipAddress("fallback"));
    }

    @Test
    @DisplayName("the hostname prefers HOSTNAME, then COMPUTERNAME, then the resolver, then a placeholder")
    void hostnameChain() throws Exception {
        assertEquals("from-env", HostInfo.hostname(Map.of("HOSTNAME", "from-env", "COMPUTERNAME", "win")::get, null));
        assertEquals("win", HostInfo.hostname(Map.of("HOSTNAME", " ", "COMPUTERNAME", "win")::get, null));
        assertEquals("win", HostInfo.hostname(Map.of("COMPUTERNAME", "win")::get, null));
        assertEquals("localhost", HostInfo.hostname(key -> null, () -> InetAddress.getByName("localhost")));
        assertEquals("unknown-host", HostInfo.hostname(Map.of("COMPUTERNAME", " ")::get, () -> {
            throw new java.net.UnknownHostException("no");
        }));
        assertFalse(HostInfo.hostname().isBlank());
    }

    @Test
    @DisplayName("the execution environment is Kubernetes, Docker, bare metal or restricted")
    void executionEnvironment() {
        assertEquals("Kubernetes Pod (Linux amd64)", HostInfo.executionEnvironment("Linux", "amd64", key -> "10.0.0.1", path -> true));
        assertEquals("Docker Container (Linux amd64)", HostInfo.executionEnvironment("Linux", "amd64", key -> null, "/.dockerenv"::equals));
        assertEquals("Docker Container (Linux amd64)", HostInfo.executionEnvironment("Linux", "amd64", key -> null, "/run/.containerenv"::equals));
        assertEquals("Bare-Metal / Local OS (Windows 10 x64)", HostInfo.executionEnvironment("Windows", "10 x64", key -> null, path -> false));
        assertEquals("Restricted Environment (Linux arm64)", HostInfo.executionEnvironment("Linux", "arm64", key -> {
            throw new SecurityException("denied");
        }, path -> false));
        assertTrue(HostInfo.executionEnvironment().contains("("));
    }

    @Test
    @DisplayName("the system MDC block carries the trace id, address and agent under both key styles")
    void injectSystemContext() {
        HostInfo.injectSystemContext("SYSTEM-BOOT", "10.0.0.1", "agent");

        assertEquals("SYSTEM-BOOT", MDC.get("traceId"));
        assertEquals("10.0.0.1", MDC.get("clientIp"));
        assertEquals("10.0.0.1", MDC.get("ip"));
        assertEquals("agent", MDC.get("userAgent"));
        assertEquals("agent", MDC.get("client"));
    }

    @Test
    @DisplayName("the utility class cannot be instantiated")
    void privateConstructor() throws Exception {
        Constructor<HostInfo> constructor = HostInfo.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);

        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }
}
