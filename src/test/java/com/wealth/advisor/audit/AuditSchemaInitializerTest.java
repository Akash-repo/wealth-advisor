package com.wealth.advisor.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema evolution is the part that must never drop or change a column of the live table. */
class AuditSchemaInitializerTest {

	private static final List<String> NEW_COLUMNS =
			List.of("agent1_cached_tokens", "agent1_thinking_tokens", "agent2_cached_tokens", "agent2_thinking_tokens");

	private static Schema schemaWithout(List<String> names) {
		return Schema.of(AuditSchemaInitializer.schema().getFields().stream().filter(f -> !names.contains(f.getName())).toList());
	}

	private static List<String> names(Schema schema) {
		return schema.getFields().stream().map(Field::getName).toList();
	}

	@Test
	void anOlderTable_getsTheNewColumnsAppended() {
		Schema old = schemaWithout(NEW_COLUMNS);

		Schema evolved = AuditSchemaInitializer.withMissingColumns(old, AuditSchemaInitializer.schema());

		assertThat(names(evolved)).containsAll(NEW_COLUMNS);
		assertThat(names(evolved).subList(0, old.getFields().size())).isEqualTo(names(old));
	}

	@Test
	void anUpToDateTable_isLeftExactlyAsItIs() {
		Schema current = AuditSchemaInitializer.schema();

		assertThat(AuditSchemaInitializer.withMissingColumns(current, AuditSchemaInitializer.schema())).isEqualTo(current);
	}

	@Test
	void columnsOnlyTheLiveTableHas_areNeverRemoved() {
		Schema live = Schema.of(Field.of("user_prompt_id", StandardSQLTypeName.STRING), Field.of("added_by_hand", StandardSQLTypeName.STRING));

		Schema evolved = AuditSchemaInitializer.withMissingColumns(live, AuditSchemaInitializer.schema());

		assertThat(names(evolved)).contains("added_by_hand");
	}

	@Test
	void everyNewColumn_isNullableSoExistingRowsStayValid() {
		Schema evolved = AuditSchemaInitializer.withMissingColumns(schemaWithout(NEW_COLUMNS), AuditSchemaInitializer.schema());

		evolved.getFields().stream()
				.filter(f -> NEW_COLUMNS.contains(f.getName()))
				.forEach(f -> assertThat(f.getMode() == null || f.getMode() == Field.Mode.NULLABLE).isTrue());
	}
}
