package com.springclaw.service.chat.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 工具反射公共辅助——穿透 Spring CGLIB 代理扫 {@link Tool} 注解。
 * <p>从 {@link ReActEngine} 提取(行为不变),供 {@link ExplicitToolExecutioner}(手动循环按名定位 @Tool 方法)
 * 与 {@link ReActEngine#renderReActPrompt} 的工具列表渲染共用。Plan-Execute 范式引擎(Task 4)同样复用。</p>
 * <p>无状态静态工具类——所有方法纯反射,不持有依赖。</p>
 */
public final class ToolReflectionSupport {

    private ToolReflectionSupport() {
    }

    /**
     * 工具定位结果:Spring 代理 bean + {@link Tool} 方法。成对返回——调用方需 bean(反射 invoke 目标)
     * 与 method(设 accessible + invoke)两者缺一不可,故不拆成 {@code Optional<Method>}。
     */
    public record ToolMethod(Object bean, Method method) {
    }

    /**
     * 穿透 Spring CGLIB 代理取真实业务类({@code $SpringCGLIB$$} 后缀 → superclass)。
     * 反射扫 {@code getDeclaredMethods} 必须在真实类上,否则命中不到 @Tool 注解。
     */
    public static Class<?> getTargetClass(Object bean) {
        if (bean.getClass().getName().contains("$SpringCGLIB$")) {
            Class<?> superclass = bean.getClass().getSuperclass();
            if (superclass != null && !superclass.getName().contains("$SpringCGLIB$")) {
                return superclass;
            }
        }
        return bean.getClass();
    }

    /**
     * 反射扫描工具 bean 上的 {@link Tool} 注解,列成 "- name(param1, param2): description" 清单。
     * <p>与 {@link AutonomousLoopEngine#renderToolList} 等价(含 CGLIB 代理穿透),ReAct 手动循环主路径
     * 据此清单格式化 {@code Action: toolName(param="value")}。</p>
     */
    public static String renderToolList(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return "（无可用工具）";
        }
        StringBuilder builder = new StringBuilder();
        for (Object toolBean : tools) {
            if (toolBean == null) continue;
            Class<?> targetClass = getTargetClass(toolBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                Tool toolAnno = method.getAnnotation(Tool.class);
                if (toolAnno != null) {
                    String toolName = StringUtils.hasText(toolAnno.name()) ? toolAnno.name() : method.getName();
                    String description = toolAnno.description();
                    builder.append("- ").append(toolName)
                            .append("(").append(renderParamSignature(method)).append(")")
                            .append(": ").append(description).append("\n");
                }
            }
        }
        return builder.toString().trim();
    }

    /**
     * 在 tools 数组里按名(忽略大小写)查找 @Tool 方法。工具名取 {@link Tool#name()}(空则取方法名)。
     * 经 {@link #getTargetClass} 穿透 CGLIB 代理。找不到返回 {@link Optional#empty()}。
     */
    public static Optional<ToolMethod> findToolMethod(Object[] tools, String toolName) {
        if (tools == null || !StringUtils.hasText(toolName)) return Optional.empty();
        for (Object bean : tools) {
            if (bean == null) continue;
            Class<?> targetClass = getTargetClass(bean);
            for (Method method : targetClass.getDeclaredMethods()) {
                Tool anno = method.getAnnotation(Tool.class);
                if (anno == null) continue;
                String name = StringUtils.hasText(anno.name()) ? anno.name() : method.getName();
                if (toolName.equalsIgnoreCase(name)) {
                    return Optional.of(new ToolMethod(bean, method));
                }
            }
        }
        return Optional.empty();
    }

    /** 渲染方法形参名清单(逗号分隔),供手动循环主路径 LLM 格式化 Action 入参。 */
    private static String renderParamSignature(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length == 0) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (Parameter p : params) {
            sj.add(p.getName());
        }
        return sj.toString();
    }
}
