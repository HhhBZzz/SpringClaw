package com.springclaw.service.prompt;

import java.util.function.Function;

/**
 * 系统提示词的一个可组合段落(dsh PromptSection 参照)。
 *
 * @param name   注册表唯一名(诊断/去重用,如 "soul")
 * @param title  渲染为 markdown 标题(# {title},如 "角色设定")
 * @param order  组装顺序(小者在前);同 order 按注册顺序
 * @param body   动态提供者:按 {@link PromptSectionContext} 产出正文;
 *               返回 null 表示本次跳过该段
 */
public record PromptSection(
        String name,
        String title,
        int order,
        Function<PromptSectionContext, String> body
) {

    public static PromptSection of(String name, String title, int order,
                                   Function<PromptSectionContext, String> body) {
        return new PromptSection(name, title, order, body);
    }
}
