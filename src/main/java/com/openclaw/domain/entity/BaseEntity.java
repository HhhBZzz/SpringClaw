package com.openclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类。
 *
 * 设计说明：
 * 1. 所有业务实体都应继承此基类，统一管理审计字段。
 * 2. 使用 MyBatis-Plus 的 FieldFill 自动填充，无需手工赋值。
 * 3. 使用 Lombok @Data 自动生成 Getter/Setter/toString 等。
 */
@Data
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 创建时间，插入时自动填充。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间，插入和更新时自动填充。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 创建人。
     */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 更新人。
     */
    @TableField(value = "update_by", fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /**
     * 删除标记：0 正常，1 已删除（逻辑删除）。
     */
    @TableField("deleted")
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
