package com.storyforge.prompt;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.storyforge.common.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromptService {
    private final JdbcTemplate jdbc;

    public PromptService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public PromptTemplateResponse create(Long userId, PromptTemplateRequest request) {
        String key = request.promptKey().trim();
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM prompt_template WHERE prompt_key=?", Integer.class, key);
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("INSERT INTO prompt_template (prompt_key, prompt_type, version_no, system_prompt, user_template, output_schema, model_profile, status, change_summary, created_by, created_time) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, CURRENT_TIMESTAMP)",
                key, request.promptType().trim().toUpperCase(), version, request.systemPrompt(), request.userTemplate(), request.outputSchema(), request.modelProfile(), request.changeSummary(), userId);
        return findByKeyVersion(key, version);
    }

    public List<PromptTemplateResponse> list(Long userId) {
        return jdbc.query("SELECT * FROM prompt_template WHERE created_by=? ORDER BY prompt_key, version_no DESC", (rs, row) -> map(rs), userId);
    }

    public PromptTemplateResponse get(Long userId, Long id) {
        return findOwned(userId, id);
    }

    @Transactional
    public PromptTemplateResponse update(Long userId, Long id, PromptTemplateRequest request) {
        PromptTemplateResponse existing = findOwned(userId, id);
        if ("PUBLISHED".equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROMPT_PUBLISHED_IMMUTABLE", "已发布 Prompt 不能直接修改，请创建新版本");
        }
        jdbc.update("UPDATE prompt_template SET prompt_type=?, system_prompt=?, user_template=?, output_schema=?, model_profile=?, change_summary=? WHERE id=? AND created_by=?",
                request.promptType().trim().toUpperCase(), request.systemPrompt(), request.userTemplate(), request.outputSchema(), request.modelProfile(), request.changeSummary(), id, userId);
        return findOwned(userId, id);
    }

    @Transactional
    public PromptTemplateResponse markTesting(Long userId, Long id) {
        PromptTemplateResponse existing = findOwned(userId, id);
        if ("PUBLISHED".equals(existing.status())) throw new ApiException(HttpStatus.CONFLICT, "PROMPT_PUBLISHED_IMMUTABLE", "已发布 Prompt 不能重新测试");
        jdbc.update("UPDATE prompt_template SET status='TESTING' WHERE id=? AND created_by=?", id, userId);
        return findOwned(userId, id);
    }

    @Transactional
    public PromptTemplateResponse publish(Long userId, Long id) {
        PromptTemplateResponse existing = findOwned(userId, id);
        jdbc.update("UPDATE prompt_template SET status='RETIRED' WHERE prompt_key=? AND created_by=? AND status='PUBLISHED'", existing.promptKey(), userId);
        jdbc.update("UPDATE prompt_template SET status='PUBLISHED', published_time=CURRENT_TIMESTAMP WHERE id=? AND created_by=?", id, userId);
        return findOwned(userId, id);
    }

    private PromptTemplateResponse findOwned(Long userId, Long id) {
        List<PromptTemplateResponse> rows = jdbc.query("SELECT * FROM prompt_template WHERE id=? AND created_by=?", (rs, row) -> map(rs), id, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PROMPT_NOT_FOUND", "Prompt 版本不存在");
        return rows.get(0);
    }

    private PromptTemplateResponse findByKeyVersion(String key, int version) {
        List<PromptTemplateResponse> rows = jdbc.query("SELECT * FROM prompt_template WHERE prompt_key=? AND version_no=?", (rs, row) -> map(rs), key, version);
        return rows.get(0);
    }

    private PromptTemplateResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PromptTemplateResponse(rs.getLong("id"), rs.getString("prompt_key"), rs.getString("prompt_type"), rs.getInt("version_no"),
                rs.getString("system_prompt"), rs.getString("user_template"), rs.getString("output_schema"), rs.getString("model_profile"),
                rs.getString("status"), rs.getString("change_summary"), timestamp(rs.getTimestamp("created_time")), timestamp(rs.getTimestamp("published_time")));
    }

    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
