package com.storyforge.prompt;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves the user's published prompt while retaining a safe built-in fallback. */
@Service
public class PromptResolver {
    private final JdbcTemplate jdbc;

    public PromptResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Selection resolve(Long userId, String promptKey, String fallbackVersion) {
        List<Selection> rows = jdbc.query("""
                SELECT prompt_key, version_no, system_prompt, user_template, model_profile
                FROM prompt_template
                WHERE created_by=? AND prompt_key=? AND status='PUBLISHED'
                ORDER BY version_no DESC
                LIMIT 1
                """, (rs, row) -> new Selection(rs.getString("prompt_key"), rs.getInt("version_no"),
                rs.getString("system_prompt"), rs.getString("user_template"), rs.getString("model_profile")),
                userId, promptKey);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return new Selection(promptKey, parseFallbackVersion(fallbackVersion), null, null, null);
    }

    private int parseFallbackVersion(String value) {
        if (value == null || value.isBlank()) return 1;
        String digits = value.replaceAll(".*?(\\d+)$", "$1");
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 1; }
    }

    public record Selection(String promptKey, int version, String systemPrompt,
            String userTemplate, String modelProfile) {
        public String versionLabel() { return promptKey + "_v" + version; }
    }
}
