package com.storyforge.chapter.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StoryMemoryValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StoryMemoryService service = new StoryMemoryService(jdbc, new ChapterSupport(mapper));

    @Test
    void rejectsOversizedKeysAndUnknownEnumsBeforeAnyJdbcWrite() throws Exception {
        var memory = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"newFacts":[{
                  "factKey":"ok-key",
                  "factType":"CHARACTER_STATE",
                  "subject":"林晚",
                  "predicate":"mood",
                  "value":"警惕",
                  "visibility":"EVERYONE",
                  "status":"ACTIVE"
                }]}
                """);
        StoryChapter chapter = new StoryChapter(); chapter.setId(1L); chapter.setStoryId(2L); chapter.setChapterNo(1);
        StoryChapterVersion version = new StoryChapterVersion(); version.setId(3L);

        assertThatThrownBy(() -> service.persistApproval(chapter, version, null, memory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryUpdate.newFacts[0].visibility");
        verifyNoInteractions(jdbc);

        ((com.fasterxml.jackson.databind.node.ObjectNode) memory.withArray("newFacts").get(0))
                .put("visibility", "PUBLIC").put("factKey", "x".repeat(129));
        assertThatThrownBy(() -> service.persistApproval(chapter, version, null, memory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factKey").hasMessageContaining("128");
        verifyNoInteractions(jdbc);
    }

    @Test
    void acceptsStructuredCharacterStateAndBoundsItsDerivedKey() throws Exception {
        var memory = mapper.readTree("""
                {"characterStateChanges":[{
                  "character":"林晚",
                  "field":"investigation_notes",
                  "newValue":{"mood":"警惕","clues":["账单"]},
                  "visibility":"CHARACTER_PRIVATE"
                }]}
                """);

        assertThatCode(() -> service.validateApproval(null, memory)).doesNotThrowAnyException();
        String key = service.characterStateKey("角".repeat(100), "状态".repeat(50));
        assertThat(key).startsWith("character_state:").hasSizeLessThanOrEqualTo(128);
        assertThat(service.characterStateKey("林晚", "mood")).isEqualTo("character_state:林晚:mood");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsNullCharacterStateBeforeAnyJdbcWrite() throws Exception {
        var memory = mapper.readTree("""
                {"characterStateChanges":[{
                  "character":"林晚",
                  "field":"injury",
                  "newValue":null,
                  "visibility":"PUBLIC"
                }]}
                """);

        assertThatThrownBy(() -> service.validateApproval(null, memory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("characterStateChanges[0].newValue")
                .hasMessageContaining("不能为空");
        verifyNoInteractions(jdbc);
    }
}
