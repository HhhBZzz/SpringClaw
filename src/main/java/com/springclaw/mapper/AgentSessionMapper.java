package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.AgentSession;

/**
 * 会话 Mapper。
 *
 * 设计说明：
 * 1. 继承 BaseMapper，减少样板 CRUD。
 * 2. 保持 DAO 层纯数据访问职责，不承载业务逻辑。
 */
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
