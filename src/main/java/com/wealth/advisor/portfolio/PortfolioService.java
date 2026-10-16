package com.wealth.advisor.portfolio;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Accepts, lists and removes a user's <strong>portfolio statements</strong>.
 *
 * <p>An upload is a pipeline of three small steps, each in its own class: read the PDF's text
 * ({@link PdfTextReader}), extract the holdings ({@link HoldingsExtractionService}), save them
 * ({@link PortfolioStatementStore}). Only statements are accepted: a file with no holdings in it (a
 * resume, a bank statement, a scanned image) is rejected with {@link
 * InvalidPortfolioDocumentException} <em>before anything is stored</em>, so a bad upload leaves no
 * data behind. Several statements per user, one per account, are supported.
 *
 * <pre>{@code
 * int holdings = portfolioService.ingest("demo", "vanguard.pdf", pdfBytes);
 * }</pre>
 */
@Service
public class PortfolioService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

	private final PdfTextReader pdfTextReader;
	private final HoldingsExtractionService holdingsExtractionService;
	private final PortfolioStatementStore store;

	public PortfolioService(
			PdfTextReader pdfTextReader,
			HoldingsExtractionService holdingsExtractionService,
			PortfolioStatementStore store) {
		this.pdfTextReader = pdfTextReader;
		this.holdingsExtractionService = holdingsExtractionService;
		this.store = store;
	}

	/**
	 * @return the number of holdings stored
	 * @throws InvalidPortfolioDocumentException if the file isn't a readable portfolio statement;
	 *     nothing is stored in that case
	 */
	public int ingest(String userId, String filename, byte[] pdfBytes) {
		log.info("Ingesting statement '{}' ({} bytes) for userId='{}'", filename, pdfBytes.length, userId);
		String text = pdfTextReader.read(pdfBytes);
		List<Holding> holdings = requireHoldings(holdingsExtractionService.extract(text), userId, filename);
		store.save(userId, filename, holdings);
		log.info("Stored statement '{}' for userId='{}': {} holding(s)", filename, userId, holdings.size());
		return holdings.size();
	}

	public List<Map<String, Object>> listStatements(String userId) {
		return store.list(userId);
	}

	public void deleteStatement(String userId, String filename) {
		store.delete(userId, filename);
		log.info("Deleted statement '{}' for userId='{}'", filename, userId);
	}

	private static List<Holding> requireHoldings(List<Holding> holdings, String userId, String filename) {
		if (holdings.isEmpty()) {
			log.info("Rejected '{}' for userId='{}': no holdings found", filename, userId);
			throw new InvalidPortfolioDocumentException(
					"This doesn't look like a portfolio statement: no holdings were found in it. "
							+ "Please upload a brokerage or portfolio statement PDF.");
		}
		return holdings;
	}
}
