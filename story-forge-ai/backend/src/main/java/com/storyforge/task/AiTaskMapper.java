package com.storyforge.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiTaskMapper extends BaseMapper<AiTask> {

    @Select("SELECT * FROM ai_task WHERE id = #{id} FOR UPDATE")
    AiTask selectByIdForUpdate(Long id);

    @Select("""
            SELECT id
            FROM ai_task
            WHERE story_id = #{storyId}
              AND task_type IN ('STORY_WORKFLOW', 'WORKFLOW_RESUME')
            ORDER BY created_time DESC, id DESC
            LIMIT 1
            """)
    Long selectLatestWorkflowTaskId(Long storyId);
}
