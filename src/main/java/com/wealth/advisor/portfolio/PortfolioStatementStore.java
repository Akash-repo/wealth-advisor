package com.wealth.advisor.portfolio;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.google.common.collect.Lists;
import com.wealth.advisor.constants.FirestoreCollections;
import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.util.FirestoreFutures;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * All Firestore access for uploaded statements: one {@code portfolio_documents} record per
 * statement plus one {@code portfolio_holdings} record per position in it.
 *
 * <p>A statement is saved with batched writes and its record goes in the <em>last</em> batch, so a
 * statement only shows up in the list once all of its holdings are stored. (For statements within
 * one batch, which is every realistic one, the save is fully atomic.)
 */
@Component
public class PortfolioStatementStore {

	/** Firestore allows 500 writes per batch; leave headroom for the metadata and user updates. */
	private static final int MAX_HOLDINGS_PER_BATCH = 400;

	private static final String FILENAME = "filename";

	private final Firestore firestore;

	public PortfolioStatementStore(Firestore firestore) {
		this.firestore = firestore;
	}

	public void save(String userId, String filename, List<Holding> holdings) {
		Lists.partition(holdings, MAX_HOLDINGS_PER_BATCH)
				.forEach(part -> commit("save holdings", batch -> part.forEach(h -> addHolding(batch, userId, filename, h))));
		commit(
				"save statement record",
				batch -> batch.create(newDocument(FirestoreCollections.PORTFOLIO_DOCUMENTS), statementRecord(userId, filename, holdings.size())));
	}

	/** The user's statements, newest first. */
	public List<Map<String, Object>> list(String userId) {
		return userDocuments(FirestoreCollections.PORTFOLIO_DOCUMENTS, userId, null).stream()
				.sorted(Comparator.comparing((QueryDocumentSnapshot d) -> d.getTimestamp("uploadedAt")).reversed())
				.map(QueryDocumentSnapshot::getData)
				.toList();
	}

	/** Removes a statement's record and all of its holdings. */
	public void delete(String userId, String filename) {
		List<DocumentReference> refs = new ArrayList<>();
		for (String collection : List.of(FirestoreCollections.PORTFOLIO_HOLDINGS, FirestoreCollections.PORTFOLIO_DOCUMENTS)) {
			userDocuments(collection, userId, filename).forEach(d -> refs.add(d.getReference()));
		}
		Lists.partition(refs, MAX_HOLDINGS_PER_BATCH)
				.forEach(part -> commit("delete statement", batch -> part.forEach(batch::delete)));
	}

	private List<QueryDocumentSnapshot> userDocuments(String collection, String userId, String filenameOrNull) {
		var query = firestore.collection(collection).whereEqualTo(FirestoreFields.USER_ID, userId);
		if (filenameOrNull != null) {
			query = query.whereEqualTo(FILENAME, filenameOrNull);
		}
		return FirestoreFutures.await(query.get(), "read statements").getDocuments();
	}

	private void addHolding(WriteBatch batch, String userId, String filename, Holding holding) {
		Map<String, Object> doc = new HashMap<>();
		doc.put(FirestoreFields.USER_ID, userId);
		doc.put(FILENAME, filename);
		doc.put("ticker", holding.ticker());
		doc.put("shares", holding.shares());
		doc.put("costBasis", holding.costBasis());
		doc.put("account", holding.account());
		doc.put("extractedAt", Timestamp.now());
		batch.create(newDocument(FirestoreCollections.PORTFOLIO_HOLDINGS), doc);
	}

	private static Map<String, Object> statementRecord(String userId, String filename, int holdingsCount) {
		return Map.of(
				FirestoreFields.USER_ID, userId,
				FILENAME, filename,
				"holdingsCount", holdingsCount,
				"uploadedAt", Timestamp.now());
	}

	private DocumentReference newDocument(String collection) {
		return firestore.collection(collection).document();
	}

	private void commit(String action, Consumer<WriteBatch> writes) {
		WriteBatch batch = firestore.batch();
		writes.accept(batch);
		FirestoreFutures.await(batch.commit(), action);
	}
}
