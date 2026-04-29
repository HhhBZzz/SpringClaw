package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色定义实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("role_definition")
public class RoleDefinition extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 角色编码，如 ADMIN/USER/GUEST。
     */
    private String roleCode;

    /**
     * 角色名称。
     */
    private String roleName;

    /**
     * 角色描述。
     */
    private String description;

    /**
     * 是否启用：1 启用，0 禁用。
     */
    private Integer enabled;
}

