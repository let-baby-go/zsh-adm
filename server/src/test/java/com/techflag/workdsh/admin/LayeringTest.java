package com.techflag.workdsh.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.*;

/** Controllers are entry points only: no bean may receive a controller through its constructor. */
class LayeringTest {
    @Test
    void noBeanDependsOnAController() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : applicationClasses()) {
            for (var constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (parameter.isAnnotationPresent(RestController.class)) {
                        violations.add(type.getSimpleName() + " -> " + parameter.getSimpleName());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Controller dependencies: " + violations);
    }

    @Test
    void onlyAuditLogWritesAuditEvents() throws IOException {
        List<String> writers = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("insert into audit_events")) writers.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of("AuditLog.java"), writers);
    }

    private static List<Class<?>> applicationClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.java$", "");
                classes.add(Class.forName(name));
            }
        }
        return classes;
    }
}
