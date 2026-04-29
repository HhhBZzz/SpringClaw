package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工具权限策略实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tool_permission")
public class ToolPermission extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 角色编码，可使用 * 表示通配。
     */
    private String roleCode;

    /**
     * 工具名，支持精确匹配或前缀通配（例如 SystemToolPack.*）。
     */
    private String toolName;

    /**
     * 是否允许：1 允许，0 拒绝。
     */
    private Integer allow;

    /**
     * 优先级（值越大优先级越高）。
     */
    private Integer priority;

    /**
     * 是否启用：1 启用，0 禁用。
     */
    private Integer enabled;
}

