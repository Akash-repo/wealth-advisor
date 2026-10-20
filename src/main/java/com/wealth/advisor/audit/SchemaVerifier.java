package com.wealth.advisor.audit;

import com.google.cloud.bigquery.BigQuery;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * The read-only side of the BigQuery schema initializers, used when {@code advisor.bigquery.schema-mode}
 * is {@code verify} (the deployed service): it can only look and report, so a new revision starting up can
 * never change the production schema. Changes are applied as a separate, deliberate step.
 */
final class SchemaVerifier {

	private SchemaVerifier() {}

	/** The names among {@code names} that do not exist as tables or views in {@code dataset}. */
	static List<String> missingTables(BigQuery bigQuery, String dataset, List<String> names) {
		List<String> missing = new ArrayList<>();
		for (String name : names) {
			if (bigQuery.getTable(dataset, name) == null) {
				missing.add("table or view " + name);
			}
		}
		return missing;
	}

	static void report(Logger log, String dataset, List<String> problems) {
		if (problems.isEmpty()) {
			log.info("BigQuery schema check passed for dataset '{}' (schema-mode=verify; nothing was changed)", dataset);
		} else {
			log.error(
					"BigQuery dataset '{}' is behind the code (schema-mode=verify, so nothing was changed). Missing: {}."
							+ " Apply the schema as a separate step: see ARCHITECTURE.md, \"Environments\".",
					dataset,
					problems);
		}
	}
}
