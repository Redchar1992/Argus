package com.storyforge.chapter.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("story_chapter_version")
public class StoryChapterVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Integer versionNo;
    private String sourceType;
    private String content;
    private String contentHash;
    private Long baseVersionId;
    private Long aiTaskId;
    private String idempotencyKey;
    private String promptVersion;
    private String modelName;
    private String reviewJson;
    private String changeSummary;
    private Long createdBy;
    private LocalDateTime createdTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getChapterId(){return chapterId;} public void setChapterId(Long v){chapterId=v;}
    public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
    public String getSourceType(){return sourceType;} public void setSourceType(String v){sourceType=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public String getContentHash(){return contentHash;} public void setContentHash(String v){contentHash=v;}
    public Long getBaseVersionId(){return baseVersionId;} public void setBaseVersionId(Long v){baseVersionId=v;}
    public Long getAiTaskId(){return aiTaskId;} public void setAiTaskId(Long v){aiTaskId=v;}
    public String getIdempotencyKey(){return idempotencyKey;} public void setIdempotencyKey(String v){idempotencyKey=v;}
    public String getPromptVersion(){return promptVersion;} public void setPromptVersion(String v){promptVersion=v;}
    public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;}
    public String getReviewJson(){return reviewJson;} public void setReviewJson(String v){reviewJson=v;}
    public String getChangeSummary(){return changeSummary;} public void setChangeSummary(String v){changeSummary=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
}
