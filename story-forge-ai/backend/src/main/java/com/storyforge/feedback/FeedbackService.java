package com.storyforge.feedback;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.storyforge.story.StoryService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private final StoryService stories;
    private final JdbcTemplate jdbc;

    public FeedbackService(StoryService stories, JdbcTemplate jdbc) { this.stories = stories; this.jdbc = jdbc; }

    public FeedbackResponse create(Long userId, Long storyId, FeedbackRequest request) {
        stories.requireOwned(userId, storyId);
        jdbc.update("INSERT INTO user_feedback (user_id, story_id, topic_score, character_score, outline_score, chapter_score, report_score, export_score, willingness, favorite_feature, biggest_problem, created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                userId, storyId, request.topicScore(), request.characterScore(), request.outlineScore(), request.chapterScore(), request.reportScore(), request.exportScore(), request.willingness(), request.favoriteFeature(), request.biggestProblem());
        Long id = jdbc.queryForObject("SELECT id FROM user_feedback WHERE user_id=? AND story_id=? ORDER BY id DESC LIMIT 1", Long.class, userId, storyId);
        return new FeedbackResponse(id, storyId, request, LocalDateTime.now());
    }

    public List<FeedbackResponse> list(Long userId, Long storyId) {
        stories.requireOwned(userId, storyId);
        return jdbc.query("SELECT * FROM user_feedback WHERE user_id=? AND story_id=? ORDER BY created_time DESC", (rs, row) -> new FeedbackResponse(
                rs.getLong("id"), storyId, new FeedbackRequest(rs.getObject("topic_score", Integer.class), rs.getObject("character_score", Integer.class),
                        rs.getObject("outline_score", Integer.class), rs.getObject("chapter_score", Integer.class), rs.getObject("report_score", Integer.class),
                        rs.getObject("export_score", Integer.class), rs.getString("willingness"), rs.getString("favorite_feature"), rs.getString("biggest_problem")), timestamp(rs.getTimestamp("created_time"))), userId, storyId);
    }

    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
