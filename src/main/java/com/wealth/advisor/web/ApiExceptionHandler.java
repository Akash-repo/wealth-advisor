package com.wealth.advisor.web;

import com.wealth.advisor.portfolio.InvalidPortfolioDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to HTTP responses in one place, using Spring's built-in {@link
 * ProblemDetail} (RFC 9457 "problem details") so error bodies have a standard shape:
 *
 * <pre>{@code
 * { "title": "Not a portfolio statement", "status": 422, "detail": "This doesn't look like ..." }
 * }</pre>
 *
 * Controllers just throw; they don't build error responses themselves.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(InvalidPortfolioDocumentException.class)
	ProblemDetail invalidPortfolioDocument(InvalidPortfolioDocumentException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
		problem.setTitle("Not a portfolio statement");
		return problem;
	}
}
