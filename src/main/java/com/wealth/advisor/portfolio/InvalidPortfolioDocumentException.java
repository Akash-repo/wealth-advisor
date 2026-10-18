package com.wealth.advisor.portfolio;

/**
 * An uploaded file isn't a usable portfolio statement: it can't be read as a PDF, has no text, or
 * no holdings could be found in it. The message is written for the end user and is returned to the
 * client as-is (HTTP 422), so it must never contain internals.
 */
public class InvalidPortfolioDocumentException extends RuntimeException {

	public InvalidPortfolioDocumentException(String message) {
		super(message);
	}
}
