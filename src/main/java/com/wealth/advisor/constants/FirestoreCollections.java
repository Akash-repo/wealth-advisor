package com.wealth.advisor.constants;

/**
 * Names of the app's own Firestore collections. These are constants, not configuration: changing
 * one is a data migration, not a deployment setting. (ADK's session collections are named by the
 * ADK Firestore module itself and aren't listed here.)
 */
public final class FirestoreCollections {

	private FirestoreCollections() {}

	public static final String USERS = "users";
	public static final String PORTFOLIO_DOCUMENTS = "portfolio_documents";
	public static final String PORTFOLIO_VECTORS = "portfolio_vectors";
	public static final String PORTFOLIO_HOLDINGS = "portfolio_holdings";
	public static final String MARKET_DATA_CACHE = "market_data_cache";
}
