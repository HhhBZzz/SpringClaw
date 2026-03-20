package com.openclaw.service.workspace;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class WorkspaceTaskServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAnalyzeWorkspaceTaskAndReturnRelevantFiles() throws Exception {
        Path serviceFile = tempDir.resolve("src/main/java/com/demo/service/OrderService.java");
        Path controllerFile = tempDir.resolve("src/main/java/com/demo/controller/OrderController.java");

        Files.createDirectories(serviceFile.getParent());
        Files.createDirectories(controllerFile.getParent());

        Files.writeString(serviceFile, """
                package com.demo.service;

                public class OrderService {
                    public String createOrder() {
                        return "ok";
                    }
                }
                """);
        Files.writeString(controllerFile, """
                package com.demo.controller;

                public class OrderController {
                    private final OrderService orderService;
                }
                """);

        WorkspaceTaskService service = new WorkspaceTaskService(
                tempDir.toString(),
                8,
                4,
                6,
                1200,
                512
        );

        String result = service.analyzeTask("帮我分析 OrderService 在哪里实现");

        Assertions.assertTrue(result.contains("WORKSPACE_TASK"));
        Assertions.assertTrue(result.contains("OrderService.java"));
        Assertions.assertTrue(result.contains("createOrder"));
    }
}
