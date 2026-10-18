package com.wealth.advisor.web.dto;

import com.wealth.advisor.memory.IndustryGroup;
import com.wealth.advisor.memory.PreferenceCategory;
import com.wealth.advisor.memory.Sector;
import java.util.Arrays;
import java.util.List;

/**
 * What the panel needs to build its add form: the categories, and the fixed lists that the sector and
 * industry-group categories must pick from.
 */
public record PreferenceOptions(List<CategoryOption> categories, List<String> sectors, List<String> industryGroups) {

	public record CategoryOption(String name, String title) {}

	public static PreferenceOptions all() {
		return new PreferenceOptions(
				Arrays.stream(PreferenceCategory.values()).map(c -> new CategoryOption(c.name(), c.title())).toList(),
				Sector.labels(),
				IndustryGroup.labels());
	}
}
