package com.wealth.advisor.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit against a standalone Bean Validation {@link Validator} — no {@code @SpringBootTest}
 * needed, since validating a DTO's constraints doesn't require the full application context, only
 * the validation provider (Hibernate Validator, brought in by {@code
 * spring-boot-starter-validation}). Guards {@code Optimization.md} P1-4: a message over the size
 * limit, or blank, must fail validation before {@code ChatController} ever runs.
 */
class ChatRequestTest {

  private static ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  void normalMessage_hasNoViolations() {
    ChatRequest request = new ChatRequest("Can I invest in Nvidia?");

    Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }

  @Test
  void blankMessage_isRejected() {
    ChatRequest request = new ChatRequest("   ");

    Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

    assertThat(violations).isNotEmpty();
  }

  @Test
  void messageOverTheLimit_isRejected() {
    ChatRequest request = new ChatRequest("a".repeat(ChatRequest.MAX_MESSAGE_LENGTH + 1));

    Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

    assertThat(violations).isNotEmpty();
  }

  @Test
  void messageAtExactlyTheLimit_isAllowed() {
    ChatRequest request = new ChatRequest("a".repeat(ChatRequest.MAX_MESSAGE_LENGTH));

    Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

    assertThat(violations).isEmpty();
  }
}
