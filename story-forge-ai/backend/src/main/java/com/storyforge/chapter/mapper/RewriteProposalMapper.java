package com.storyforge.chapter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.chapter.entity.RewriteProposal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RewriteProposalMapper extends BaseMapper<RewriteProposal> {
    @Select("SELECT * FROM story_rewrite_proposal WHERE id=#{id} FOR UPDATE")
    RewriteProposal selectByIdForUpdate(Long id);
}
