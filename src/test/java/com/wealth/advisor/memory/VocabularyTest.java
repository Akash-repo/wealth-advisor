package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class VocabularyTest {

	@Test
	void thereAreElevenSectorsAndTwentyFiveIndustryGroups() {
		assertThat(Sector.values()).hasSize(11);
		assertThat(IndustryGroup.values()).hasSize(25);
	}

	@Test
	void labelsAreUnique() {
		assertThat(new HashSet<>(Sector.labels())).hasSize(11);
		assertThat(new HashSet<>(IndustryGroup.labels())).hasSize(25);
	}

	@Test
	void everySectorHasAtLeastOneIndustryGroup() {
		for (Sector sector : Sector.values()) {
			assertThat(Arrays.stream(IndustryGroup.values()).anyMatch(g -> g.sector() == sector)).as(sector.label()).isTrue();
		}
	}

	@Test
	void banksBelongToFinancials() {
		assertThat(IndustryGroup.BANKS.sector()).isEqualTo(Sector.FINANCIALS);
	}

	@Test
	void lookupIgnoresCaseAndSurroundingSpace() {
		assertThat(Sector.fromLabel("  financials ")).contains(Sector.FINANCIALS);
		assertThat(IndustryGroup.fromLabel("BANKS")).contains(IndustryGroup.BANKS);
	}

	@Test
	void unknownOrMissingLabelsAreEmpty() {
		assertThat(Sector.fromLabel("Fintech")).isEmpty();
		assertThat(Sector.fromLabel(null)).isEmpty();
		assertThat(IndustryGroup.fromLabel("Tobacco")).isEmpty();
	}
}
