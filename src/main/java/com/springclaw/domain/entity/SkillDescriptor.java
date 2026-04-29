package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 元数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_descriptor")
public class SkillDescriptor extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 技能唯一编码，例如 system-basic/file-basic。
     */
    private String skillId;

    /**
     * 技能名称。
     */
    private String name;

    /**
     * 技能描述。
     */
    private String description;

    /**
     * 关联的工具包标识，例如 system/file。
     */
    private String toolPack;

    /**
     * 是否启用：1 启用，0 禁用。
     */
    private Integer enabled;

    /**
     * 排序优先级（值越小越靠前）。
     */
    private Integer priority;
}
