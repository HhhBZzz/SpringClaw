package com.springclaw.tool.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一能力注册中心。
 * <p>
 * 扫描所有标注了 {@link ToolPackDescriptor} 的 ToolPack bean，
 * 提供基于触发关键词、兜底候选、意图等维度的查询能力。
 * 替代原先分散在多个文件中的 containsAny 硬编码关键词匹配。
 * </p>
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    private final List<CapabilityEntry> entries;

    /** 测试用构造函数：直接传入已知的能力条目列表 */
    public CapabilityRegistry(List<CapabilityEntry> entries) {
        this.entries = entries.stream()
                .sorted(Comparator.comparing(e -> e.descriptor.id()))
                .toList();
        log.info("CapabilityRegistry 已通过测试构造函数初始化 {} 个能力", entries.size());
    }

    @Autowired
    public CapabilityRegistry(ApplicationContext applicationContext) {
        List<CapabilityEntry> found = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class, false, false);
        for (String beanName : beanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            ToolPackDescriptor descriptor = findDescriptor(beanType);
            Object bean = null;
            if (descriptor == null && looksLikeToolPack(beanName, beanType)) {
                try {
                    bean = applicationContext.getBean(beanName);
                    descriptor = findDescriptor(AopUtils.getTargetClass(bean));
                    if (descriptor == null) {
                        descriptor = findDescriptor(bean.getClass());
                    }
                } catch (Exception ex) {
                    log.debug("跳过能力扫描 bean={}，reason={}", beanName, ex.getMessage());
                    continue;
                }
            }
            if (descriptor == null || !StringUtils.hasText(descriptor.id()) || !seenIds.add(descriptor.id())) {
                continue;
            }
            if (bean == null) {
                bean = applicationContext.getBean(beanName);
            }
            found.add(new CapabilityEntry(descriptor, bean, beanName));
        }
        this.entries = found.stream()
                .sorted(Comparator.comparing(e -> e.descriptor.id()))
                .toList();
        log.info("CapabilityRegistry 已初始化 {} 个能力: {}",
                entries.size(),
                entries.stream().map(e -> e.descriptor.id()).collect(Collectors.joining(", ")));
    }

    private boolean looksLikeToolPack(String beanName, Class<?> beanType) {
        String normalizedName = beanName == null ? "" : beanName.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("toolpack")) {
            return true;
        }
        if (beanType == null) {
            return false;
        }
        String simpleName = ClassUtils.getUserClass(beanType).getSimpleName().toLowerCase(Locale.ROOT);
        return simpleName.contains("toolpack");
    }

    private ToolPackDescriptor findDescriptor(Class<?> candidateClass) {
        if (candidateClass == null) {
            return null;
        }
        Class<?> userClass = ClassUtils.getUserClass(candidateClass);
        ToolPackDescriptor descriptor = AnnotatedElementUtils.findMergedAnnotation(userClass, ToolPackDescriptor.class);
        if (descriptor != null) {
            return descriptor;
        }
        return AnnotatedElementUtils.findMergedAnnotation(candidateClass, ToolPackDescriptor.class);
    }

    /** 工厂方法：创建一个仅含元数据的测试用条目（无实际 ToolPack bean） */
    public static CapabilityEntry entryForTest(String id, String toolset, String[] triggerKeywords,
                                                boolean fallbackCandidate, String riskLevel, String description) {
        // 构造一个仅用于测试的 ToolPackDescriptor 实例
        ToolPackDescriptor descriptor = new ToolPackDescriptor() {
            @Override public String id() { return id; }
            @Override public String toolset() { return toolset; }
            @Override public String[] triggerKeywords() { return triggerKeywords; }
            @Override public boolean fallbackCandidate() { return fallbackCandidate; }
            @Override public String riskLevel() { return riskLevel; }
            @Override public String preferredMode() { return "simplified"; }
            @Override public String description() { return description; }
            @Override public boolean includeForAgentMode() { return true; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return ToolPackDescriptor.class; }
        };
        return new CapabilityEntry(descriptor, null, id);
    }

    /** 列出所有已注册的能力元数据 */
    public List<CapabilityEntry> listAll() {
        return entries;
    }

    /** 列出所有已注册能力的人类可读视图（用于前端 API） */
    public List<Map<String, Object>> listViews() {
        return entries.stream()
                .map(CapabilityEntry::toView)
                .toList();
    }

    /** 列出具体 @Tool 方法级目录，供前端展示工具能力、风险和确认边界。 */
    public List<Map<String, Object>> listToolViews() {
        return entries.stream()
                .flatMap(entry -> entry.toToolViews().stream())
                .toList();
    }

    /**
     * 按触发关键词匹配能力，返回匹配列表（按匹配关键词数量降序）。
     *
     * @param text 用户输入文本
     * @return 匹配的能力条目列表，最佳匹配在前
     */
    public List<CapabilityEntry> findByTriggerKeywords(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> e.matchesKeywords(lower))
                .sorted(Comparator.<CapabilityEntry>comparingInt(e -> e.countKeywordMatches(lower)).reversed())
                .toList();
    }

    /**
     * 查找所有标记为兜底候选且触发关键词匹配的能力。
     * 用于模型不可用时的本地兜底执行。
     *
     * @param text 用户输入文本
     * @return 匹配的兜底候选列表，最佳匹配在前
     */
    public List<CapabilityEntry> findFallbackCandidates(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(CapabilityEntry::isFallbackCandidate)
                .filter(e -> e.matchesKeywords(lower))
                .sorted(Comparator.<CapabilityEntry>comparingInt(e -> e.countKeywordMatches(lower)).reversed())
                .toList();
    }

    /**
     * 查找最佳匹配的单个兜底候选。
     */
    public CapabilityEntry findBestFallback(String text) {
        List<CapabilityEntry> candidates = findFallbackCandidates(text);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 按能力 ID 精确查找。
     */
    public CapabilityEntry findById(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return entries.stream()
                .filter(e -> e.descriptor.id().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /**
     * 能力条目：将 ToolPackDescriptor 元数据与实际的 ToolPack bean 实例配对。
     */
    public static class CapabilityEntry {
        private final ToolPackDescriptor descriptor;
        private final Object toolPackBean;
        private final String beanName;

        public CapabilityEntry(ToolPackDescriptor descriptor, Object toolPackBean, String beanName) {
            this.descriptor = descriptor;
            this.toolPackBean = toolPackBean;
            this.beanName = beanName;
        }

        public ToolPackDescriptor descriptor() { return descriptor; }
        public Object toolPackBean() { return toolPackBean; }
        public String beanName() { return beanName; }

        public String id() { return descriptor.id(); }
        public String toolset() { return descriptor.toolset(); }
        public String riskLevel() { return descriptor.riskLevel(); }
        public boolean isFallbackCandidate() { return descriptor.fallbackCandidate(); }
        public String preferredMode() { return descriptor.preferredMode(); }
        public String description() { return descriptor.description(); }
        public boolean includeForAgentMode() { return descriptor.includeForAgentMode(); }
        public String[] triggerKeywords() { return descriptor.triggerKeywords(); }

        public boolean matchesKeywords(String lowerText) {
            for (String keyword : descriptor.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        public int countKeywordMatches(String lowerText) {
            int count = 0;
            for (String keyword : descriptor.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                    count++;
                }
            }
            return count;
        }

        Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", descriptor.id());
            view.put("toolset", descriptor.toolset());
            view.put("triggerKeywords", List.of(descriptor.triggerKeywords()));
            view.put("riskLevel", descriptor.riskLevel());
            view.put("fallbackCandidate", descriptor.fallbackCandidate());
            view.put("preferredMode", descriptor.preferredMode());
            view.put("description", StringUtils.hasText(descriptor.description())
                    ? descriptor.description()
                    : beanName);
            view.put("beanName", beanName);
            return view;
        }

        List<Map<String, Object>> toToolViews() {
            if (toolPackBean == null) {
                return List.of(toPackToolView());
            }
            Class<?> targetClass = AopUtils.getTargetClass(toolPackBean);
            if (targetClass == null) {
                targetClass = ClassUtils.getUserClass(toolPackBean);
            }
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Method method : targetClass.getDeclaredMethods()) {
                Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
                if (tool == null) {
                    continue;
                }
                tools.add(toMethodToolView(targetClass, method, tool));
            }
            tools.sort(Comparator.comparing(view -> String.valueOf(view.get("name"))));
            return tools.isEmpty() ? List.of(toPackToolView()) : tools;
        }

        private Map<String, Object> toPackToolView() {
            Map<String, Object> view = toView();
            view.put("packId", descriptor.id());
            view.put("requiredToolPacks", StringUtils.hasText(descriptor.toolset()) ? Set.of(descriptor.toolset()) : Set.of());
            view.put("requiresConfirmation", requiresConfirmation(descriptor.riskLevel()));
            return view;
        }

        private Map<String, Object> toMethodToolView(Class<?> targetClass, Method method, Tool tool) {
            Map<String, Object> view = new LinkedHashMap<>();
            String methodName = method.getName();
            String toolName = StringUtils.hasText(tool.name()) ? tool.name().trim() : methodName;
            view.put("name", toolName);
            view.put("methodName", methodName);
            view.put("runtimeToolName", targetClass.getSimpleName() + "." + methodName);
            view.put("packId", descriptor.id());
            view.put("toolset", descriptor.toolset());
            view.put("requiredToolPacks", StringUtils.hasText(descriptor.toolset()) ? Set.of(descriptor.toolset()) : Set.of());
            view.put("triggerKeywords", List.of(descriptor.triggerKeywords()));
            view.put("riskLevel", descriptor.riskLevel());
            view.put("requiresConfirmation", requiresConfirmation(descriptor.riskLevel()));
            view.put("fallbackCandidate", descriptor.fallbackCandidate());
            view.put("preferredMode", descriptor.preferredMode());
            view.put("description", StringUtils.hasText(tool.description()) ? tool.description() : descriptor.description());
            view.put("beanName", beanName);
            view.put("packDescription", descriptor.description());
            view.put("returnDirect", tool.returnDirect());
            view.put("parameters", renderParameters(method));
            return view;
        }

        private List<Map<String, Object>> renderParameters(Method method) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", parameter.getName());
                item.put("type", parameter.getType().getSimpleName());
                parameters.add(item);
            }
            return parameters;
        }

        private boolean requiresConfirmation(String riskLevel) {
            String risk = riskLevel == null ? "" : riskLevel.trim().toLowerCase(Locale.ROOT);
            return "write".equals(risk)
                    || "side_effect".equals(risk)
                    || "execution".equals(risk)
                    || "dangerous".equals(risk);
        }
    }
}
