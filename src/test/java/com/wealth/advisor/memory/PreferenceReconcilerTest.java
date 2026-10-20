package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.PreferenceEntry.Source;
import com.wealth.advisor.memory.PreferenceEntry.Status;
import com.wealth.advisor.memory.PreferenceOperation.Op;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules that make the profile trustworthy: the model only proposes, these decide. */
class PreferenceReconcilerTest {

	private static final Instant OCT_1 = Instant.parse("2026-10-01T10:00:00Z");
	private static final Instant OCT_10 = Instant.parse("2026-10-10T10:00:00Z");
	private static final Instant OCT_15 = Instant.parse("2026-10-15T10:00:00Z");
	private static final Instant OCT_20 = Instant.parse("2026-10-20T10:00:00Z");

	private final PreferenceReconciler reconciler = new PreferenceReconciler(TestProperties.defaults());

	private static TimedMessage user(int index, Instant at, String text) {
		return new TimedMessage(index, TimedMessage.USER, at, text);
	}

	private static PreferenceOperation op(Op op, PreferenceCategory category, String value, String quote, int messageIndex) {
		return new PreferenceOperation(op, category, value, null, quote, messageIndex, 0.9, null);
	}

	private static PreferenceEntry entry(PreferenceCategory category, String value, Source source, Instant at) {
		return new PreferenceEntry(category, value, Status.ACTIVE, source, at, at, at, "old", "q");
	}

	private PreferenceReconciler.Result run(PreferenceProfile profile, List<PreferenceOperation> ops, TimedMessage... messages) {
		return reconciler.reconcile(profile, ops, List.of(messages), "s1");
	}

