package com.openclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户账号实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_account")
public class UserAccount extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户名（唯一）。
     */
    private String username;

    /**
     * 密码摘要（含盐）。
     */
    private String passwordHash;

    /**
     * 角色编码，如 ADMIN/USER/GUEST。
     */
    private String roleCode;

    /**
     * 状态：ACTIVE / DISABLED。
     */
    private String status;
}

