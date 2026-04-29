package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {
}
