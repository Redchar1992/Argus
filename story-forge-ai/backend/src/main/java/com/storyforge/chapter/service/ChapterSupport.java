package com.storyforge.chapter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChapterSupport {
    private final ObjectMapper mapper;
    public ChapterSupport(ObjectMapper mapper) { this.mapper = mapper; }
    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public String write(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("JSON 序列化失败", exception); }
    }
    public JsonNode read(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的 JSON 无效", exception); }
    }
    public JsonNode readRequired(String value, String name) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException(name + " 不是有效 JSON", exception); }
    }
    public ObjectMapper mapper() { return mapper; }
}
