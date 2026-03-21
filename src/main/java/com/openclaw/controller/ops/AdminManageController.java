package com.openclaw.controller.ops;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.common.response.ApiResponse;
import com.openclaw.domain.entity.SkillDescriptor;
import com.openclaw.domain.entity.SkillPolicy;
import com.openclaw.domain.entity.ToolPermission;
import com.openclaw.domain.entity.UserAccount;
import com.openclaw.mapper.SkillDescriptorMapper;
import com.openclaw.mapper.SkillPolicyMapper;
import com.openclaw.mapper.ToolPermissionMapper;
import com.openclaw.mapper.UserAccountMapper;
import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.auth.AuthService;
import com.openclaw.service.event.MessageEventService;
import com.openclaw.service.skill.script.ScriptSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillDefinition;
import com.openclaw.service.usage.LlmUsageRecordService;
import com.openclaw.web.auth.RequireRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 后台管理接口（轻量版）。
 */
@RestController
@RequestMapping("/api/admin/manage")
@RequireRole({"ADMIN"})
public class AdminManageController {

    private final UserAccountMapper userAccountMapper;
    private final ToolPermissionMapper toolPermissionMapper;
    private final SkillDescriptorMapper skillDescriptorMapper;
    private final SkillPolicyMapper skillPolicyMapper;
    private final MessageEventService messageEventService;
    private final AuthService authService;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final AiProviderService aiProviderService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final boolean dbEnabled;

    public AdminManageController(UserAccountMapper userAccountMapper,
                                 ToolPermissionMapper toolPermissionMapper,
                                 SkillDescriptorMapper skillDescriptorMapper,
                                 SkillPolicyMapper skillPolicyMapper,
                                 MessageEventService messageEventService,
                                 AuthService authService,
                                 ScriptSkillCatalogService scriptSkillCatalogService,
                                 AiProviderService aiProviderService,
                                 LlmUsageRecordService llmUsageRecordService,
                                 @Value("${openclaw.persistence.db-enabled:false}") boolean dbEnabled) {
        this.userAccountMapper = userAccountMapper;
        this.toolPermissionMapper = toolPermissionMapper;
        this.skillDescriptorMapper = skillDescriptorMapper;
        this.skillPolicyMapper = skillPolicyMapper;
        this.messageEventService = messageEventService;
        this.authService = authService;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.aiProviderService = aiProviderService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.dbEnabled = dbEnabled;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(buildOverviewPayload());
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> result = buildOverviewPayload();
        result.put("activeSessions", authService.listActiveSessions(20));
        result.put("recentUsers", buildRecentUserActivity());
        result.put("providers", aiProviderService.summary());
        result.put("llmUsage", llmUsageRecordService.summary(300));
        result.put("recentLlmUsage", llmUsageRecordService.listRecent(20));
        return ApiResponse.success(result);
    }

    @GetMapping("/llm-usage/summary")
    public ApiResponse<Map<String, Object>> llmUsageSummary() {
        return ApiResponse.success(llmUsageRecordService.summary(300));
    }

    @GetMapping("/llm-usage/recent")
    public ApiResponse<List<com.openclaw.domain.entity.LlmUsageRecord>> llmUsageRecent() {
        return ApiResponse.success(llmUsageRecordService.listRecent(50));
    }

