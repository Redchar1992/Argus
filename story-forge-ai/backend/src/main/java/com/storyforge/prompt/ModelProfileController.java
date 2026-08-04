package com.storyforge.prompt;

/**
 * Model profiles are platform-owned configuration, not a user feature.
 *
 * <p>The service and table remain available for a future authenticated admin
 * console, but there is deliberately no public controller. This prevents a
 * normal user from registering an arbitrary provider/model or exposing secret
 * references through the API.</p>
 */
final class ModelProfileController {
    private ModelProfileController() {
    }
}
