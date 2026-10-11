package com.wealth.advisor.agent.prompt;

/**
 * An agent instruction loaded from {@code resources/prompts/<name>.txt}.
 *
 * @param name the file's base name, e.g. {@code portfolio_analysis}
 * @param text the instruction exactly as written in the file (ADK substitutes {@code {key}}
 *     placeholders from session state at run time)
 * @param version a short, stable fingerprint of {@code text}; it changes whenever the prompt does,
 *     so a logged turn can be tied to the exact wording that produced it
 */
public record Prompt(String name, String text, String version) {}
