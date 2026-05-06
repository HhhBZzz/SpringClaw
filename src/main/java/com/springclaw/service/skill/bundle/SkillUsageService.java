package com.springclaw.service.skill.bundle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Skill usage sidecar，参考 Hermes 的 tools/skill_usage.py。
 *
 * 设计要点：
 * 1. 使用记录写入 skills/.usage.json，不污染 SKILL.md。
 * 2. 计数失败不影响真实 skill 查看或执行。
 * 3. 后续 curator 可以基于 use/view/patch 事实做清理和合并。
 */
@Service
public class SkillUsageService {

    private static final TypeReference<Map<String, Map<String, Object>>> USAGE_TYPE = new TypeReference<>() {
    };

    private final boolean enabled;
    private final SkillCatalogService skillCatalogService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkillUsageService(@Value("${springclaw.skills.usage.enabled:true}") boolean enabled,
                             SkillCatalogService skillCatalogService,
                             ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.skillCatalogService = skillCatalogService;
        this.objectMapper = objectMapper;
    }

    public Path usagePath() {
        return skillCatalogService.rootPath().resolve(".usage.json").toAbsolutePath().normalize();
    }

    public void recordView(String skillId) {
        mutate(skillId, record -> {
            record.put("view_count", intValue(record.get("view_count")) + 1);
            record.put("last_viewed_at", nowIso());
        });
    }

    public void recordUse(String skillId) {
        mutate(skillId, record -> {
            record.put("use_count", intValue(record.get("use_count")) + 1);
            record.put("last_used_at", nowIso());
        });
    }

    public void recordPatch(String skillId) {
        mutate(skillId, record -> {
            record.put("patch_count", intValue(record.get("patch_count")) + 1);
            record.put("last_patched_at", nowIso());
        });
    }

    public List<SkillUsageRecord> listUsage(List<SkillBundleDefinition> bundles) {
        Map<String, Map<String, Object>> data = readUsage();
        List<SkillUsageRecord> rows = new ArrayList<>();
        for (SkillBundleDefinition bundle : bundles == null ? List.<SkillBundleDefinition>of() : bundles) {
            rows.add(toRecord(bundle.skillId(), normalizeRecord(data.get(bundle.skillId()))));
        }
        return rows.stream()
                .sorted(Comparator.comparingInt(SkillUsageRecord::useCount).reversed()
                        .thenComparing(Comparator.comparingInt(SkillUsageRecord::viewCount).reversed())
                        .thenComparing(SkillUsageRecord::skillId))
                .toList();
    }

    private void mutate(String skillId, Consumer<Map<String, Object>> mutator) {
        if (!enabled || !StringUtils.hasText(skillId)) {
            return;
        }
        try {
            Map<String, Map<String, Object>> data = readUsage();
            Map<String, Object> record = normalizeRecord(data.get(skillId));
            mutator.accept(record);
            data.put(skillId.trim(), record);
            writeUsage(data);
        } catch (Exception ignored) {
            // 使用统计是辅助能力，不能影响 skill 主流程。
        }
    }

    private Map<String, Map<String, Object>> readUsage() {
        Path path = usagePath();
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Object>> data = objectMapper.readValue(path.toFile(), USAGE_TYPE);
            return data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void writeUsage(Map<String, Map<String, Object>> data) throws IOException {
        Path path = usagePath();
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), ".usage-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Map<String, Object> normalizeRecord(Map<String, Object> existing) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("created_by", null);
        record.put("use_count", 0);
        record.put("view_count", 0);
        record.put("patch_count", 0);
        record.put("last_used_at", null);
        record.put("last_viewed_at", null);
        record.put("last_patched_at", null);
        record.put("created_at", nowIso());
        record.put("state", "active");
        record.put("pinned", false);
        if (existing != null) {
            record.putAll(existing);
        }
        backfillLegacyCamelCase(record, "useCount", "use_count");
        backfillLegacyCamelCase(record, "viewCount", "view_count");
        backfillLegacyCamelCase(record, "patchCount", "patch_count");
        backfillLegacyCamelCase(record, "lastUsedAt", "last_used_at");
        backfillLegacyCamelCase(record, "lastViewedAt", "last_viewed_at");
        backfillLegacyCamelCase(record, "lastPatchedAt", "last_patched_at");
        backfillLegacyCamelCase(record, "createdAt", "created_at");
        backfillLegacyCamelCase(record, "createdBy", "created_by");
        return record;
    }

    private SkillUsageRecord toRecord(String skillId, Map<String, Object> record) {
        return new SkillUsageRecord(
                skillId,
                intValue(record.get("use_count")),
                intValue(record.get("view_count")),
                intValue(record.get("patch_count")),
                stringValue(record.get("last_used_at")),
                stringValue(record.get("last_viewed_at")),
                stringValue(record.get("last_patched_at")),
                stringValue(record.get("created_at")),
                stringValue(record.get("state"), "active"),
                booleanValue(record.get("pinned")),
                stringValue(record.get("created_by"))
        );
    }

    private void backfillLegacyCamelCase(Map<String, Object> record, String oldKey, String newKey) {
        if (record.containsKey(oldKey) && isDefaultValue(record.get(newKey))) {
            record.put(newKey, record.get(oldKey));
        }
        record.remove(oldKey);
    }

    private boolean isDefaultValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Number number) {
            return number.intValue() == 0;
        }
        return !StringUtils.hasText(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return stringValue(value, "");
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String nowIso() {
        return Instant.now().toString();
    }
}
