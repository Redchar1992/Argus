package com.storyforge.chapter.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("story_chapter")
public class StoryChapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long storyId;
    private Integer chapterNo;
    private String title;
    private Long currentVersionId;
    private String status;
    private String planStatus;
    private String planJson;
    private Integer wordCount;
    private Long rowVersion;
    private LocalDateTime approvedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getStoryId(){return storyId;} public void setStoryId(Long v){storyId=v;}
    public Integer getChapterNo(){return chapterNo;} public void setChapterNo(Integer v){chapterNo=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public Long getCurrentVersionId(){return currentVersionId;} public void setCurrentVersionId(Long v){currentVersionId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getPlanStatus(){return planStatus;} public void setPlanStatus(String v){planStatus=v;}
    public String getPlanJson(){return planJson;} public void setPlanJson(String v){planJson=v;}
    public Integer getWordCount(){return wordCount;} public void setWordCount(Integer v){wordCount=v;}
    public Long getRowVersion(){return rowVersion;} public void setRowVersion(Long v){rowVersion=v;}
    public LocalDateTime getApprovedTime(){return approvedTime;} public void setApprovedTime(LocalDateTime v){approvedTime=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
    public LocalDateTime getUpdatedTime(){return updatedTime;} public void setUpdatedTime(LocalDateTime v){updatedTime=v;}
}
