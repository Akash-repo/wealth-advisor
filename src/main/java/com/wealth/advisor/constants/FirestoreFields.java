package com.wealth.advisor.constants;

/**
 * Document field names that are both written and queried in more than one place, so a typo in one
 * spot would silently return no rows rather than fail.
 */
public final class FirestoreFields {

	private FirestoreFields() {}

	public static final String USER_ID = "userId";
}
