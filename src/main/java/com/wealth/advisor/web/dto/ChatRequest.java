package com.wealth.advisor.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/chat}: {@code {"sessionId": "...", "message": "..."}}.
 * {@code sessionId} is optional — omit it to start a new conversation (the response carries the
 * new id). Deliberately has no {@code userId} field: the caller's identity comes from their
 * authenticated session instead, see {@link com.wealth.advisor.web.ChatController}; a session id
 * that isn't that user's is a 404.
 *
 * <p>{@code message} is bounded at {@value #MAX_MESSAGE_LENGTH} characters — without this, a
 * single pathologically large message directly inflates that turn's Gemini input token cost and,
 * since a session's history is resent every turn, permanently bloats every future turn of that
 * conversation too, not just this one. 8,000 characters is generous
 * for a real chat message (this is a wealth-advisor Q&A app, not a document-paste tool) while
 * still ruling out someone pasting a whole document into the box.
 */
public record ChatRequest(
    @Size(max = 64, message = "sessionId must be at most 64 characters") String sessionId,
    @NotBlank(message = "message must not be blank")
        @Size(max = ChatRequest.MAX_MESSAGE_LENGTH, message = "message must be at most " + ChatRequest.MAX_MESSAGE_LENGTH + " characters")
        String message) {

  static final int MAX_MESSAGE_LENGTH = 8000;
}
