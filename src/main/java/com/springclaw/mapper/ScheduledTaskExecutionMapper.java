package com.springclaw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springclaw.domain.entity.ScheduledTaskExecution;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledTaskExecutionMapper extends BaseMapper<ScheduledTaskExecution> {
}
