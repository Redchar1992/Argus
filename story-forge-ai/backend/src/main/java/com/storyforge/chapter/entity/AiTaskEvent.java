package com.storyforge.chapter.entity;

import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ai_task_event")
public class AiTaskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String redisEventId;
    private String eventType;
    private Long sequenceNo;
    private String status;
    private String currentNode;
    private Integer progress;
    private String dataJson;
    private LocalDateTime createdTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;}
    public String getRedisEventId(){return redisEventId;} public void setRedisEventId(String v){redisEventId=v;}
    public String getEventType(){return eventType;} public void setEventType(String v){eventType=v;}
    public Long getSequenceNo(){return sequenceNo;} public void setSequenceNo(Long v){sequenceNo=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getCurrentNode(){return currentNode;} public void setCurrentNode(String v){currentNode=v;}
    public Integer getProgress(){return progress;} public void setProgress(Integer v){progress=v;}
    public String getDataJson(){return dataJson;} public void setDataJson(String v){dataJson=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
}
