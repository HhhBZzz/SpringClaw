package com.springclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalToolOwnershipTest {

    private static final Pattern CONSTRUCTION = Pattern.compile(
            "new\\s+ToolExecutionContext\\s*\\((.*?)\\)\\s*;",
            Pattern.DOTALL
    );

    private static final List<String> FILES = List.of(
            "src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java",
            "src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java"
    );

    @Test
    void allProductionToolContextsCarryRequestAndRunOwnership() throws IOException {
        int constructionCount = 0;
        for (String file : FILES) {
            Matcher matcher = CONSTRUCTION.matcher(
                    Files.readString(Path.of(file))
            );
            while (matcher.find()) {
                constructionCount++;
                List<String> arguments = topLevelArguments(matcher.group(1));
                assertThat(arguments)
                        .as(file + " construction " + constructionCount)
                        .hasSize(7);
                if (file.endsWith("ToolProposalExecutionService.java")) {
                    assertThat(arguments.get(3)).isEqualTo("proposal.requestId()");
                    assertThat(arguments.get(5)).isEqualTo("proposal.runId()");
                } else {
                    assertThat(arguments.get(5)).isEqualTo(arguments.get(3));
                }
            }
        }
        assertThat(constructionCount).isEqualTo(14);
    }

    private List<String> topLevelArguments(String arguments) {
        List<String> values = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < arguments.length(); index++) {
            char value = arguments.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '(' || value == '[' || value == '{') {
                depth++;
            } else if (value == ')' || value == ']' || value == '}') {
                depth--;
            } else if (value == ',' && depth == 0) {
                values.add(normalizeArgument(arguments.substring(start, index)));
                start = index + 1;
            }
        }
        if (!arguments.isBlank()) {
            values.add(normalizeArgument(arguments.substring(start)));
        }
        return values;
    }

    private String normalizeArgument(String argument) {
        return argument.replaceAll("(?m)^\\s*//.*(?:\\R|$)", "").trim();
    }
}
