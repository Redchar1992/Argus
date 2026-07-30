package com.storyforge.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.chapter.entity.AiTaskEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiTaskEventMapper extends BaseMapper<AiTaskEvent> {
    @Select("SELECT COALESCE(MAX(sequence_no),0) FROM ai_task_event WHERE task_id=#{taskId}")
    long selectMaxSequence(Long taskId);
    @Select("SELECT * FROM ai_task_event WHERE task_id=#{taskId} AND sequence_no=#{sequence}")
    AiTaskEvent selectByTaskAndSequence(@Param("taskId") Long taskId, @Param("sequence") Long sequence);
}
