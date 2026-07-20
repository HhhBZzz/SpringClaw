package com.springclaw.service.chat.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelLocalFileWritePlannerTest {

    @Test
    void parsePlan_extractsJsonPlanFromModelText() {
        String raw = """
                计划如下：
                {"supported":true,"relativePath":"Desktop/notes.txt","content":"hello","overwrite":true,"reason":"用户要求创建本地文件"}
                """;

        Optional<LocalFileWritePlan> result = ModelLocalFileWritePlanner.parsePlan(raw, new ObjectMapper());

        assertThat(result).contains(new LocalFileWritePlan(
                "Desktop/notes.txt",
                "hello",
                true,
                "用户要求创建本地文件"
        ));
    }

    @Test
    void parsePlan_ignoresUnsupportedPlan() {
        String raw = "{\"supported\":false,\"reason\":\"不是文件写入\"}";

        Optional<LocalFileWritePlan> result = ModelLocalFileWritePlanner.parsePlan(raw, new ObjectMapper());

        assertThat(result).isEmpty();
    }
}
