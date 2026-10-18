package com.wealth.advisor.memory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The 25 GICS industry groups, each inside one {@link Sector}: the narrower level of the exclusion
 * vocabulary ("no banks" is the group Banks, not the whole of Financials). Excluding a sector covers
 * all of its groups; excluding a group does not cover the sector.
 *
 * <p>Taken from the 2023 GICS structure; check against the current standard before relying on it.
 */
public enum IndustryGroup implements Labeled {
	ENERGY("Energy", Sector.ENERGY),
	MATERIALS("Materials", Sector.MATERIALS),
	CAPITAL_GOODS("Capital Goods", Sector.INDUSTRIALS),
	COMMERCIAL_PROFESSIONAL_SERVICES("Commercial & Professional Services", Sector.INDUSTRIALS),
	TRANSPORTATION("Transportation", Sector.INDUSTRIALS),
	AUTOMOBILES_COMPONENTS("Automobiles & Components", Sector.CONSUMER_DISCRETIONARY),
	CONSUMER_DURABLES_APPAREL("Consumer Durables & Apparel", Sector.CONSUMER_DISCRETIONARY),
	CONSUMER_SERVICES("Consumer Services", Sector.CONSUMER_DISCRETIONARY),
	CONSUMER_DISCRETIONARY_RETAIL("Consumer Discretionary Distribution & Retail", Sector.CONSUMER_DISCRETIONARY),
	CONSUMER_STAPLES_RETAIL("Consumer Staples Distribution & Retail", Sector.CONSUMER_STAPLES),
	FOOD_BEVERAGE_TOBACCO("Food, Beverage & Tobacco", Sector.CONSUMER_STAPLES),
	HOUSEHOLD_PERSONAL_PRODUCTS("Household & Personal Products", Sector.CONSUMER_STAPLES),
	HEALTH_CARE_EQUIPMENT_SERVICES("Health Care Equipment & Services", Sector.HEALTH_CARE),
	PHARMA_BIOTECH_LIFE_SCIENCES("Pharmaceuticals, Biotechnology & Life Sciences", Sector.HEALTH_CARE),
	BANKS("Banks", Sector.FINANCIALS),
	FINANCIAL_SERVICES("Financial Services", Sector.FINANCIALS),
	INSURANCE("Insurance", Sector.FINANCIALS),
	SOFTWARE_SERVICES("Software & Services", Sector.INFORMATION_TECHNOLOGY),
	TECHNOLOGY_HARDWARE_EQUIPMENT("Technology Hardware & Equipment", Sector.INFORMATION_TECHNOLOGY),
	SEMICONDUCTORS("Semiconductors & Semiconductor Equipment", Sector.INFORMATION_TECHNOLOGY),
	TELECOMMUNICATION_SERVICES("Telecommunication Services", Sector.COMMUNICATION_SERVICES),
	MEDIA_ENTERTAINMENT("Media & Entertainment", Sector.COMMUNICATION_SERVICES),
	UTILITIES("Utilities", Sector.UTILITIES),
	EQUITY_REITS("Equity Real Estate Investment Trusts (REITs)", Sector.REAL_ESTATE),
	REAL_ESTATE_MANAGEMENT("Real Estate Management & Development", Sector.REAL_ESTATE);

	private final String label;
	private final Sector sector;

	IndustryGroup(String label, Sector sector) {
		this.label = label;
		this.sector = sector;
	}

	@Override
	public String label() {
		return label;
	}

	public Sector sector() {
		return sector;
	}

	public static Optional<IndustryGroup> fromLabel(String label) {
		return Labeled.find(values(), label);
	}

	public static List<String> labels() {
		return Arrays.stream(values()).map(IndustryGroup::label).toList();
	}
}
