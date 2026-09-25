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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
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
   * Reduces the configured {@code app.security.jwt.expected-audiences} to its non-blank entries —
   * the list the audience validator enforces, and the one the gate-posture gauge reports on. An
   * empty result means the audience check is off.
   *
   * @param configured the raw bound list, possibly {@code null} or holding blank entries
   * @return the effective audiences, never {@code null}
   */
  public static @NotNull @Unmodifiable List<String> effectiveAudiences(
      @Nullable List<String> configured) {
    return configured == null
        ? List.of()
        : configured.stream().filter(StringUtils::hasText).map(String::trim).toList();
  }

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
   * <p><strong>The audience is {@code basetool-ingest}, deliberately NOT the backend's {@code
   * basetool-backend}.</strong> Every {@code basetool-frontend} session token carries {@code
   * basetool-backend}, so checking it here would admit exactly the tokens this interface exists to
   * refuse (ADR-0018 amendment 1, REQ-INGEST-011). Set it only once the realm's {@code
   * extractor-ingest-only} scope actually stamps {@code basetool-ingest} (see {@code
   * docs/INGEST_KEYCLOAK_SETUP.md} step 7a). The two modules checking different values is the
   * point; it is not a copy-paste omission. Nor does the gateway inherit the backend's value by
   * forwarding anything: since ADR-0129 the caller's token stops here and the backend is called
   * with the gateway's own.
   *
   * @param issuerUri the configured Keycloak issuer location (used for {@code iss} validation)
   * @param jwkSetUri the internal JWKS URL, or blank to derive keys from the issuer location
   * @param expectedAudiences the configured {@code app.security.jwt.expected-audiences}; blank
   *     entries are ignored and an empty list leaves the audience unchecked
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
      @Value("${app.security.jwt.expected-audiences:}") List<String> expectedAudiences,
      SslBundles sslBundles) {
    NimbusJwtDecoder decoder = buildDecoder(issuerUri, jwkSetUri, sslBundles);
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
    List<String> audiences = effectiveAudiences(expectedAudiences);
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
  static NimbusJwtDecoder buildDecoder(String issuerUri, String jwkSetUri, SslBundles sslBundles) {
    if (!StringUtils.hasText(jwkSetUri)) {
      return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
    }
    NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
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
  @NotNull
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
   * @param ingestProperties supplies the gateway's public origin, used as the DPoP {@code htu}
   *     comparison target so it does not depend on the reverse proxy's forwarded headers
   * @return the configured filter chain
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      LoggingProperties loggingProperties,
      ClientIdentityProperties clientIdentityProperties,
      IngestProperties ingestProperties)
      throws Exception {
    SecurityProblemResponseHandler securityProblems =
        new SecurityProblemResponseHandler(objectMapper, meterRegistry, loggingProperties);
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
                    .requestMatchers("/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(securityProblems)
                    .accessDeniedHandler(securityProblems))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .dPoP(
                        dpop ->
                            dpop.authenticationConverter(
                                    new PublicUriDpopAuthenticationConverter(
                                        ingestProperties.publicBaseUrl()))
                                .authenticationFailureHandler(
                                    new org.springframework.security.web.authentication
                                        .AuthenticationEntryPointFailureHandler(securityProblems)))
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(securityProblems)
                    .accessDeniedHandler(securityProblems))
        .addFilterBefore(
            new IdentityProviderUnavailableFilter(objectMapper, meterRegistry, loggingProperties),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .addFilterAfter(
            new UserIdMdcFilter(loggingProperties),
            org.springframework.security.web.authentication.AuthenticationFilter.class)
        .addFilterAfter(
            new ClientIdentityFilter(
                clientIdentityProperties, meterRegistry, objectMapper, loggingProperties),
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
  @NotNull
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
