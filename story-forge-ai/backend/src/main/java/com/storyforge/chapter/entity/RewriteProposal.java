package com.storyforge.chapter.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("story_rewrite_proposal")
public class RewriteProposal {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Long baseVersionId;
    private Long aiTaskId;
    private String idempotencyKey;
    private Integer generationNo;
    private Integer startOffset;
    private Integer endOffset;
    private String selectedText;
    private String selectedTextHash;
    private String actionType;
    private String customInstruction;
    private String replacementText;
    private String replacementHash;
    private String reason;
    private String status;
    private Long resolvedVersionId;
    private Long createdBy;
    private LocalDateTime createdTime;
    private LocalDateTime resolvedTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getChapterId(){return chapterId;} public void setChapterId(Long v){chapterId=v;}
    public Long getBaseVersionId(){return baseVersionId;} public void setBaseVersionId(Long v){baseVersionId=v;}
    public Long getAiTaskId(){return aiTaskId;} public void setAiTaskId(Long v){aiTaskId=v;}
    public String getIdempotencyKey(){return idempotencyKey;} public void setIdempotencyKey(String v){idempotencyKey=v;}
    public Integer getGenerationNo(){return generationNo;} public void setGenerationNo(Integer v){generationNo=v;}
    public Integer getStartOffset(){return startOffset;} public void setStartOffset(Integer v){startOffset=v;}
    public Integer getEndOffset(){return endOffset;} public void setEndOffset(Integer v){endOffset=v;}
    public String getSelectedText(){return selectedText;} public void setSelectedText(String v){selectedText=v;}
    public String getSelectedTextHash(){return selectedTextHash;} public void setSelectedTextHash(String v){selectedTextHash=v;}
    public String getActionType(){return actionType;} public void setActionType(String v){actionType=v;}
    public String getCustomInstruction(){return customInstruction;} public void setCustomInstruction(String v){customInstruction=v;}
    public String getReplacementText(){return replacementText;} public void setReplacementText(String v){replacementText=v;}
    public String getReplacementHash(){return replacementHash;} public void setReplacementHash(String v){replacementHash=v;}
    public String getReason(){return reason;} public void setReason(String v){reason=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Long getResolvedVersionId(){return resolvedVersionId;} public void setResolvedVersionId(Long v){resolvedVersionId=v;}
    public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
    public LocalDateTime getResolvedTime(){return resolvedTime;} public void setResolvedTime(LocalDateTime v){resolvedTime=v;}
}
