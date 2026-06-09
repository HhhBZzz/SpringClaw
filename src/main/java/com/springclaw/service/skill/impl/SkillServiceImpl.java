package com.springclaw.service.skill.impl;

import com.springclaw.common.util.TextUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.domain.entity.SkillDescriptor;
import com.springclaw.domain.entity.SkillPolicy;
import com.springclaw.mapper.SkillDescriptorMapper;
import com.springclaw.mapper.SkillPolicyMapper;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

/**
 * Skill 服务实现。
 *
 * 设计说明：
 * 1. 优先走 MySQL 元数据/策略；数据库不可用时自动降级到本地默认技能，保证可用性。
 * 2. 策略支持 channel/user 的精确匹配和 * 通配，按“精确优先”覆盖。
 */
@Service
public class SkillServiceImpl extends ServiceImpl<SkillDescriptorMapper, SkillDescriptor> implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);
    private static final long DB_RETRY_INTERVAL_MS = 30_000L;

    private final SkillPolicyMapper skillPolicyMapper;
    private final SkillRegistryService skillRegistryService;
    private final boolean skillEnabled;
    private final boolean dbEnabled;
    private final boolean defaultSystemEnabled;
    private final boolean defaultFileEnabled;
    private final boolean defaultBusinessEnabled;
    private volatile long dbRetryAt = 0L;

    public SkillServiceImpl(SkillPolicyMapper skillPolicyMapper,
                            SkillRegistryService skillRegistryService,
                            @Value("${springclaw.skill.enabled:true}") boolean skillEnabled,
                            @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                            @Value("${springclaw.skill.default-system-enabled:true}") boolean defaultSystemEnabled,
                            @Value("${springclaw.skill.default-file-enabled:true}") boolean defaultFileEnabled,
                            @Value("${springclaw.skill.default-business-enabled:true}") boolean defaultBusinessEnabled) {
        this.skillPolicyMapper = skillPolicyMapper;
        this.skillRegistryService = skillRegistryService;
        this.skillEnabled = skillEnabled;
        this.dbEnabled = dbEnabled;
        this.defaultSystemEnabled = defaultSystemEnabled;
        this.defaultFileEnabled = defaultFileEnabled;
        this.defaultBusinessEnabled = defaultBusinessEnabled;
    }

    @Override
    public Set<String> resolveAllowedToolPacks(String channel, String userId) {
        if (!skillEnabled) {
            return Collections.emptySet();
        }

        String channelKey = TextUtils.normalize(channel);
        String userKey = TextUtils.normalize(userId);
        List<SkillDescriptor> descriptors = loadDescriptors();
        if (descriptors.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> skillIds = descriptors.stream()
                .map(SkillDescriptor::getSkillId)
                .filter(StringUtils::hasText)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Map<String, Boolean> policyMap = loadPolicyMap(channelKey, userKey, skillIds);

        LinkedHashSet<String> allowedToolPacks = new LinkedHashSet<>();
        for (SkillDescriptor descriptor : descriptors) {
            String skillId = TextUtils.safe(descriptor.getSkillId());
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            boolean enabled = descriptor.getEnabled() == null || descriptor.getEnabled() == 1;
            boolean allowed = policyMap.getOrDefault(skillId, true);
            if (enabled && allowed) {
                String toolPack = TextUtils.safe(descriptor.getToolPack()).toLowerCase();
                if (StringUtils.hasText(toolPack)) {
                    allowedToolPacks.add(toolPack);
                }
            }
        }
        return allowedToolPacks;
    }

    @Override
    public String describeAvailableSkills(String channel, String userId) {
        if (!skillEnabled) {
            return "（Skill 功能未开启）";
        }

        Set<String> allowedToolPacks = resolveAllowedToolPacks(channel, userId);
        List<String> explicitSkillLines = skillRegistryService.listAgentVisibleDefinitions(allowedToolPacks).stream()
                .map(this::toRuntimeSkillLine)
                .toList();
        List<String> genericSkillLines = buildGenericSkillLines(allowedToolPacks, collectCoveredToolPacks(allowedToolPacks));

        if (explicitSkillLines.isEmpty() && genericSkillLines.isEmpty()) {
            return "（暂无可用技能）";
        }
        return joinSections(
                explicitSkillLines.isEmpty() ? null : "显式技能",
                explicitSkillLines,
                genericSkillLines.isEmpty() ? null : "基础能力",
                genericSkillLines
        );
    }

    @Override
    public String describeCoreSkills(String channel, String userId) {
        if (!skillEnabled) {
            return "（核心技能未开启）";
        }
        Set<String> allowed = resolveAllowedToolPacks(channel, userId);
        List<SkillDefinition> coreDefinitions = skillRegistryService.listCoreDefinitions(allowed);
        Set<String> coveredToolPacks = collectCoveredToolPacks(coreDefinitions);
        List<String> explicitLines = coreDefinitions.stream()
                .map(this::toRuntimeSkillLine)
                .toList();
        List<String> genericLines = buildGenericCoreLines(allowed, coveredToolPacks);
        if (explicitLines.isEmpty() && genericLines.isEmpty()) {
            return "（暂无核心技能）";
        }
        return joinSections(
                explicitLines.isEmpty() ? null : "核心显式技能",
                explicitLines,
                genericLines.isEmpty() ? null : "核心基础能力",
                genericLines
        );
    }

    private String toRuntimeSkillLine(SkillDefinition definition) {
        String mode = StringUtils.hasText(definition.preferredMode()) ? definition.preferredMode() : "simplified";
        String tools = (definition.toolPacks() == null || definition.toolPacks().isEmpty())
                ? "-"
                : String.join("/", definition.toolPacks());
        return "- %s (%s, source=%s, mode=%s, tools=%s): %s".formatted(
                definition.name(),
                definition.skillId(),
                definition.sourceType(),
                mode,
                tools,
                definition.description()
        );
    }

    private List<String> buildGenericSkillLines(Set<String> allowedToolPacks, Set<String> coveredToolPacks) {
        List<String> lines = new ArrayList<>();
        if (allowedToolPacks.contains("system") && !coveredToolPacks.contains("system")) {
            lines.add("- 系统基础能力 (system): 获取时间、JVM 信息、运行环境与受控命令结果");
        }
        if (allowedToolPacks.contains("web") && !coveredToolPacks.contains("web")) {
            lines.add("- 联网检索能力 (web): 搜索官网、公开网页与外部资料摘要；深度网页抓取改走 Python web skill");
        }
        if (allowedToolPacks.contains("weather") && !coveredToolPacks.contains("weather")) {
            lines.add("- 天气能力 (weather): 查询城市天气");
        }
        if (allowedToolPacks.contains("exchange") && !coveredToolPacks.contains("exchange")) {
            lines.add("- 汇率能力 (exchange): 查询币种汇率");
        }
        if (allowedToolPacks.contains("news") && !coveredToolPacks.contains("news")) {
            lines.add("- 新闻能力 (news): 按关键词检索新闻摘要");
        }
        if (allowedToolPacks.contains("file") && !coveredToolPacks.contains("file")) {
            lines.add("- 文件能力 (file): 在授权目录内读取、搜索和列举本地文件；写入仍限制在项目受控目录");
        }
        if (allowedToolPacks.contains("workspace") && !coveredToolPacks.contains("workspace")) {
            lines.add("- 项目检索能力 (workspace): 不知道路径时按文件名或关键词检索项目内容");
        }
        if (allowedToolPacks.contains("script") && !coveredToolPacks.contains("script")) {
            lines.add("- 脚本能力 (script): 调用受控 Python 技能完成项目分析、运行诊断和网页抓取");
        }
        return lines;
    }

    private List<String> buildGenericCoreLines(Set<String> allowedToolPacks, Set<String> coveredToolPacks) {
        List<String> lines = new ArrayList<>();
        if (allowedToolPacks.contains("system") && !coveredToolPacks.contains("system")) {
            lines.add("- Runtime & System：查看当前时间、JVM 信息、运行环境和受控命令结果");
        }
        if (allowedToolPacks.contains("web") && !coveredToolPacks.contains("web")) {
            lines.add("- Web Research：联网搜索官网、公开网页和资料摘要；网页正文抓取交给 Python web skill");
        }
        if (allowedToolPacks.contains("workspace") && !coveredToolPacks.contains("workspace")) {
            lines.add("- Workspace Explorer：不知道路径时检索项目文件、实现位置和配置证据");
        }
        if (allowedToolPacks.contains("file") && !coveredToolPacks.contains("file")) {
            lines.add("- File Operator：在授权目录内读取、搜索和列举本地文件；写入仍限制在项目受控目录");
        }
        if (allowedToolPacks.contains("script") && !coveredToolPacks.contains("script")) {
            lines.add("- External Skills：调用受控 Python 技能做项目分析、运行诊断和网页抓取");
        }
        return lines;
    }

    private Set<String> collectCoveredToolPacks(List<SkillDefinition> definitions) {
        return definitions.stream()
                .flatMap(definition -> definition.toolPacks() == null ? java.util.stream.Stream.empty() : definition.toolPacks().stream())
                .filter(StringUtils::hasText)
                .map(toolPack -> toolPack.trim().toLowerCase())
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    private Set<String> collectCoveredToolPacks(Set<String> allowedToolPacks) {
        return collectCoveredToolPacks(skillRegistryService.listAgentVisibleDefinitions(allowedToolPacks));
    }

    private String joinSections(String firstTitle,
                                List<String> firstLines,
                                String secondTitle,
                                List<String> secondLines) {
        List<String> sections = new ArrayList<>();
        if (firstTitle != null && firstLines != null && !firstLines.isEmpty()) {
            sections.add(firstTitle + ":\n" + String.join("\n", firstLines));
        }
        if (secondTitle != null && secondLines != null && !secondLines.isEmpty()) {
            sections.add(secondTitle + ":\n" + String.join("\n", secondLines));
        }
        return sections.isEmpty() ? "（暂无可用技能）" : String.join("\n", sections);
    }

    private List<SkillDescriptor> loadDescriptors() {
        if (!dbEnabled || isDbTemporarilyUnavailable()) {
            return defaultDescriptors();
        }
        try {
            List<SkillDescriptor> list = lambdaQuery()
                    .eq(SkillDescriptor::getEnabled, 1)
                    .orderByAsc(SkillDescriptor::getPriority)
                    .list();
            if (list == null || list.isEmpty()) {
                return defaultDescriptors();
            }
            return mergeBuiltinDefaultsIfMissing(list);
        } catch (Exception ex) {
            markDbTemporarilyUnavailable(ex);
            return defaultDescriptors();
        }
    }

    private Map<String, Boolean> loadPolicyMap(String channel, String userId, Set<String> skillIds) {
        if (!dbEnabled || isDbTemporarilyUnavailable() || skillIds.isEmpty()) {
            return Map.of();
        }
        try {
            QueryWrapper<SkillPolicy> wrapper = new QueryWrapper<>();
            wrapper.in("channel", List.of(channel, "*"));
            wrapper.in("user_id", List.of(userId, "*"));
            wrapper.in("skill_id", skillIds);

            List<SkillPolicy> policies = skillPolicyMapper.selectList(wrapper);
            if (policies == null || policies.isEmpty()) {
                return Map.of();
            }

            Map<String, Integer> scoreMap = new LinkedHashMap<>();
            Map<String, Boolean> result = new LinkedHashMap<>();
            for (SkillPolicy policy : policies) {
                String skillId = TextUtils.safe(policy.getSkillId());
                if (!StringUtils.hasText(skillId)) {
                    continue;
                }
                int score = 0;
                if (channel.equalsIgnoreCase(TextUtils.safe(policy.getChannel()))) {
                    score += 2;
                }
                if (userId.equalsIgnoreCase(TextUtils.safe(policy.getUserId()))) {
                    score += 1;
                }
                Integer oldScore = scoreMap.get(skillId);
                if (oldScore == null || score >= oldScore) {
                    scoreMap.put(skillId, score);
                    result.put(skillId, policy.getAllow() == null || policy.getAllow() == 1);
                }
            }
            return result;
        } catch (Exception ex) {
            markDbTemporarilyUnavailable(ex);
            return Map.of();
        }
    }

    private List<SkillDescriptor> defaultDescriptors() {
        List<SkillDescriptor> list = new ArrayList<>();
        if (defaultSystemEnabled) {
            list.add(buildDefault("system-basic", "系统基础技能", "获取系统时间、UUID、JVM 信息", "system", 10));
        }
        if (defaultFileEnabled) {
            list.add(buildDefault("file-basic", "文件技能", "在授权目录内读取/搜索/列举文件，写入仍限制在项目受控目录", "file", 20));
            list.add(buildDefault("workspace-search", "项目检索技能", "不知道路径时可按文件名或关键词检索项目内容", "workspace", 25));
        }
        list.add(buildDefault("web-basic", "联网检索技能", "检索公开网页摘要；网页正文抓取交给 Python web skill", "web", 30));
        if (defaultBusinessEnabled) {
            list.add(buildDefault("weather-basic", "天气技能", "查询城市天气", "weather", 40));
            list.add(buildDefault("exchange-basic", "汇率技能", "查询币种汇率", "exchange", 41));
            list.add(buildDefault("news-basic", "新闻技能", "按关键词检索新闻摘要", "news", 42));
            list.add(buildDefault("script-basic", "脚本技能", "执行 repo_inspector、runtime_probe 等受控 Python 技能", "script", 43));
        }
        return list;
    }

    private List<SkillDescriptor> mergeBuiltinDefaultsIfMissing(List<SkillDescriptor> dbList) {
        Map<String, SkillDescriptor> merged = new LinkedHashMap<>();
        for (SkillDescriptor descriptor : dbList) {
            if (descriptor == null || !StringUtils.hasText(descriptor.getSkillId())) {
                continue;
            }
            merged.put(descriptor.getSkillId().trim(), descriptor);
        }
        for (SkillDescriptor builtin : defaultDescriptors()) {
            if (builtin == null || !StringUtils.hasText(builtin.getSkillId())) {
                continue;
            }
            merged.putIfAbsent(builtin.getSkillId(), builtin);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt(this::priorityOrDefault))
                .toList();
    }

    private SkillDescriptor buildDefault(String skillId,
                                         String name,
                                         String description,
                                         String toolPack,
                                         int priority) {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setSkillId(skillId);
        descriptor.setName(name);
        descriptor.setDescription(description);
        descriptor.setToolPack(toolPack);
        descriptor.setEnabled(1);
        descriptor.setPriority(priority);
        return descriptor;
    }

    private boolean isDbTemporarilyUnavailable() {
        return System.currentTimeMillis() < dbRetryAt;
    }

    private void markDbTemporarilyUnavailable(Exception ex) {
        dbRetryAt = System.currentTimeMillis() + DB_RETRY_INTERVAL_MS;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("Skill DB 不可用，{}ms 内走默认技能。reason={}", DB_RETRY_INTERVAL_MS, reason);
    }

    private int priorityOrDefault(SkillDescriptor descriptor) {
        return descriptor.getPriority() == null ? 9999 : descriptor.getPriority();
    }
}
