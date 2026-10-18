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
	public static final String PORTFOLIO_HOLDINGS = "portfolio_holdings";
	public static final String MARKET_DATA_CACHE = "market_data_cache";
	/** One document per user: the typed preference profile. See docs/design/03-user-preferences-memory.md. */
	public static final String USER_PREFERENCES = "user_preferences";
	/** One document per (user, session): when the background memory job last processed it. */
	public static final String MEMORY_EXTRACTION_STATE = "memory_extraction_state";
	/** One document per episode (a topic summary of a past conversation). See docs/design/04-episodic-memory.md. */
	public static final String EPISODES = "episodes";
}
