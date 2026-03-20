package com.openclaw.service.skill.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.openclaw.domain.entity.SkillDescriptor;
import com.openclaw.domain.entity.SkillPolicy;
import com.openclaw.mapper.SkillDescriptorMapper;
import com.openclaw.mapper.SkillPolicyMapper;
import com.openclaw.service.skill.SkillService;
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
    private final boolean skillEnabled;
    private final boolean dbEnabled;
    private final boolean defaultSystemEnabled;
    private final boolean defaultFileEnabled;
    private final boolean defaultBusinessEnabled;
    private volatile long dbRetryAt = 0L;

    public SkillServiceImpl(SkillPolicyMapper skillPolicyMapper,
                            @Value("${openclaw.skill.enabled:true}") boolean skillEnabled,
                            @Value("${openclaw.persistence.db-enabled:false}") boolean dbEnabled,
                            @Value("${openclaw.skill.default-system-enabled:true}") boolean defaultSystemEnabled,
                            @Value("${openclaw.skill.default-file-enabled:true}") boolean defaultFileEnabled,
                            @Value("${openclaw.skill.default-business-enabled:true}") boolean defaultBusinessEnabled) {
        this.skillPolicyMapper = skillPolicyMapper;
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

        String channelKey = normalize(channel);
        String userKey = normalize(userId);
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
            String skillId = safe(descriptor.getSkillId());
            if (!StringUtils.hasText(skillId)) {
                continue;
            }
            boolean enabled = descriptor.getEnabled() == null || descriptor.getEnabled() == 1;
            boolean allowed = policyMap.getOrDefault(skillId, true);
            if (enabled && allowed) {
                String toolPack = safe(descriptor.getToolPack()).toLowerCase();
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

        String channelKey = normalize(channel);
        String userKey = normalize(userId);
        List<SkillDescriptor> descriptors = loadDescriptors();
        if (descriptors.isEmpty()) {
            return "（暂无可用技能）";
        }

        Set<String> skillIds = descriptors.stream()
                .map(SkillDescriptor::getSkillId)
                .filter(StringUtils::hasText)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Map<String, Boolean> policyMap = loadPolicyMap(channelKey, userKey, skillIds);

        List<String> lines = new ArrayList<>();
        for (SkillDescriptor descriptor : descriptors) {
            String skillId = safe(descriptor.getSkillId());
            boolean enabled = descriptor.getEnabled() == null || descriptor.getEnabled() == 1;
            boolean allowed = policyMap.getOrDefault(skillId, true);
            if (!enabled || !allowed || !StringUtils.hasText(skillId)) {
                continue;
            }
            String name = safe(descriptor.getName());
            String desc = safe(descriptor.getDescription());
            String toolPack = safe(descriptor.getToolPack());
            lines.add("- " + name + " (" + skillId + ", tool=" + toolPack + "): " + desc);
        }
        if (lines.isEmpty()) {
            return "（暂无可用技能）";
        }
        return String.join("\n", lines);
    }

    @Override
    public String describeCoreSkills(String channel, String userId) {
        if (!skillEnabled) {
            return "（核心技能未开启）";
        }
        Set<String> allowed = resolveAllowedToolPacks(channel, userId);
        List<String> lines = new ArrayList<>();
        if (allowed.contains("workspace")) {
            lines.add("- Workspace Explorer：不知道路径时检索项目文件、实现位置和配置证据");
        }
        if (allowed.contains("file")) {
            lines.add("- File Operator：在明确路径下读取、写入和列举文件");
        }
        if (allowed.contains("web")) {
            lines.add("- Web Research：联网检索官网、公开网页和资料摘要");
        }
        if (allowed.contains("system")) {
            lines.add("- Runtime & System：查看当前时间、JVM 信息、运行环境和受控命令结果");
        }
        if (allowed.contains("script")) {
            lines.add("- External Skills：调用受控脚本技能做项目分析和运行诊断");
        }
        if (lines.isEmpty()) {
            return "（暂无核心技能）";
        }
        return String.join("\n", lines);
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
                String skillId = safe(policy.getSkillId());
                if (!StringUtils.hasText(skillId)) {
                    continue;
                }
                int score = 0;
                if (channel.equalsIgnoreCase(safe(policy.getChannel()))) {
                    score += 2;
                }
                if (userId.equalsIgnoreCase(safe(policy.getUserId()))) {
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
            list.add(buildDefault("file-basic", "文件技能", "在受控目录内读取/写入/列举文件", "file", 20));
            list.add(buildDefault("workspace-search", "项目检索技能", "不知道路径时可按文件名或关键词检索项目内容", "workspace", 25));
        }
        list.add(buildDefault("web-basic", "联网检索技能", "检索公开网页并抓取文本摘要", "web", 30));
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

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "*";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private int priorityOrDefault(SkillDescriptor descriptor) {
        return descriptor.getPriority() == null ? 9999 : descriptor.getPriority();
    }
}