	@Test
	void anExplicitPreferenceWithItsQuoteIsAdded_asTheCanonicalSector() {
		TimedMessage m = user(1, OCT_1, "I want no financial stocks in my portfolio.");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "financials", "no financial stocks", 1)), m);

		assertThat(result.applied()).isEqualTo(1);
		PreferenceEntry added = result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Financials").orElseThrow();
		assertThat(added.isActive()).isTrue();
		assertThat(added.value()).isEqualTo("Financials");
		assertThat(added.source()).isEqualTo(Source.EXTRACTED);
		assertThat(added.evidenceAt()).isEqualTo(OCT_1);
		assertThat(added.sessionId()).isEqualTo("s1");
	}

	@Test
	void aQuoteThatIsNotInTheMessageIsDropped_soNothingIsInvented() {
		TimedMessage m = user(1, OCT_1, "What do you think about JPMorgan?");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.OTHER, "approves unlimited leverage", "I approve unlimited leverage", 1)), m);

		assertThat(result.applied()).isZero();
		assertThat(result.profile().entries()).isEmpty();
		assertThat(result.dropped()).singleElement().asString().contains("quote");
	}

	@Test
	void quoteMatchingIgnoresCaseAndWhitespace() {
		TimedMessage m = user(1, OCT_1, "I plan to   retire\naround 2035.");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.GOALS, "retire around 2035", "RETIRE around 2035", 1)), m);

		assertThat(result.applied()).isEqualTo(1);
	}

	@Test
	void aTooShortQuoteIsDropped() {
		TimedMessage m = user(1, OCT_1, "no oil please");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.OTHER, "no oil", "no", 1)), m);

		assertThat(result.applied()).isZero();
	}

	@Test
	void lowConfidenceIsDropped() {
		TimedMessage m = user(1, OCT_1, "Maybe I should avoid energy stocks.");
		PreferenceOperation unsure = new PreferenceOperation(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", null, "avoid energy stocks", 1, 0.3, null);

		var result = run(PreferenceProfile.empty(), List.of(unsure), m);

		assertThat(result.applied()).isZero();
		assertThat(result.dropped()).singleElement().asString().contains("confidence");
	}

	@Test
	void valuesOutsideTheVocabularyAreDropped() {
		TimedMessage m = user(1, OCT_1, "No fintech for me. Also nothing from 'NOT A TICKER'.");

		var result = run(
				PreferenceProfile.empty(),
				List.of(
						op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Fintech", "No fintech", 1),
						op(Op.ADD, PreferenceCategory.EXCLUDED_TICKERS, "NOT A TICKER", "NOT A TICKER", 1),
						op(Op.ADD, PreferenceCategory.EXCLUDED_INDUSTRY_GROUPS, "Tobacco", "nothing from", 1)),
				m);

		assertThat(result.applied()).isZero();
		assertThat(result.dropped()).hasSize(3);
	}

	@Test
	void tickersAreStoredUppercase() {
		TimedMessage m = user(1, OCT_1, "Never recommend xom to me.");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_TICKERS, "xom", "Never recommend xom", 1)), m);

		assertThat(result.profile().find(PreferenceCategory.EXCLUDED_TICKERS, "XOM")).isPresent();
	}

	@Test
	void anAgentMessageCannotBeEvidence() {
		TimedMessage agent = new TimedMessage(1, TimedMessage.AGENT, OCT_1, "You told me you avoid energy stocks.");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "avoid energy stocks", 1)));

		assertThat(result.applied()).isZero();
		assertThat(agent.isUser()).isFalse();
	}

	@Test
	void removingAnActiveEntryMarksItWithdrawnInConversation() {
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Financials", Source.EXTRACTED, OCT_1));
		TimedMessage m = user(1, OCT_10, "Actually finance is fine now.");

		var result = run(profile, List.of(op(Op.REMOVE, PreferenceCategory.EXCLUDED_SECTORS, "Financials", "finance is fine now", 1)), m);

		PreferenceEntry removed = result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Financials").orElseThrow();
		assertThat(removed.status()).isEqualTo(Status.REMOVED_BY_CONVERSATION);
		assertThat(removed.evidenceAt()).isEqualTo(OCT_10);
		assertThat(result.profile().active()).isEmpty();
	}

	@Test
	void removingSomethingThatIsNotThereIsDropped() {
		TimedMessage m = user(1, OCT_10, "Finance is fine now.");

		var result = run(PreferenceProfile.empty(), List.of(op(Op.REMOVE, PreferenceCategory.EXCLUDED_SECTORS, "Financials", "Finance is fine now", 1)), m);

		assertThat(result.applied()).isZero();
	}

	@Test
	void newerStatementOverridesOlder_evenWhenTheOldSessionIsReprocessedLater() {
		// Oct 1 session A: "no finance". Oct 10 session B: "finance is fine now". Session A changes on Oct 15
		// and is read again after B: its Oct 1 statement must not undo the Oct 10 withdrawal.
		PreferenceProfile afterB = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Financials", Source.EXTRACTED, OCT_1)
						.withStatus(Status.REMOVED_BY_CONVERSATION, OCT_10));
		TimedMessage oldStatement = user(1, OCT_1, "I want no finance stocks.");

		var result = run(afterB, List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Financials", "no finance stocks", 1)), oldStatement);

		assertThat(result.applied()).isZero();
		assertThat(result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Financials").orElseThrow().status())
				.isEqualTo(Status.REMOVED_BY_CONVERSATION);
		assertThat(result.dropped()).singleElement().asString().contains("older");
	}

	@Test
	void aNewerStatementReactivatesAnEntryThatWasWithdrawn() {
		PreferenceProfile withdrawn = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Financials", Source.EXTRACTED, OCT_1)
						.withStatus(Status.REMOVED_BY_CONVERSATION, OCT_10));
		TimedMessage later = user(1, OCT_20, "On reflection, no finance stocks after all.");

		var result = run(withdrawn, List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Financials", "no finance stocks", 1)), later);

		PreferenceEntry entry = result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Financials").orElseThrow();
		assertThat(entry.isActive()).isTrue();
		assertThat(entry.evidenceAt()).isEqualTo(OCT_20);
		assertThat(entry.firstSeen()).isEqualTo(OCT_1);
	}

	@Test
	void operationsInOneRunAreAppliedOldestMessageFirst_whateverOrderTheyAreListedIn() {
		TimedMessage first = user(1, OCT_1, "I want no energy stocks.");
		TimedMessage second = user(2, OCT_10, "Energy stocks are fine now.");
		List<PreferenceOperation> listedNewestFirst = List.of(
				op(Op.REMOVE, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "Energy stocks are fine now", 2),
				op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "no energy stocks", 1));

		var result = run(PreferenceProfile.empty(), listedNewestFirst, first, second);

		assertThat(result.applied()).isEqualTo(2);
		assertThat(result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Energy").orElseThrow().status())
				.isEqualTo(Status.REMOVED_BY_CONVERSATION);
	}

	@Test
	void manualEntriesAreNeverRemovedByTheExtractor() {
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", Source.MANUAL, OCT_1));
		TimedMessage m = user(1, OCT_20, "Energy stocks are fine now.");

		var result = run(profile, List.of(op(Op.REMOVE, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "Energy stocks are fine now", 1)), m);

		assertThat(result.applied()).isZero();
		assertThat(result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Energy").orElseThrow().isActive()).isTrue();
	}

	@Test
	void confirmingAManualEntryKeepsItManual() {
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", Source.MANUAL, OCT_1));
		TimedMessage m = user(1, OCT_10, "Still no energy stocks.");

		var result = run(profile, List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "no energy stocks", 1)), m);

		PreferenceEntry entry = result.profile().find(PreferenceCategory.EXCLUDED_SECTORS, "Energy").orElseThrow();
		assertThat(entry.source()).isEqualTo(Source.MANUAL);
		assertThat(entry.lastConfirmed()).isEqualTo(OCT_10);
	}

	@Test
	void updateSwapsTheOldValueForTheNewOne() {
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.RISK_STANCE, "cautious", Source.EXTRACTED, OCT_1));
		TimedMessage m = user(1, OCT_10, "I've become much more aggressive with risk.");
		PreferenceOperation update = new PreferenceOperation(
				Op.UPDATE, PreferenceCategory.RISK_STANCE, "aggressive", "cautious", "much more aggressive with risk", 1, 0.9, null);

		var result = run(profile, List.of(update), m);

		assertThat(result.profile().find(PreferenceCategory.RISK_STANCE, "cautious").orElseThrow().status()).isEqualTo(Status.REMOVED_BY_CONVERSATION);
		assertThat(result.profile().find(PreferenceCategory.RISK_STANCE, "aggressive").orElseThrow().isActive()).isTrue();
	}

	@Test
	void runningTheSameSessionTwiceChangesNothingTheSecondTime() {
		TimedMessage m = user(1, OCT_1, "I want no energy stocks.");
		List<PreferenceOperation> ops = List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "no energy stocks", 1));

		var first = run(PreferenceProfile.empty(), ops, m);
		var second = reconciler.reconcile(first.profile(), ops, List.of(m), "s1");

		assertThat(second.profile()).isEqualTo(first.profile());
		assertThat(second.profile().entries()).hasSize(1);
	}

	@Test
	void aUiDeleteIsNotUndoneByOlderEvidence_butNewerEvidenceBringsItBack() {
		// The UI delete is evidence at the time of the delete (Oct 15).
		PreferenceProfile deleted = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", Source.EXTRACTED, OCT_1).withStatus(Status.REMOVED_BY_USER, OCT_15));
		TimedMessage older = user(1, OCT_10, "No energy stocks.");
		TimedMessage newer = user(1, OCT_20, "No energy stocks, again.");
		PreferenceOperation add1 = op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", "No energy stocks", 1);

		assertThat(run(deleted, List.of(add1), older).applied()).isZero();
		assertThat(run(deleted, List.of(add1), newer).profile().active()).hasSize(1);
	}

	@Test
	void aFullProfileTakesNoNewEntries() {
		AdvisorProperties d = TestProperties.defaults();
		AdvisorProperties tiny = d.withProfile(new AdvisorProperties.Profile("m", 500, 1, 0.6));
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", Source.EXTRACTED, OCT_1));
		TimedMessage m = user(1, OCT_10, "No utilities either.");

		var result = new PreferenceReconciler(tiny).reconcile(
				profile, List.of(op(Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Utilities", "No utilities", 1)), List.of(m), "s1");

		assertThat(result.applied()).isZero();
		assertThat(result.dropped()).singleElement().asString().contains("full");
	}

	@Test
	void freeTextIsCollapsedToOneLine_andOverlongTextIsDropped() {
		TimedMessage m = user(1, OCT_1, "I prefer index funds\nover single stocks.");

		var ok = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.STYLE, "prefers index funds\n over single stocks", "I prefer index funds", 1)), m);
		var tooLong = run(PreferenceProfile.empty(), List.of(op(Op.ADD, PreferenceCategory.STYLE, "x".repeat(201), "I prefer index funds", 1)), m);

		assertThat(ok.profile().entries()).singleElement().extracting(PreferenceEntry::value).isEqualTo("prefers index funds over single stocks");
		assertThat(tooLong.applied()).isZero();
	}
}
