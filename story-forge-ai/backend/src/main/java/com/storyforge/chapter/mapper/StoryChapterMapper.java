package com.storyforge.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.chapter.entity.StoryChapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StoryChapterMapper extends BaseMapper<StoryChapter> {
    @Select("SELECT * FROM story_chapter WHERE id = #{id} FOR UPDATE")
    StoryChapter selectByIdForUpdate(Long id);

    @Select("SELECT * FROM story_chapter WHERE story_id = #{storyId} AND chapter_no = #{chapterNo} FOR UPDATE")
    StoryChapter selectByStoryAndNoForUpdate(@Param("storyId") Long storyId, @Param("chapterNo") Integer chapterNo);

    @Update("""
        UPDATE story_chapter SET current_version_id=#{versionId}, status=#{status},
          word_count=#{wordCount}, row_version=row_version+1, updated_time=CURRENT_TIMESTAMP
        WHERE id=#{chapterId} AND row_version=#{expectedRowVersion}
        """)
    int advanceVersion(@Param("chapterId") Long chapterId, @Param("expectedRowVersion") Long expectedRowVersion,
                       @Param("versionId") Long versionId, @Param("status") String status,
                       @Param("wordCount") Integer wordCount);
}
