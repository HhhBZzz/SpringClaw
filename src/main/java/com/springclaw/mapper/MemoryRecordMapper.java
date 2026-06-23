package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.MemoryRecordEntity;

/**
 * memory_record Mapper。CAS / active-slot 语义由 store 层通过显式 SQL 控制。
 */
public interface MemoryRecordMapper extends BaseMapper<MemoryRecordEntity> {
}
