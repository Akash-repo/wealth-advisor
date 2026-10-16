package com.wealth.advisor.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.firestore.Firestore;
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
 * Uploads are portfolio statements only. A rejected file must cost at most one holdings-extraction
 * call and must leave nothing behind — no embeddings requested, no Firestore writes — so these
 * tests assert on what was <em>not</em> touched, not just on the exception.
 */
class PortfolioIngestionServiceTest {

	private Firestore firestore;
	private EmbeddingService embeddingService;
	private HoldingsExtractionService holdingsExtractionService;
	private PortfolioIngestionService service;

	@BeforeEach
	void setUp() {
		firestore = mock(Firestore.class);
		embeddingService = mock(EmbeddingService.class);
		holdingsExtractionService = mock(HoldingsExtractionService.class);
		service = new PortfolioIngestionService(firestore, embeddingService, holdingsExtractionService);
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
	void notAPdf_isRejectedAndNothingIsTouched() {
		byte[] notPdf = "just some text, not a pdf".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> service.ingest("alice", "notes.txt", notPdf))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("PDF");

		verifyNoInteractions(holdingsExtractionService, embeddingService, firestore);
	}

	@Test
	void pdfWithNoText_isRejectedAndNothingIsTouched() throws Exception {
		byte[] blank = pdf(null);

		assertThatThrownBy(() -> service.ingest("alice", "scan.pdf", blank))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("No readable text");

		verifyNoInteractions(holdingsExtractionService, embeddingService, firestore);
	}

	@Test
	void documentWithNoHoldings_isRejectedBeforeAnythingIsEmbeddedOrStored() throws Exception {
		byte[] resume = pdf("Jane Doe - Senior Engineer - 10 years of experience");
		when(holdingsExtractionService.extract(anyString())).thenReturn(List.of());

		assertThatThrownBy(() -> service.ingest("alice", "resume.pdf", resume))
				.isInstanceOf(InvalidPortfolioDocumentException.class)
				.hasMessageContaining("portfolio statement");

		verify(holdingsExtractionService).extract(anyString());
		verifyNoInteractions(embeddingService, firestore);
	}

	@Test
	void rejectionMessage_isSafeToShowTheUser() throws Exception {
		when(holdingsExtractionService.extract(anyString())).thenReturn(List.of());

		assertThatThrownBy(() -> service.ingest("alice", "x.pdf", pdf("Account summary")))
				.hasMessageNotContaining("Exception")
				.hasMessageNotContaining("alice");
	}
}
