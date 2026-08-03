package com.storyforge.story;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("story_project")
public class StoryProject {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String genre;
    private String audience;
    private String keywords;
    private String contentMode;
    private Integer targetChapterCount;
    private Integer targetTotalWords;
    private Integer chapterTargetWords;
    private String viewpoint;
    private String styleProfile;
    private String status;
    private String selectedTopic;
    private String generatedTopics;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getContentMode() { return contentMode; }
    public void setContentMode(String contentMode) { this.contentMode = contentMode; }
    public Integer getTargetChapterCount() { return targetChapterCount; }
    public void setTargetChapterCount(Integer targetChapterCount) { this.targetChapterCount = targetChapterCount; }
    public Integer getTargetTotalWords() { return targetTotalWords; }
    public void setTargetTotalWords(Integer targetTotalWords) { this.targetTotalWords = targetTotalWords; }
    public Integer getChapterTargetWords() { return chapterTargetWords; }
    public void setChapterTargetWords(Integer chapterTargetWords) { this.chapterTargetWords = chapterTargetWords; }
    public String getViewpoint() { return viewpoint; }
    public void setViewpoint(String viewpoint) { this.viewpoint = viewpoint; }
    public String getStyleProfile() { return styleProfile; }
    public void setStyleProfile(String styleProfile) { this.styleProfile = styleProfile; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSelectedTopic() {
        return selectedTopic;
    }

    public void setSelectedTopic(String selectedTopic) {
        this.selectedTopic = selectedTopic;
    }

    public String getGeneratedTopics() {
        return generatedTopics;
    }

    public void setGeneratedTopics(String generatedTopics) {
        this.generatedTopics = generatedTopics;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime updatedTime) {
        this.updatedTime = updatedTime;
    }
}
