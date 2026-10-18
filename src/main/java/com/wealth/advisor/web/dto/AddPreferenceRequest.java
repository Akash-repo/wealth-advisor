package com.wealth.advisor.web.dto;

import com.wealth.advisor.memory.PreferenceCategory;
import com.wealth.advisor.memory.PreferenceValues;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A preference the user types in; the value is validated against its category by the service. */
public record AddPreferenceRequest(
		@NotNull(message = "category is required") PreferenceCategory category,
		@NotBlank(message = "value must not be blank")
				@Size(max = PreferenceValues.MAX_LENGTH, message = "value must be at most " + PreferenceValues.MAX_LENGTH + " characters")
				String value) {}
