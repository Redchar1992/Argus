package com.storyforge.artifact;

import java.util.Locale;
import java.util.Set;

public final class ArtifactType {

    public static final String CHARACTER = "CHARACTER";
    public static final String OUTLINE = "OUTLINE";
    public static final String SCORE = "SCORE";
    public static final String WORKFLOW_FINAL = "WORKFLOW_FINAL";

    private static final Set<String> SUPPORTED = Set.of(
            CHARACTER,
            OUTLINE,
            SCORE,
            WORKFLOW_FINAL
    );

    private ArtifactType() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("artifactType 不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalArgumentException("不支持的 artifactType: " + value);
        }
        return normalized;
    }
}
