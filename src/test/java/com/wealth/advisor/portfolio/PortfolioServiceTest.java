package com.wealth.advisor.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Uploads are portfolio statements only. A rejected file must leave nothing behind, so the tests
 * assert on what was <em>not</em> touched (the store), not just on the exception.
 */
class PortfolioServiceTest {

	private HoldingsExtractionService extraction;
	private PortfolioStatementStore store;
	private PortfolioService service;

	@BeforeEach
	void setUp() {
		extraction = mock(HoldingsExtractionService.class);
		store = mock(PortfolioStatementStore.class);
		service = new PortfolioService(new PdfTextReader(), extraction, store);
	}

	private static byte[] pdf(String textOrNull) throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			if (textOrNull != null) {
				try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(50, 700);
					content.showText(textOrNull);
					content.endText();
				}
			}
			doc.save(out);
			return out.toByteArray();
		}
	}

	@Test
	void validStatement_isStoredAndReturnsTheHoldingCount() throws Exception {
		List<Holding> holdings = List.of(new Holding("AAPL", 10.0, null, null), new Holding("MSFT", 5.0, null, null));
		when(extraction.extract(anyString())).thenReturn(holdings);

		int stored = service.ingest("alice", "vanguard.pdf", pdf("Holdings: AAPL 10, MSFT 5"));

		assertThat(stored).isEqualTo(2);
		verify(store).save("alice", "vanguard.pdf", holdings);
	}

	@Test
	void notAPdf_isRejectedAndNothingIsTouched() {
		byte[] notPdf = "just some text, not a pdf".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.ingest("alice", "notes.txt", notPdf))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("PDF");

		verifyNoInteractions(extraction, store);
	}

	@Test
	void pdfWithNoText_isRejectedAndNothingIsTouched() throws Exception {
		byte[] blank = pdf(null);

		assertThatThrownBy(() -> service.ingest("alice", "scan.pdf", blank))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("No readable text");

		verifyNoInteractions(extraction, store);
	}

	@Test
	void documentWithNoHoldings_isRejectedAndNothingIsStored() throws Exception {
		when(extraction.extract(anyString())).thenReturn(List.of());

		assertThatThrownBy(() -> service.ingest("alice", "resume.pdf", pdf("Jane Doe - Senior Engineer")))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("portfolio statement");

		verifyNoInteractions(store);
	}

	@Test
	void extractionOutage_isAServerErrorNotABlameTheUserRejection() throws Exception {
		when(extraction.extract(anyString())).thenThrow(new IllegalStateException("Holdings extraction call failed"));

		assertThatThrownBy(() -> service.ingest("alice", "vanguard.pdf", pdf("Holdings: AAPL 10")))
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(InvalidPortfolioDocumentException.class);

		verifyNoInteractions(store);
	}

	@Test
	void rejectionMessage_isSafeToShowTheUser() throws Exception {
		when(extraction.extract(anyString())).thenReturn(List.of());

		assertThatThrownBy(() -> service.ingest("alice", "x.pdf", pdf("Account summary")))
				.hasMessageNotContaining("Exception")
				.hasMessageNotContaining("alice");
	}
}
