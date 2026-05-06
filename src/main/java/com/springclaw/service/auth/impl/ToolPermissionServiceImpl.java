package com.springclaw.service.auth.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.ToolPermission;
import com.springclaw.mapper.ToolPermissionMapper;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.auth.ToolPermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具权限服务实现。
 *
 * 设计说明：
 * 1. 支持“角色 + 工具名”策略校验，规则可落库维护。
 * 2. DB 不可用时按内置默认策略兜底，保证主链路可用。
 */
@Service
public class ToolPermissionServiceImpl implements ToolPermissionService {

    private final ToolPermissionMapper toolPermissionMapper;
    private final AuthService authService;
    private final boolean permissionEnabled;
    private final boolean dbEnabled;
    private final boolean defaultDeny;
    private final Set<String> userDenyTools;
    private final Set<String> guestAllowTools;

    public ToolPermissionServiceImpl(ToolPermissionMapper toolPermissionMapper,
                                     AuthService authService,
                                     @Value("${springclaw.auth.tool-permission-enabled:true}") boolean permissionEnabled,
                                     @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                     @Value("${springclaw.auth.default-deny:false}") boolean defaultDeny,
                                     @Value("${springclaw.auth.user-deny-tools:SystemToolPack.runCommand}") String userDenyTools,
                                     @Value("${springclaw.auth.guest-allow-tools:SystemToolPack.now,SystemToolPack.uuid}") String guestAllowTools) {
        this.toolPermissionMapper = toolPermissionMapper;
        this.authService = authService;
        this.permissionEnabled = permissionEnabled;
        this.dbEnabled = dbEnabled;
        this.defaultDeny = defaultDeny;
        this.userDenyTools = parseToolSet(userDenyTools);
        this.guestAllowTools = parseToolSet(guestAllowTools);
    }

    @Override
    public void checkPermission(String userId, String toolName) {
        if (!permissionEnabled || !StringUtils.hasText(toolName)) {
            return;
        }

        String roleCode = authService.resolveRoleByUserId(userId);
        if ("ADMIN".equalsIgnoreCase(roleCode)) {
            return;
        }

        Boolean decision = findDbDecision(roleCode, toolName);
        if (decision == null) {
            decision = defaultDecision(roleCode, toolName);
        }
        if (Boolean.FALSE.equals(decision)) {
            throw new BusinessException(40311, "当前角色无权限调用工具: " + toolName + " (role=" + roleCode + ")");
        }
    }

    private Boolean findDbDecision(String roleCode, String toolName) {
        if (!dbEnabled) {
            return null;
        }
        try {
            QueryWrapper<ToolPermission> wrapper = new QueryWrapper<>();
            wrapper.eq("enabled", 1);
            wrapper.in("role_code", List.of(roleCode, "*"));
            List<ToolPermission> rules = toolPermissionMapper.selectList(wrapper);
            if (rules == null || rules.isEmpty()) {
                return null;
            }

            return rules.stream()
                    .filter(rule -> matches(rule.getToolName(), toolName))
                    .max(Comparator.comparingInt(rule -> score(rule, roleCode, toolName)))
                    .map(rule -> rule.getAllow() == null || rule.getAllow() == 1)
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private Boolean defaultDecision(String roleCode, String toolName) {
        String role = normalizeRole(roleCode);
        if ("GUEST".equals(role)) {
            return matchesAny(guestAllowTools, toolName);
        }
        if ("USER".equals(role)) {
            return !matchesAny(userDenyTools, toolName);
        }
        return !defaultDeny;
    }

    private int score(ToolPermission rule, String roleCode, String toolName) {
        int score = 0;
        String role = normalizeRole(rule.getRoleCode());
        String pattern = normalizeTool(rule.getToolName());
        if (role.equals(normalizeRole(roleCode))) {
            score += 20;
        }
        if (pattern.equals(normalizeTool(toolName))) {
            score += 10;
        } else if ("*".equals(pattern) || pattern.endsWith("*")) {
            score += 5;
        }
        if (rule.getPriority() != null) {
            score += rule.getPriority();
        }
        return score;
    }

    private boolean matches(String pattern, String toolName) {
        String normalizedPattern = normalizeTool(pattern);
        String normalizedTool = normalizeTool(toolName);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        if (normalizedPattern.endsWith("*")) {
            String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
            return normalizedTool.startsWith(prefix);
        }
        return normalizedPattern.equals(normalizedTool);
    }

    private boolean matchesAny(Set<String> patterns, String toolName) {
        return patterns.stream().anyMatch(pattern -> matches(pattern, toolName));
    }

    private Set<String> parseToolSet(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        return Arrays.stream(text.split(","))
                .map(this::normalizeTool)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private String normalizeRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return "USER";
        }
        return roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeTool(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return "";
        }
        return toolName.trim();
    }
}
