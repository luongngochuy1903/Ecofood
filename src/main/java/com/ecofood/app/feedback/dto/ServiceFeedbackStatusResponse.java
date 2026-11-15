package com.ecofood.app.feedback.dto;

/** Also used as the successful submit acknowledgment; never expose another guest's content. */
public record ServiceFeedbackStatusResponse(boolean submitted) {}
