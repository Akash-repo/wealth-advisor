package com.wealth.advisor.portfolio;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Reads the text out of an uploaded PDF. Anything that can't yield text is reported as an {@link
 * InvalidPortfolioDocumentException} with a message the end user can act on.
 */
@Component
public class PdfTextReader {

	public String read(byte[] pdfBytes) {
		String text = extract(pdfBytes);
		if (text.isBlank()) {
			throw new InvalidPortfolioDocumentException(
					"No readable text was found in that PDF (is it a scanned image?). "
							+ "Please upload a statement with selectable text.");
		}
		return text;
	}

	private static String extract(byte[] pdfBytes) {
		try (PDDocument document = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(document);
		} catch (IOException e) {
			throw new InvalidPortfolioDocumentException(
					"That file couldn't be read as a PDF. It may be corrupted or password protected.");
		}
	}
}
