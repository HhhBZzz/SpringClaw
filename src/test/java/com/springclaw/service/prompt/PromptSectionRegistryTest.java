package com.springclaw.service.prompt;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * PromptSection 注册表契约测试(dsh PromptSection 参照,SOUL section 化)。
 * 注册表负责:name 唯一性、order 排序、正文渲染("# 标题\n正文")。
 */
class PromptSectionRegistryTest {

    @Test
    void rendersSectionsInOrderWithMarkdownHeaders() {
        PromptSectionRegistry registry = new PromptSectionRegistry();
        registry.register(PromptSection.of("soul", "角色设定", 10, ctx -> "你是测试 Agent"));
        registry.register(PromptSection.of("runtime-context", "运行上下文", 20, ctx ->
                "- 当前渠道: " + ctx.channel()));

        String prompt = registry.render(new PromptSectionContext("api", "u1", List.of()));

        Assertions.assertEquals("""
                # 角色设定
                你是测试 Agent

                # 运行上下文
                - 当前渠道: api
                """.stripTrailing() + "\n", prompt);
    }

    @Test
    void ordersByOrderFieldRegardlessOfRegistrationSequence() {
        PromptSectionRegistry registry = new PromptSectionRegistry();
        registry.register(PromptSection.of("late", "后段", 90, ctx -> "后注册但排前面"));
        registry.register(PromptSection.of("early", "前段", 5, ctx -> "先注册但排后面"));

        String prompt = registry.render(new PromptSectionContext("api", "u1", List.of()));

        Assertions.assertTrue(prompt.indexOf("先注册但排后面") < prompt.indexOf("后注册但排前面"));
    }

    @Test
    void duplicateNameRegistrationThrows() {
        PromptSectionRegistry registry = new PromptSectionRegistry();
        registry.register(PromptSection.of("soul", "角色设定", 10, ctx -> "a"));
        Assertions.assertThrows(IllegalStateException.class, () ->
                registry.register(PromptSection.of("soul", "角色设定二", 20, ctx -> "b")));
    }

    @Test
    void nullBodySectionIsSkipped() {
        PromptSectionRegistry registry = new PromptSectionRegistry();
        registry.register(PromptSection.of("soul", "角色设定", 10, ctx -> "存在"));
        registry.register(PromptSection.of("optional", "可选段", 20, ctx -> null));

        String prompt = registry.render(new PromptSectionContext("api", "u1", List.of()));

        Assertions.assertTrue(prompt.contains("存在"));
        Assertions.assertFalse(prompt.contains("# 可选段"));
    }

    @Test
    void registeredNamesExposeIntrospection() {
        PromptSectionRegistry registry = new PromptSectionRegistry();
        registry.register(PromptSection.of("soul", "角色设定", 10, ctx -> "x"));
        registry.register(PromptSection.of("skills", "技能", 30, ctx -> "y"));

        Assertions.assertEquals(List.of("soul", "skills"), registry.sectionNames());
    }
}
