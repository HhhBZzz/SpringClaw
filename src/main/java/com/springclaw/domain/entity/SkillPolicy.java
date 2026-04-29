package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 权限策略。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_policy")
public class SkillPolicy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 生效渠道，支持 * 通配。
     */
    private String channel;

    /**
     * 生效用户，支持 * 通配。
     */
    private String userId;

    /**
     * 作用的 skillId。
     */
    private String skillId;

    /**
     * 是否允许：1 允许，0 拒绝。
     */
    private Integer allow;
}
