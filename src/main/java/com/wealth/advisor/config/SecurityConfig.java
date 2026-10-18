package com.wealth.advisor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;

/**
 * FR-1: form login + HttpOnly {@code JSESSIONID} cookie, per-request authentication check on
 * every other endpoint (this is what gives {@link com.wealth.advisor.web.ChatController} a real,
 * per-user identity instead of a client-supplied one).
 *
 * <p>What actually happens on a request, in order:
 *
 * <ol>
 *   <li>Unauthenticated request to anything except {@code /login.html}, {@code /signup.html},
 *       {@code /api/auth/login}, {@code /api/auth/register}, or the static {@code /css/**}/
 *       {@code /js/**} assets those two pages load → Spring Security redirects to {@code
 *       /login.html}, <strong>except</strong> under {@code /api/**}, which gets a plain {@code
 *       401} instead. Without that split, an expired session hitting {@code fetch('/api/chat',
 *       ...)} would silently follow the redirect to {@code /login.html} (which itself returns
 *       {@code 200}) — {@code fetch} reports that as a successful response, so the frontend has no
 *       way to tell "the server did the thing" from "the server sent me the login page back."
 *       Found live: an uploaded document reported success but never showed up in the documents
 *       list, because the session had expired between the upload and the list refresh.
 *   <li>New user: browser POSTs JSON to {@code /api/auth/register} ({@code signup.html}'s fetch
 *       call) → {@code RegistrationController} creates the Firestore user doc, then the client
 *       redirects to {@code /login.html} to actually authenticate — registering doesn't log you
 *       in by itself.
 *   <li>Browser POSTs {@code username}/{@code password} to {@code /api/auth/login} (a plain HTML
 *       form, no JS) → Spring Security's form-login filter intercepts it, calls {@link
 *       FirestoreUserDetailsService} to load the user and check the password.
 *   <li>On success: a {@code JSESSIONID} cookie is set and the browser is redirected to
 *       {@code /index.html}. On failure: back to {@code /login.html}.
 *   <li>Every later request carries that cookie; Spring Security resolves it back to an {@link
 *       org.springframework.security.core.Authentication}, which {@code ChatController} and
 *       {@code PortfolioController} read via {@code Authentication.getName()} to get the real
 *       username — never trusting a client-supplied id.
 *   <li>{@code POST /api/auth/logout} clears the session and deletes the cookie.
 * </ol>
 *
 * <p>CSRF protection is off here, same tradeoff the original design doc called out: the session
 * cookie is issued with {@code SameSite=Lax} (see {@code application.properties}), which blocks
 * the cross-site request forgery scenarios CSRF tokens exist to prevent for a same-origin app
 * like this one. Revisit with a cookie-based CSRF token (e.g. {@code CookieCsrfTokenRepository})
 * if this ever serves requests cross-origin.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				// See class javadoc for why this is safe to disable in this app's shape.
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(
						auth ->
								auth.requestMatchers(
												"/login.html",
												"/api/auth/login",
												"/signup.html",
												"/api/auth/register",
												"/css/**",
												"/js/**")
										.permitAll()
										.anyRequest()
										.authenticated())
				.formLogin(
						form ->
								form.loginPage("/login.html")
										.loginProcessingUrl("/api/auth/login")
										.defaultSuccessUrl("/index.html", true)
										.permitAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(apiAware401EntryPoint()))
				.logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID"));
		return http.build();
	}

	/**
	 * {@code defaultAuthenticationEntryPointFor(entryPoint, matcher)} turned out not to fall back to
	 * the form-login redirect the way its name implies — every unauthenticated request, including a
	 * direct browser visit to {@code /index.html}, came back {@code 401} instead of the expected
	 * redirect to {@code /login.html} (caught live before this shipped, not assumed from the docs).
	 * Building the {@link DelegatingAuthenticationEntryPoint} by hand instead, with an explicit
	 * default, removes the ambiguity: {@code /api/**} gets {@code 401}, anything else falls through
	 * to the same {@code LoginUrlAuthenticationEntryPoint} {@code formLogin()} would have configured
	 * on its own.
	 */
	private static AuthenticationEntryPoint apiAware401EntryPoint() {
		RequestMatcher apiRequests = PathPatternRequestMatcher.pathPattern("/api/**");
		RequestMatcherEntry<AuthenticationEntryPoint> apiEntry =
				new RequestMatcherEntry<>(apiRequests, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
		return new DelegatingAuthenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login.html"), apiEntry);
	}
}
