package com.wealth.advisor.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Converts a plain-text eval fixture into real PDF bytes at test run time, using PDFBox's own
 * write API — the same library {@code PortfolioIngestionService} uses to <em>read</em> uploaded
 * PDFs, so generating fixtures doesn't need a second PDF dependency. See
 * {@code src/test/resources/eval-fixtures/README.md} for what each fixture is designed to prove,
 * and {@code ARCHITECTURE.md}'s eval-design discussion for why the PDF is generated fresh every
 * run instead of a binary committed to git: the checked-in {@code .txt} is the only source of
 * truth, so it can never drift out of sync with a stale generated copy, and it works identically
 * on any CI runner — unlike the macOS-only {@code cupsfilter} used for ad hoc manual testing
 * earlier in this project's history, which isn't available on a typical Linux CI runner.
 *
 * <p>Deliberately <strong>not</strong> a Spring bean, unlike {@link JudgeService} — it has nothing
 * to inject (no Firestore, no Gemini client, no external dependency at all), just a pure text-in,
 * PDF-bytes-out transformation, so dependency injection wouldn't buy anything here.
 *
 * <p>Intentionally minimal: single page, no line wrapping, plain Helvetica. Correct for eval
 * fixtures, which are short and entirely within this project's own control — not meant to become a
 * general-purpose PDF generator just because it would be convenient to extend.
 */
public final class PdfFixtures {

	private static final String FIXTURE_DIR = "eval-fixtures/";
	private static final float FONT_SIZE = 11;
	private static final float LINE_HEIGHT = 14;
	private static final float LEFT_MARGIN = 50;
	private static final float TOP_MARGIN = 700;

	private PdfFixtures() {}

	/**
	 * @param fixtureName e.g. {@code "vanguard"} — loads {@code eval-fixtures/vanguard.txt} from
	 *     the test classpath and returns it converted to real, parseable PDF bytes, ready to POST
	 *     through {@code /api/portfolio/upload} exactly like a real uploaded file
	 */
	public static byte[] load(String fixtureName) {
		return toPdfBytes(readClasspathText(FIXTURE_DIR + fixtureName + ".txt"));
	}

	private static String readClasspathText(String classpathPath) {
		try (InputStream in = PdfFixtures.class.getClassLoader().getResourceAsStream(classpathPath)) {
			if (in == null) {
				throw new IllegalArgumentException("No such eval fixture on the classpath: " + classpathPath);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read eval fixture: " + classpathPath, e);
		}
	}

	private static byte[] toPdfBytes(String text) {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
				stream.beginText();
				stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
				stream.newLineAtOffset(LEFT_MARGIN, TOP_MARGIN);
				for (String line : text.split("\n")) {
					stream.showText(line);
					stream.newLineAtOffset(0, -LINE_HEIGHT);
				}
				stream.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to generate fixture PDF for text", e);
		}
	}
}
