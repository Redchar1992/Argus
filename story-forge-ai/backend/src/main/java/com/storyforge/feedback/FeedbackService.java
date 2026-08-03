package com.storyforge.feedback;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.story.StoryService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
    private final StoryService stories;
    private final JdbcTemplate jdbc;
    private final ProductAnalyticsService analytics;

    public FeedbackService(
            StoryService stories,
            JdbcTemplate jdbc,
            ProductAnalyticsService analytics
    ) {
        this.stories = stories;
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    @Transactional
    public FeedbackResponse create(Long userId, Long storyId, FeedbackRequest request) {
        stories.requireOwned(userId, storyId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO user_feedback
                    (user_id, story_id, topic_score, character_score,
                     outline_score, chapter_score, report_score, export_score,
                     willingness, favorite_feature, biggest_problem, created_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[] {"id"});
            statement.setLong(1, userId);
            statement.setLong(2, storyId);
            statement.setObject(3, request.topicScore());
            statement.setObject(4, request.characterScore());
            statement.setObject(5, request.outlineScore());
            statement.setObject(6, request.chapterScore());
            statement.setObject(7, request.reportScore());
            statement.setObject(8, request.exportScore());
            statement.setString(9, request.willingness());
            statement.setString(10, request.favoriteFeature());
            statement.setString(11, request.biggestProblem());
            return statement;
        }, keys);
        // JDBC drivers do not agree on the generated-key column label
        // (MySQL uses GENERATED_KEY, while H2 commonly exposes the table
        // column name). KeyHolder#getKey is deliberately label-independent.
        Number generatedId = keys.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("反馈记录未生成 ID");
        }
        Long id = generatedId.longValue();
        analytics.record(
                ProductEventNames.FEEDBACK_SUBMITTED,
                userId,
                storyId,
                null,
                "feedback:" + id + ":submitted"
        );
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
