package com.storyforge.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.chapter.entity.StoryChapterVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StoryChapterVersionMapper extends BaseMapper<StoryChapterVersion> {
    @Select("SELECT COALESCE(MAX(version_no),0) FROM story_chapter_version WHERE chapter_id=#{chapterId}")
    int selectMaxVersionNo(Long chapterId);
    @Select("SELECT * FROM story_chapter_version WHERE chapter_id=#{chapterId} AND idempotency_key=#{key}")
    StoryChapterVersion selectByIdempotencyKey(@Param("chapterId") Long chapterId, @Param("key") String key);
    @Update("""
            UPDATE story_chapter_version SET review_json=#{reviewJson}
            WHERE id=#{id} AND ai_task_id=#{taskId} AND content_hash=#{contentHash} AND review_json IS NULL
            """)
    int attachReviewIfAbsent(@Param("id") Long id, @Param("taskId") Long taskId,
            @Param("contentHash") String contentHash, @Param("reviewJson") String reviewJson);
}
