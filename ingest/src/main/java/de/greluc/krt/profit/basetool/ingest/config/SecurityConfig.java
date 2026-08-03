/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.ingest.config;

import de.greluc.krt.profit.basetool.ingest.filter.ClientIdentityFilter;
import de.greluc.krt.profit.basetool.ingest.filter.UserIdMdcFilter;
import de.greluc.krt.profit.basetool.ingest.web.SecurityProblemResponseHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Security configuration for the ingest gateway: a pure JWT-bearer resource server. There is no
 * session and no HTML, so the posture is deliberately minimal — stateless sessions, CSRF kept
 * enabled but ignored for the bearer-only {@code /v1/**} endpoints (no weaker than the backend),
 * empty CORS, and a {@code default-src 'none'} CSP (REQ-INGEST-001/-002).
 *
 * <p>Authorization is intentionally coarse: every ingest endpoint requires only an authenticated
 * caller ({@code isAuthenticated()}, enforced both here and by method-level {@code @PreAuthorize}),
 * mirroring the backend's import endpoints (REQ-REFINERY-011). The optional {@code aud} check below
 * is the resource-server defence-in-depth knob.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /**
   * Expected JWT {@code aud} values for the opt-in audience check. Empty by default → no audience
   * enforcement (signature/issuer/expiry still apply). Set {@code
   * app.security.jwt.expected-audiences=basetool-backend} to require it — but only once the realm's
   * clients actually emit that audience (see {@code docs/INGEST_KEYCLOAK_SETUP.md}); the same value
   * the backend uses, because the gateway forwards the same bearer to the backend.
   */
  @Value("${app.security.jwt.expected-audiences:}")
  private List<String> expectedAudiences;

  /**
   * Custom resource-server {@link JwtDecoder}, created ONLY when at least one hardening knob is
   * set: {@code app.security.jwt.expected-audiences} (opt-in {@code aud} enforcement) and/or {@code
   * app.security.jwt.jwk-set-uri} (opt-in: fetch the JWKS from the INTERNAL Keycloak so token
   * validation no longer hairpins through the public edge — REQ-SEC-024). When neither is set the
   * bean is absent and Spring Boot's auto-configured, lazily-fetching decoder is used unchanged, so
   * the default behaviour — including the {@code test} profile's placeholder issuer — is untouched.
   *
   * <p>The validator chain is identical to the auto-config default plus the optional audience
   * check: signature + issuer + timestamp via {@link JwtValidators#createDefaultWithIssuer(String)}
   * — the {@code iss} claim is still validated against the PUBLIC issuer Keycloak stamps into
   * tokens, so split-horizon JWKS (public {@code iss}, internal key fetch) is transparent — and the
   * {@code aud} validator only when non-blank audiences are configured.
   *
   * @param issuerUri the configured Keycloak issuer location (used for {@code iss} validation)
   * @param jwkSetUri the internal JWKS URL, or blank to derive keys from the issuer location
   * @param sslBundles the registered SSL bundles, consulted for the {@code keycloak-trust} pin when
   *     an internal {@code jwkSetUri} is used
   * @return a Nimbus decoder wired for the configured hardening knobs
   */
  @Bean
  @ConditionalOnExpression(
      "!'${app.security.jwt.expected-audiences:}'.isBlank()"
          + " or !'${app.security.jwt.jwk-set-uri:}'.isBlank()")
  JwtDecoder resourceServerJwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
      @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
      SslBundles sslBundles) {
    NimbusJwtDecoder decoder = buildDecoder(issuerUri, jwkSetUri, sslBundles);
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
    List<String> audiences = expectedAudiences.stream().filter(StringUtils::hasText).toList();
    if (!audiences.isEmpty()) {
      validators.add(audienceValidator(audiences));
    }
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }

  /**
   * Builds the underlying {@link NimbusJwtDecoder} for {@link #resourceServerJwtDecoder}. With a
   * blank {@code jwkSetUri} it reproduces the auto-config exactly ({@link
   * NimbusJwtDecoder#withIssuerLocation(String)}). With an internal {@code jwkSetUri} it fetches
   * keys from that URL over a {@link KeycloakTrustSupport}-pinned client so the self-signed
   * internal Keycloak certificate is trusted; when no {@code keycloak-trust} bundle is registered
   * (dev/test) it falls back to the default client.
   *
   * @param issuerUri the Keycloak issuer location
   * @param jwkSetUri the internal JWKS URL, or blank for issuer-location discovery
   * @param sslBundles the registered SSL bundles
   * @return the Nimbus decoder (validators are attached by the caller)
   */
  // Package-private (not private) so SecurityConfigInternalJwksDecoderTest can assert the
  // internal-JWKS path accepts a non-RS256 (ES256) token — the REQ-SEC-024 algorithm-set fix.
  static NimbusJwtDecoder buildDecoder(String issuerUri, String jwkSetUri, SslBundles sslBundles) {
    if (!StringUtils.hasText(jwkSetUri)) {
      return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
    }
    NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            // withJwkSetUri defaults to RS256-ONLY, whereas withIssuerLocation derives the accepted
            // algorithm set from the live JWKS. Restore the full asymmetric set so enabling
            // internal
            // JWKS cannot 401 every token the moment the realm signs with PS*/ES* (REQ-SEC-024).
            // SignatureAlgorithm carries only asymmetric algorithms (no HMAC), so widening it
            // cannot
            // open an algorithm-confusion attack — the signature is still verified against the JWK.
            .jwsAlgorithms(
                algorithms -> algorithms.addAll(EnumSet.allOf(SignatureAlgorithm.class)));
    ClientHttpRequestFactory trusted =
        KeycloakTrustSupport.trustedRequestFactory(
            sslBundles, KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);
    if (trusted != null) {
      builder.restOperations(new RestTemplate(trusted));
    }
    return builder.build();
  }

  /**
   * Builds the {@code aud}-claim validator: a token passes only when its {@code aud} list
   * intersects {@code expectedAudiences}. Package-private + static so it is unit-testable without a
   * Spring context.
   *
   * @param expectedAudiences the accepted audience values; an empty set matches no token
   * @return a validator that errors unless the JWT's {@code aud} intersects the expected set
   */
  static OAuth2TokenValidator<Jwt> audienceValidator(List<String> expectedAudiences) {
    return new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, aud -> aud != null && !Collections.disjoint(aud, expectedAudiences));
  }

  /**
   * The single {@link SecurityFilterChain}: CSRF enabled but ignored for the bearer-only {@code
   * /v1/**} endpoints, empty CORS, locked-down response headers, the authorization matrix, JWT
   * resource-server activation, the identity-provider-unavailable 503 re-map and a stateless
   * session policy.
   *
   * @param http the Spring Security builder
   * @param objectMapper serializes the {@link IdentityProviderUnavailableFilter}'s 503 problem body
   * @param meterRegistry counts the identity-provider-unavailable 503 on {@code
   *     basetool_http_error_total} (REQ-OBS-011)
   * @param loggingProperties supplies the MDC key the {@link UserIdMdcFilter} writes the
   *     authenticated subject to
   * @param clientIdentityProperties the configured client-identity gate (REQ-INGEST-011)
   * @return the configured filter chain
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      LoggingProperties loggingProperties,
      ClientIdentityProperties clientIdentityProperties)
      throws Exception {
    // CSRF stays ENABLED (never disabled) so the gateway carries no weaker posture than the
    // backend. Every real endpoint (/v1/**) is JSON + bearer-token only on a stateless chain with
    // no session cookie, so it can never be driven from a CSRF-vulnerable browser flow — those
    // paths are ignored exactly like the backend's bearer API. The cookie repository never issues a
    // session, and no other state-changing browser endpoint exists, so the CSRF machinery is inert
    // here while keeping the static-analysis posture clean.
    SecurityProblemResponseHandler securityProblems =
        new SecurityProblemResponseHandler(objectMapper, meterRegistry);
    CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepo.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(true));
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/v1/**"))
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(
            headers -> {
              // The gateway serves only JSON — no document context exists, so every fetch
              // directive inherits 'none'. frame-ancestors/base-uri/form-action are
              // defence-in-depth against an injected document.
              headers.contentSecurityPolicy(
                  csp ->
                      csp.policyDirectives(
                          "default-src 'none'; frame-ancestors 'none'; base-uri 'none';"
                              + " form-action 'none'"));
              headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
              headers.httpStrictTransportSecurity(
                  hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
            })
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // springdoc serves /v3/api-docs in non-prod only (prod sets api-docs.enabled
                    // = false → 404); harmless to permit here.
                    .requestMatchers("/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // REQ-API-004: give the filter-level 401/403 the same problem+json shape (stable `code` +
        // `correlationId`) as every other ingest error, and a log line — Spring Security's defaults
        // answer with an empty body and log nothing. Installed BOTH globally and on the resource
        // server: the latter has its own entry point which would otherwise win for bearer requests,
        // which is every real request here.
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(securityProblems)
                    .accessDeniedHandler(securityProblems))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    // Bearer only — NO `dPoP(...)` here, deliberately (REQ-INGEST-012).
                    //
                    // Accepting a DPoP-bound access token at this gateway is architecturally wrong,
                    // and enabling it broke every blueprint send on 2026-08-03. The gateway is a
                    // RELAY: it forwards the caller's own token onward to the backend
                    // (BackendImportClient#commonHeaders). A sender-constrained token is bound to
                    // the CLIENT's key and to the `htu` of THIS endpoint, so the second hop can
                    // neither carry a proof nor be covered by the first one — the backend receives
                    // a token issued for DPoP as a plain bearer and refuses it. And even where it
                    // did not refuse, the binding would end at the gateway, which is exactly where
                    // it would need to hold.
                    //
                    // DPoP is still used, one layer up and invisible here: a realm client policy
                    // binds the REFRESH token — the long-lived credential the extractor persists to
                    // disk (REQ-INGEST-007) — while access tokens stay plain bearer and relay
                    // cleanly.
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(securityProblems)
                    .accessDeniedHandler(securityProblems))
        // REQ-SEC-024: re-map an identity-provider-unreachable failure (JWKS timeout / 5xx /
        // Docker-DNS strand) escaping the bearer-token filter as a re-thrown
        // AuthenticationServiceException to a retryable 503 instead of an opaque 500. Installed
        // before the bearer-token filter so its try/catch wraps that filter; a genuine 401 never
        // reaches it.
        .addFilterBefore(
            new IdentityProviderUnavailableFilter(objectMapper, meterRegistry),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        // REQ-OBS-001/-002: refine the `userId` MDC field from `anonymous` to the caller's JWT
        // `sub`. Installed AFTER the bearer-token filter — that is the first point at which the
        // SecurityContext is populated; the shared servlet filters all run earlier and would only
        // ever see an empty context. CorrelationIdFilter seeds and clears the key (see its
        // Javadoc).
        .addFilterAfter(
            new UserIdMdcFilter(loggingProperties),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        // REQ-INGEST-011: the client-identity gate (azp allowlist + ingest scope).
        // Installed AFTER UserIdMdcFilter, not merely after the bearer filter, so its WARN lines
        // already carry the acting subject in the `userId` MDC field and never have to repeat it
        // (REQ-OBS-002/-004). Every check inside is inert until configured, so this is a no-op on a
        // deployment that has not run the Keycloak setup yet.
        .addFilterAfter(
            new ClientIdentityFilter(clientIdentityProperties, meterRegistry, objectMapper),
            UserIdMdcFilter.class)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  /**
   * CORS source: empty allowlist, {@code allowCredentials=false}. The gateway is called by a native
   * desktop app (no browser origin) and by no browser directly, so cross-origin browser traffic is
   * rejected outright — combined with the bearer-only model this closes the open-CORS-with-creds
   * failure mode.
   *
   * @return a CORS source applied to all paths
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(List.of());
    configuration.setAllowedMethods(List.of("POST", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Accept", "Accept-Language", "X-Correlation-Id"));
    configuration.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
