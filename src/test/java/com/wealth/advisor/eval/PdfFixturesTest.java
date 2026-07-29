package com.wealth.advisor.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Round-trip check: generate a fixture's PDF bytes, then parse them back with the exact same
 * {@link PDFTextStripper} {@code PortfolioIngestionService} uses on real uploads. This is the
 * assertion that actually matters — it's not enough that {@link PdfFixtures} produces well-formed
 * PDF bytes in the abstract, it has to produce bytes the *real production parsing code* correctly
 * reads back the intended content from, since that's the exact path every eval case exercises.
 */
class PdfFixturesTest {

	@Test
	@Tag("eval")
	void generatedPdf_parsesBackToOriginalContent() throws Exception {
		byte[] pdfBytes = PdfFixtures.load("vanguard");

		String extracted;
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			extracted = new PDFTextStripper().getText(document);
		}

		assertThat(extracted).contains("GOOGL");
		assertThat(extracted).contains("JPM");
		assertThat(extracted).contains("50 shares");
		assertThat(extracted).contains("Vanguard");
	}

	@Test
	@Tag("eval")
	void allThreeFixtures_roundTripCorrectly() throws Exception {
		assertFixtureContains("fidelity", "AAPL", "MSFT", "Fidelity");
		assertFixtureContains("merrill", "AMZN", "NVDA", "Merrill");
	}

	private static void assertFixtureContains(String fixtureName, String... expectedSubstrings) throws Exception {
		String extracted;
		try (PDDocument document = Loader.loadPDF(PdfFixtures.load(fixtureName))) {
			extracted = new PDFTextStripper().getText(document);
		}
		for (String expected : expectedSubstrings) {
			assertThat(extracted).as("fixture '%s' should contain '%s'", fixtureName, expected).contains(expected);
		}
	}
}
