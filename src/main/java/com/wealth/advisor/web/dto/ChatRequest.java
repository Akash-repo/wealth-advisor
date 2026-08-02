package com.wealth.advisor.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/chat} — just {@code {"message": "..."}}. Deliberately has no
 * {@code userId} field: the caller's identity comes from their authenticated session instead, see
 * {@link com.wealth.advisor.web.ChatController}.
 *
 * <p>{@code message} is bounded at {@value #MAX_MESSAGE_LENGTH} characters — without this, a
 * single pathologically large message directly inflates that turn's Gemini input token cost and,
 * since this app's session never resets (one continuous conversation per user), permanently
 * bloats every future turn's resent history too, not just this one. 8,000 characters is generous
 * for a real chat message (this is a wealth-advisor Q&A app, not a document-paste tool) while
 * still ruling out someone pasting a whole document into the box.
 */
public record ChatRequest(
    @NotBlank(message = "message must not be blank")
        @Size(max = ChatRequest.MAX_MESSAGE_LENGTH, message = "message must be at most " + ChatRequest.MAX_MESSAGE_LENGTH + " characters")
        String message) {

  static final int MAX_MESSAGE_LENGTH = 8000;
}
