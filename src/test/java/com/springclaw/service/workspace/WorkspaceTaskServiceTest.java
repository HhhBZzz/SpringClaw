package com.springclaw.service.workspace;

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

    @Test
    void shouldReturnReadableProjectStructureForStructureQuestion() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/demo/controller"));
        Files.createDirectories(tempDir.resolve("src/main/java/com/demo/service"));
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Files.createDirectories(tempDir.resolve("frontend/src"));
        Files.createDirectories(tempDir.resolve("skills/repo_inspector"));

        Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        Files.writeString(tempDir.resolve("src/main/java/com/demo/DemoApplication.java"), "class DemoApplication {}");
        Files.writeString(tempDir.resolve("src/main/java/com/demo/controller/UserController.java"), "class UserController {}");
        Files.writeString(tempDir.resolve("src/main/java/com/demo/service/UserService.java"), "class UserService {}");
        Files.writeString(tempDir.resolve("src/main/resources/application.yml"), "server:\\n  port: 18080\\n");
        Files.writeString(tempDir.resolve("frontend/package.json"), "{\"scripts\":{\"dev\":\"vite\"}}");
        Files.writeString(tempDir.resolve("frontend/src/App.vue"), "<template>demo</template>");
        Files.writeString(tempDir.resolve("skills/repo_inspector/SKILL.md"), "---\\nname: repo inspector\\n---\\n");

        WorkspaceTaskService service = new WorkspaceTaskService(
                tempDir.toString(),
                8,
                4,
                6,
                3000,
                512
        );

        String result = service.analyzeTask("帮我看看这个项目里结构是怎样的");

        Assertions.assertFalse(result.contains("WORKSPACE_TASK"));
        Assertions.assertTrue(result.contains("项目结构概览"));
        Assertions.assertTrue(result.contains("Spring Boot 后端"));
        Assertions.assertTrue(result.contains("src/main/java"));
        Assertions.assertTrue(result.contains("frontend"));
        Assertions.assertTrue(result.contains("skills"));
    }
}
