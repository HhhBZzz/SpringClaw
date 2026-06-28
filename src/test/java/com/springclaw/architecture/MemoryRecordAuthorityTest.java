package com.springclaw.architecture;

import com.springclaw.service.chat.impl.ChatResultPersister;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.task.executor.TaskExecutionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRecordAuthorityTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Set<Path> ALLOWED_STORE_CONVERSATION_TURN_FILES = Set.of(
            Path.of("src/main/java/com/springclaw/service/memory/MemoryService.java"),
            Path.of("src/main/java/com/springclaw/service/memory/impl/VectorMemoryService.java")
    );

    @Test
    void productionCodeDoesNotUseVectorConversationTurnAsAuthorityWriter() throws IOException {
        List<Path> offenders;
        try (var paths = Files.walk(MAIN_SOURCE_ROOT)) {
            offenders = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !ALLOWED_STORE_CONVERSATION_TURN_FILES.contains(path))
                    .filter(MemoryRecordAuthorityTest::mentionsStoreConversationTurn)
                    .toList();
        }

        assertThat(offenders)
                .as("durable semantic writes must go through memory_record governance, not direct vector turn writes")
                .isEmpty();
    }

    @Test
    void productionPersistenceServicesDoNotDependOnLegacyMemoryServiceWriter() {
        assertThat(constructorParameterTypes(ChatResultPersister.class))
                .doesNotContain(MemoryService.class);
        assertThat(constructorParameterTypes(TaskExecutionService.class))
                .doesNotContain(MemoryService.class);
    }

    private static boolean mentionsStoreConversationTurn(Path path) {
        try {
            return Files.readString(path).contains(".storeConversationTurn(");
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + path, ex);
        }
    }

    private static List<Class<?>> constructorParameterTypes(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .toList();
    }
}