    @GetMapping("/active-sessions")
    public ApiResponse<List<AuthService.ActiveSession>> activeSessions() {
        return ApiResponse.success(authService.listActiveSessions(100));
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> users() {
        ensureDbEnabled();
        List<UserAccount> users = userAccountMapper.selectList(null);
        users.forEach(u -> u.setPasswordHash("***"));

        Map<String, Long> activeTokenCounts = authService.listActiveSessions(500).stream()
                .collect(Collectors.groupingBy(AuthService.ActiveSession::username, Collectors.counting()));
        Map<String, RecentUserActivity> recentActivity = buildRecentUserActivity().stream()
                .collect(Collectors.toMap(RecentUserActivity::userId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<UserView> payload = users.stream()
                .map(user -> {
                    RecentUserActivity activity = recentActivity.get(user.getUsername());
                    return new UserView(
                            user.getUsername(),
                            user.getRoleCode(),
                            user.getStatus(),
                            user.getCreateTime(),
                            activeTokenCounts.getOrDefault(user.getUsername(), 0L),
                            activity == null ? 0L : activity.recentEvents(),
                            activity == null ? "" : activity.lastSeenAt(),
                            activity == null ? List.of() : activity.channels()
                    );
                })
                .toList();
        return ApiResponse.success(payload);
    }

    private Map<String, Object> buildOverviewPayload() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbEnabled", dbEnabled);
        result.put("auditStats", messageEventService.summaryStats(300));
        result.put("userCount", safeCountUsers());
        result.put("toolPermissionCount", safeCountToolPermissions());
        result.put("skillDescriptorCount", safeCountSkillDescriptors());
        result.put("skillPolicyCount", safeCountSkillPolicies());
        result.put("roleCounts", safeRoleCounts());
        result.put("tokenStats", authService.tokenRuntimeStats());
        result.put("llmUsage", llmUsageRecordService.summary(200));
        return result;
    }

    @PostMapping("/users/create")
    public ApiResponse<Map<String, Object>> createUser(@RequestBody CreateUserRequest request) {
        ensureDbEnabled();
        if (request == null || !StringUtils.hasText(request.username()) || !StringUtils.hasText(request.password())) {
            throw new BusinessException(40098, "username/password 不能为空");
        }
        AuthService.LoginSession session = authService.register(request.username(), request.password());
        if (StringUtils.hasText(request.roleCode())) {
            UserAccount user = userAccountMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserAccount>()
                            .eq("username", request.username().trim().toLowerCase())
                            .last("limit 1")
            );
            if (user != null) {
                user.setRoleCode(request.roleCode().trim().toUpperCase());
                userAccountMapper.updateById(user);
            }
        }
        return ApiResponse.success(Map.of(
                "username", session.username(),
                "roleCode", request.roleCode() == null ? session.roleCode() : request.roleCode().trim().toUpperCase()
        ));
    }

    @PostMapping("/users/role")
    public ApiResponse<Map<String, Object>> updateUserRole(@RequestBody UpdateUserRoleRequest request) {
        ensureDbEnabled();
        if (request == null || !StringUtils.hasText(request.username()) || !StringUtils.hasText(request.roleCode())) {
            throw new BusinessException(40099, "username/roleCode 不能为空");
        }
        UserAccount user = userAccountMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserAccount>()
                        .eq("username", request.username().trim().toLowerCase())
                        .last("limit 1")
        );
        if (user == null) {
            throw new BusinessException(40432, "用户不存在");
        }
        user.setRoleCode(request.roleCode().trim().toUpperCase());
        userAccountMapper.updateById(user);
        return ApiResponse.success(Map.of(
                "username", user.getUsername(),
                "roleCode", user.getRoleCode()
        ));
    }

