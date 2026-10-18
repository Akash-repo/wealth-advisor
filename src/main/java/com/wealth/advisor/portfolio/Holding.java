package com.wealth.advisor.portfolio;

/**
 * One stock or ETF position read out of a statement.
 *
 * @param ticker upper-cased symbol (normalized on construction)
 * @param shares number of shares held
 * @param costBasis cost per share if the statement states one, else {@code null}
 * @param account brokerage or account name if the statement states one, else {@code null}
 */
public record Holding(String ticker, Double shares, Double costBasis, String account) {

	public Holding {
		ticker = ticker == null ? null : ticker.strip().toUpperCase();
	}

	/** A position without a symbol or a share count can't be valued or summed, so it is dropped. */
	public boolean isUsable() {
		return ticker != null && !ticker.isBlank() && shares != null;
	}
}
