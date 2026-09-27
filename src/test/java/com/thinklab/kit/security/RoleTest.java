package com.thinklab.kit.security;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class RoleTest {

    private static final List<HttpMethod> METHODS =
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    @TestFactory
    @DisplayName("allows() matrix: every role x every method")
    Stream<org.junit.jupiter.api.DynamicTest> matrix() {
        return Stream.of(Role.values()).flatMap(role -> METHODS.stream().map(method -> {
            boolean expected = switch (role) {
                case VIEWER -> method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS;
                case OPERATOR, REQUESTER -> method != HttpMethod.DELETE;
                case ADMIN, SERVICE -> true;
            };
            Executable check = () -> assertEquals(expected, role.allows(method), role + " x " + method);
            return dynamicTest(role + " x " + method + " -> " + expected, check);
        }));
    }
}
