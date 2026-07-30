package com.storyforge.artifact;

import com.fasterxml.jackson.databind.JsonNode;

public record ArtifactInput(
        String artifactType,
        Integer versionNo,
        String status,
        JsonNode content,
        String promptVersion,
        String modelName
) {
}