    @GetMapping("/tool-permissions")
    public ApiResponse<List<ToolPermission>> toolPermissions() {
        ensureDbEnabled();
        return ApiResponse.success(toolPermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ToolPermission>()
                        .orderByDesc("priority")
        ));
    }

    @PostMapping("/tool-permissions/upsert")
    public ApiResponse<Map<String, Object>> upsertToolPermission(@RequestBody UpsertToolPermissionRequest request) {
        ensureDbEnabled();
        if (request == null || !StringUtils.hasText(request.roleCode()) || !StringUtils.hasText(request.toolName())) {
            throw new BusinessException(40100, "roleCode/toolName 不能为空");
        }
        ToolPermission entity = toolPermissionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ToolPermission>()
                        .eq("role_code", request.roleCode().trim().toUpperCase())
                        .eq("tool_name", request.toolName().trim())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new ToolPermission();
            entity.setRoleCode(request.roleCode().trim().toUpperCase());
            entity.setToolName(request.toolName().trim());
        }
        entity.setAllow(request.allow() == null || request.allow() ? 1 : 0);
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        entity.setPriority(request.priority() == null ? 0 : request.priority());
        if (entity.getId() == null) {
            toolPermissionMapper.insert(entity);
        } else {
            toolPermissionMapper.updateById(entity);
        }
        return ApiResponse.success(Map.of(
                "id", entity.getId(),
                "roleCode", entity.getRoleCode(),
                "toolName", entity.getToolName()
        ));
    }

    @GetMapping("/skills")
    public ApiResponse<List<SkillDescriptor>> skills() {
        ensureDbEnabled();
        return ApiResponse.success(skillDescriptorMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SkillDescriptor>()
                        .orderByAsc("priority")
        ));
    }

    @GetMapping("/script-skills")
    public ApiResponse<Map<String, Object>> scriptSkills() {
        return ApiResponse.success(buildScriptSkillPayload(scriptSkillCatalogService.listDefinitions(), "loaded"));
    }

    @GetMapping("/model-providers")
    public ApiResponse<Map<String, Object>> modelProviders() {
        return ApiResponse.success(aiProviderService.summary());
    }

    @PostMapping("/model-providers/switch")
    public ApiResponse<Map<String, Object>> switchModelProvider(@RequestBody SwitchModelProviderRequest request) {
        if (request == null || !StringUtils.hasText(request.providerId())) {
            throw new BusinessException(40103, "providerId 不能为空");
        }
        if (StringUtils.hasText(request.model())) {
            aiProviderService.switchActiveModel(request.providerId(), request.model());
        } else {
            aiProviderService.switchActiveProvider(request.providerId());
        }
        return ApiResponse.success(aiProviderService.summary());
    }

    @PostMapping("/script-skills/reload")
    public ApiResponse<Map<String, Object>> reloadScriptSkills() {
        return ApiResponse.success(buildScriptSkillPayload(scriptSkillCatalogService.reloadDefinitions(), "reloaded"));
    }

    @PostMapping("/skills/upsert")
    public ApiResponse<Map<String, Object>> upsertSkill(@RequestBody UpsertSkillRequest request) {
        ensureDbEnabled();
        if (request == null || !StringUtils.hasText(request.skillId()) || !StringUtils.hasText(request.toolPack())) {
            throw new BusinessException(40101, "skillId/toolPack 不能为空");
        }
        SkillDescriptor entity = skillDescriptorMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SkillDescriptor>()
                        .eq("skill_id", request.skillId().trim())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new SkillDescriptor();
            entity.setSkillId(request.skillId().trim());
        }
        entity.setName(StringUtils.hasText(request.name()) ? request.name().trim() : request.skillId().trim());
        entity.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : "");
        entity.setToolPack(request.toolPack().trim());
        entity.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        entity.setPriority(request.priority() == null ? 100 : request.priority());
        if (entity.getId() == null) {
            skillDescriptorMapper.insert(entity);
        } else {
            skillDescriptorMapper.updateById(entity);
        }
        return ApiResponse.success(Map.of(
                "id", entity.getId(),
                "skillId", entity.getSkillId()
        ));
    }

    @GetMapping("/skill-policies")
    public ApiResponse<List<SkillPolicy>> skillPolicies() {
        ensureDbEnabled();
        return ApiResponse.success(skillPolicyMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SkillPolicy>()
                        .orderByDesc("id")
        ));
    }

    @PostMapping("/skill-policies/upsert")
    public ApiResponse<Map<String, Object>> upsertSkillPolicy(@RequestBody UpsertSkillPolicyRequest request) {
        ensureDbEnabled();
        if (request == null || !StringUtils.hasText(request.channel()) || !StringUtils.hasText(request.userId()) || !StringUtils.hasText(request.skillId())) {
            throw new BusinessException(40102, "channel/userId/skillId 不能为空");
        }
        SkillPolicy entity = skillPolicyMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SkillPolicy>()
                        .eq("channel", request.channel().trim())
                        .eq("user_id", request.userId().trim())
                        .eq("skill_id", request.skillId().trim())
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new SkillPolicy();
            entity.setChannel(request.channel().trim());
            entity.setUserId(request.userId().trim());
            entity.setSkillId(request.skillId().trim());
        }
        entity.setAllow(request.allow() == null || request.allow() ? 1 : 0);
        if (entity.getId() == null) {
            skillPolicyMapper.insert(entity);
        } else {
            skillPolicyMapper.updateById(entity);
        }
        return ApiResponse.success(Map.of(
                "id", entity.getId(),
                "channel", entity.getChannel(),
                "userId", entity.getUserId(),
                "skillId", entity.getSkillId()
        ));
    }

    private void ensureDbEnabled() {
        if (!dbEnabled) {
            throw new BusinessException(40087, "当前 db-enabled=false，管理写接口不可用");
        }
    }

    private long safeCountUsers() {
        if (!dbEnabled) {
            return -1L;
        }
        try {
            return userAccountMapper.selectCount(null);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private long safeCountToolPermissions() {
        if (!dbEnabled) {
            return -1L;
        }
        try {
            return toolPermissionMapper.selectCount(null);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private long safeCountSkillDescriptors() {
        if (!dbEnabled) {
            return -1L;
        }
        try {
            return skillDescriptorMapper.selectCount(null);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private long safeCountSkillPolicies() {
        if (!dbEnabled) {
            return -1L;
        }
        try {
            return skillPolicyMapper.selectCount(null);
        } catch (Exception ex) {
            return -1L;
        }
    }

    private Map<String, Long> safeRoleCounts() {
        if (!dbEnabled) {
            return Map.of();
        }
        try {
            return userAccountMapper.selectList(null).stream()
                    .collect(Collectors.groupingBy(
                            user -> StringUtils.hasText(user.getRoleCode()) ? user.getRoleCode().trim().toUpperCase() : "UNKNOWN",
                            LinkedHashMap::new,
                            Collectors.counting()
                    ));
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<RecentUserActivity> buildRecentUserActivity() {
        return messageEventService.pageQuery(null, null, null, null, 1, 100).getRecords().stream()
                .filter(event -> StringUtils.hasText(event.getUserId()))
                .filter(event -> !"system".equalsIgnoreCase(event.getUserId()))
                .filter(event -> !"anonymous".equalsIgnoreCase(event.getUserId()))
                .collect(Collectors.groupingBy(
                        event -> event.getUserId().trim().toLowerCase(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .entrySet().stream()
                .map(entry -> {
                    List<String> channels = entry.getValue().stream()
                            .map(evt -> StringUtils.hasText(evt.getChannel()) ? evt.getChannel().trim() : "unknown")
                            .distinct()
                            .toList();
                    String lastSeenAt = entry.getValue().stream()
                            .map(evt -> evt.getCreateTime() == null ? "" : evt.getCreateTime().toString())
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElse("");
                    return new RecentUserActivity(
                            entry.getKey(),
                            entry.getValue().size(),
                            lastSeenAt,
                            channels
                    );
                })
                .toList();
    }

    private Map<String, Object> buildScriptSkillPayload(List<ScriptSkillDefinition> definitions, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("enabled", scriptSkillCatalogService.enabled());
        payload.put("rootPath", scriptSkillCatalogService.rootPath().toString());
        payload.put("count", definitions.size());
        payload.put("definitions", definitions.stream().map(this::toScriptSkillView).toList());
        return payload;
    }

    private Map<String, Object> toScriptSkillView(ScriptSkillDefinition definition) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("skillName", definition.skillName());
        item.put("displayName", definition.displayName());
        item.put("category", definition.category());
        item.put("description", definition.description());
        item.put("inputHint", definition.inputHint());
        item.put("keywords", definition.keywords());
        item.put("examples", definition.exampleQuestions());
        item.put("scriptPath", definition.scriptPath().toString());
        return item;
    }

    public record CreateUserRequest(String username, String password, String roleCode) {
    }

    public record UpdateUserRoleRequest(String username, String roleCode) {
    }

    public record UpsertToolPermissionRequest(String roleCode, String toolName, Boolean allow, Integer priority, Boolean enabled) {
    }

    public record UpsertSkillRequest(String skillId, String name, String description, String toolPack, Integer priority, Boolean enabled) {
    }

    public record UpsertSkillPolicyRequest(String channel, String userId, String skillId, Boolean allow) {
    }

    public record SwitchModelProviderRequest(String providerId, String model) {
    }

    public record UserView(String username,
                           String roleCode,
                           String status,
                           Object createTime,
                           long activeTokenCount,
                           long recentEventCount,
                           String lastSeenAt,
                           List<String> channels) {
    }

    public record RecentUserActivity(String userId,
                                     long recentEvents,
                                     String lastSeenAt,
                                     List<String> channels) {
    }
}
