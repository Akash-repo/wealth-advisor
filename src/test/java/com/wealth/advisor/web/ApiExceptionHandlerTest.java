package com.wealth.advisor.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.portfolio.InvalidPortfolioDocumentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

	@Test
	void invalidPortfolioDocument_becomesA422ProblemDetailWithTheUserMessage() {
		ProblemDetail problem =
				new ApiExceptionHandler().invalidPortfolioDocument(new InvalidPortfolioDocumentException("Please upload a statement."));

		assertThat(problem.getStatus()).isEqualTo(422);
		assertThat(problem.getTitle()).isEqualTo("Not a portfolio statement");
		assertThat(problem.getDetail()).isEqualTo("Please upload a statement.");
	}
}
