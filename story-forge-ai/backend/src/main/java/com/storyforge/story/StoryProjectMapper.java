package com.storyforge.story;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StoryProjectMapper extends BaseMapper<StoryProject> {

    @Select("SELECT * FROM story_project WHERE id = #{id} FOR UPDATE")
    StoryProject selectByIdForUpdate(Long id);
}
