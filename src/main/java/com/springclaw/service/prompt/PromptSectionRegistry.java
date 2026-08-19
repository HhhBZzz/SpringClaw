package com.springclaw.service.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PromptSection 注册表:名字唯一、按 order 排序、统一渲染为
 * "# {title}\n{body}" 段落序列。系统提示词不再是一整块固定模板,
 * 而是注册进来的 section 组合——新增段落=注册新 section,不动既有代码。
 */
public class PromptSectionRegistry {

    private final Map<String, PromptSection> sections = new LinkedHashMap<>();

    /** 注册一个 section;同名重复注册立即失败(防止静默覆盖漂移)。 */
    public void register(PromptSection section) {
        if (sections.containsKey(section.name())) {
            throw new IllegalStateException("PromptSection 已注册: " + section.name());
        }
        sections.put(section.name(), section);
    }

    /** 按 order(小者在前,同 order 按注册顺序)渲染全部已注册 section;body 为 null 的段跳过。 */
    public String render(PromptSectionContext context) {
        List<String> blocks = new ArrayList<>();
        for (PromptSection section : sortedSections()) {
            String body = section.body().apply(context);
            if (body == null) {
                continue;
            }
            blocks.add("# " + section.title() + "\n" + body);
        }
        return String.join("\n\n", blocks) + "\n";
    }

    /** 已注册 section 名(按 order 排序),供诊断/运维接口展示。 */
    public List<String> sectionNames() {
        return sortedSections().stream().map(PromptSection::name).toList();
    }

    private List<PromptSection> sortedSections() {
        return sections.values().stream()
                .sorted(Comparator.comparingInt(PromptSection::order))
                .toList();
    }
}
