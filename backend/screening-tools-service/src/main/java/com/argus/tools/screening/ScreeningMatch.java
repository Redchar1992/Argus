package com.argus.tools.screening;

/** A direct address-list match, preserving the named entity and legal list evidence. */
public record ScreeningMatch(
        String address,
        String entity,
        String listSource,
        String program,
        int severity) {
}
