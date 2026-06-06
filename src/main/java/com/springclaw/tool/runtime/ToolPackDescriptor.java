package com.springclaw.tool.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为 ToolPack 提供自描述能力。
 * <p>
 * 每个 ToolPack 通过此注声明自己的 id、触发关键词、风险等级、
 * 是否为兜底候选、推荐执行模式等信息。
 * 系统根据这些元数据自动完成路由匹配、兜底执行和前端展示，
 * 不再需要在多个文件中硬编码中文关键词。
 * </p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolPackDescriptor {

    /** 能力唯一标识 */
    String id();

    /** 所属工具集，如 web、file、workspace、system、script */
    String toolset() default "core";

    /**
     * 触发关键词列表。
     * 用户输入命中任一关键词时，该 ToolPack 被选中。
     * 支持中英文混合。
     */
    String[] triggerKeywords() default {};

    /**
     * 是否为本地兜底候选。
     * 模型不可用时，后端直接调用该 ToolPack 返回结果。
     */
    boolean fallbackCandidate() default false;

    /** 风险等级：read / write / side_effect / execution / dangerous */
    String riskLevel() default "read";

    /**
     * 推荐执行模式。
     * simplified — 简化模式即可
     * opar — 建议走 OPAR 深度链路
     */
    String preferredMode() default "simplified";

    /** 人类可读的能力描述，用于前端展示 */
    String description() default "";

    /** 是否包含在 Agent 模式中 */
    boolean includeForAgentMode() default true;
}
