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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberAuthorities;
import de.greluc.krt.profit.basetool.backend.support.ClientAttribution;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import de.greluc.krt.profit.basetool.backend.support.RefusedSubjectWindow;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.TermsConsentCheck;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Backend security configuration: JWT resource-server, role hierarchy, CSRF policy and the request
 * authorization matrix.
 *
 * <p>The backend is a pure resource server — incoming JWTs are validated against the Keycloak
 * issuer, the {@code CustomJwtGrantedAuthoritiesConverter} maps both Keycloak realm roles AND the
 * project-specific {@code is_logistician} / {@code is_mission_manager} flags on the caller's {@code
 * org_unit_membership} rows into Spring authorities. The role hierarchy mirrors the CLAUDE.md
 * matrix (admin/officer imply logistician/mission-manager).
 *
 * <p>The {@code authorizeHttpRequests} matrix in {@link #filterChain} is the single, exhaustive
 * source for which endpoints are public, which require authentication, and which require a specific
 * role/authority. The order matters — Spring evaluates the matchers top-down. Method-level
 * {@code @PreAuthorize} on services adds fine-grained checks but never weakens the chain matcher.
 *
 * <p><strong>Four paths answer without a token, and that list is the requirement</strong>
 * (REQ-SEC-052, ADR-0159): {@code /error}, {@code /actuator/health(/**)}, {@code /internal/**} —
 * machine-to-machine behind a constant-time shared-secret header, not an anonymous data path — and
 * the two {@code GET}-scoped reads {@code /api/v1/terms/document} and {@code
 * /api/v1/app/version-policy}. Everything else requires authentication at this layer <em>and</em> a
 * method gate; a {@code HEAD} on either read falls to the catch-all and answers {@code 401},
 * because the rules name the verb.
 *
 * <p>An authenticated caller is still not admitted by default. A token whose realm roles map to no
 * application role is refused with {@code 403 NO_ROLE} (REQ-SEC-053) before it reaches a handler,
 * on the JWT path and on the ingest gateway's acting-member path alike. There is no role below
 * member: the {@code GUEST} role was removed with {@code V239}.
 *
 * <p>CSRF is enabled with the cookie token repository except in the {@code test} profile, where it
 * is disabled so MockMvc tests do not need to plumb the token through every call. API endpoints
 * that are exclusively JSON and bearer-token authenticated ({@code /api/v1/missions/**}, {@code
 * /api/v1/operations/**}, {@code /api/v1/orders}, {@code /api/v1/finance-entries}) are explicitly
 * ignored because they can never be triggered from a CSRF-vulnerable browser flow.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  /**
   * Re-arms the consent gate under the {@code test} profile for a single test class.
   *
   * <p>Only honoured when the {@code test} profile is active; production is armed regardless.
   */
  static final String TERMS_GATE_ARMED_IN_TEST = "app.security.terms.armed-in-test";

  /**
   * Paths exempt from cookie-based CSRF, because on them there is no ambient credential for a
   * cross-site request to ride.
   *
   * <p>This chain is {@link
   * org.springframework.security.config.http.SessionCreationPolicy#STATELESS} and authenticates
   * with nothing but a bearer JWT — no form login, no basic auth, no session cookie. CSRF defends
   * against a browser attaching a credential by itself; a bearer token is never attached by itself,
   * so on {@code /api/v1/**} the check can only ever refuse a legitimate client.
   *
   * <p><strong>It did.</strong> The list used to name five paths, and every path outside it
   * answered {@code 403 MissingCsrfToken} to any caller without a CSRF cookie — which is every
   * bearer client, i.e. the whole native app. In production that broke booking stock out of the
   * Lager ({@code POST /api/v1/inventory/{id}/book-out}), taking and progressing an Auftrag ({@code
   * /api/v1/orders/{id}/assignees/{userId}}, {@code /status}) and a bank account's balance target —
   * while {@code /api/v1/missions/**} and {@code /api/v1/operations/**}, which were on the list,
   * worked. The nightly {@code edge-deny-probe} named all four: it asserts {@code 401} for an
   * anonymous write and got {@code 403}, because the CSRF filter runs ahead of authorization and
   * answered first.
   *
   * <p>Growing the list per broken endpoint is what produced that shape. The pattern now matches
   * the reason: the whole bearer-only API. {@code /internal/**} keeps its entry —
   * machine-to-machine with its own shared-secret header, also cookie-less (REQ-SEC-022).
   *
   * <p>Package-private so {@code SecurityConfigCsrfExemptionTest} can pin it. The {@code test}
   * profile disables CSRF outright so MockMvc can post, which means no {@code @SpringBootTest} in
   * this repo exercises the production branch at all — that blind spot is why the gap shipped, and
   * a test over this constant is the part of it that can be closed cheaply.
   */
  static final String[] CSRF_EXEMPT_PATHS = {"/api/v1/**", "/internal/**"};

  /**
   * Cross-origin allowlist for the backend API. Empty by default: the backend is only addressed
   * server-side from the Spring-Boot frontend (Thymeleaf SSR), so no direct browser-to-backend
   * cross-origin traffic is expected, and any such call is rejected with HTTP 403. Override in
   * environment-specific YAML when a real browser client on a different origin is introduced (e.g.
   * a future mobile web app on https://mobile.profit-base.online).
   */
  @Value("${app.cors.allowed-origin-patterns:}")
  private List<String> allowedOriginPatterns;

  /**
   * Expected JWT {@code aud} (audience) values for the opt-in audience check (audit L-1). Empty by
   * default → no audience enforcement: the resource server already validates signature, issuer and
   * expiry, and the effective authority comes from realm roles, so requiring {@code aud} is a
   * defense-in-depth knob an operator enables once they know the value their realm issues (a wrong
   * value would reject every token). Set {@code app.security.jwt.expected-audiences} (comma-list)
   * to the backend client / resource id to turn it on. Under the {@code prod} profile it is not
   * optional: {@link JwtAudienceStartupCheck} refuses to start the context while it is blank
   * (APPSEC-08), so "empty = off" holds only for dev, test and e2e.
   */
  @Value("${app.security.jwt.expected-audiences:}")
  private List<String> expectedAudiences;

  /**
   * Custom resource-server {@link JwtDecoder}, created ONLY when at least one hardening knob is
   * set: {@code app.security.jwt.expected-audiences} (opt-in {@code aud} enforcement, audit L-1)
   * and/or {@code app.security.jwt.jwk-set-uri} (opt-in: fetch the JWKS from the INTERNAL Keycloak
   * so token validation no longer hairpins through the public edge — REQ-SEC-024). When neither is
   * set the bean is absent and Spring Boot's auto-configured, lazily-fetching decoder is used
   * unchanged, so the default behaviour — including the {@code test} profile's unreachable
   * placeholder issuer — is untouched (this is what keeps every {@code @SpringBootTest} that does
   * not mock {@code JwtDecoder} green).
   *
   * <p>The validator chain is identical to the auto-config default plus the optional audience
   * check: signature + issuer + timestamp via {@link JwtValidators#createDefaultWithIssuer(String)}
   * — the {@code iss} claim is still validated against the PUBLIC issuer Keycloak stamps into
   * tokens, so split-horizon JWKS (public {@code iss}, internal key fetch) is transparent — and the
   * {@code aud} validator only when non-blank audiences are configured.
   *
   * @param issuerUri the configured Keycloak issuer location (used for {@code iss} validation)
   * @param jwkSetUri the internal JWKS URL, or blank to derive keys from the issuer location as
   *     before
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
   * NimbusJwtDecoder#withIssuerLocation(String)}, an eager discovery fetch). With an internal
   * {@code jwkSetUri} it fetches keys lazily from that URL over a {@link
   * KeycloakTrustSupport}-pinned client so the self-signed internal Keycloak certificate is
   * trusted; when no {@code keycloak-trust} bundle is registered (dev/test) it falls back to the
   * default client, matching {@code KeycloakService}'s behaviour.
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
   * Builds the {@code aud}-claim validator (audit L-1): a token passes only when its {@code aud}
   * list intersects {@code expectedAudiences}. Package-private + static so it is unit-testable
   * without a Spring context.
   *
   * @param expectedAudiences the accepted audience values; never {@code null} (an empty set matches
   *     no token).
   * @return an {@link OAuth2TokenValidator} that errors unless the JWT's {@code aud} intersects the
   *     expected set.
   */
  @NotNull
  static OAuth2TokenValidator<Jwt> audienceValidator(List<String> expectedAudiences) {
    return new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, aud -> aud != null && !Collections.disjoint(aud, expectedAudiences));
  }

  /**
   * Declares the role hierarchy that {@code @PreAuthorize("hasRole('LOGISTICIAN')")} and friends
   * use. Mirrors the matrix in {@code ROLES_AND_PERMISSIONS.md}: admin and officer both imply
   * logistician and mission-manager, so an admin satisfies a {@code @PreAuthorize} for {@code
   * LOGISTICIAN} without being explicitly granted that role.
   *
   * @return the {@link RoleHierarchy} bean consumed by Spring Security's expression handlers
   */
  @Bean
  public static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(
        String.join(
            "\n",
            Roles.authority(Roles.ADMIN) + " > " + Roles.authority(Roles.LOGISTICIAN),
            Roles.authority(Roles.OFFICER) + " > " + Roles.authority(Roles.LOGISTICIAN),
            Roles.authority(Roles.ADMIN) + " > " + Roles.authority(Roles.MISSION_MANAGER),
            Roles.authority(Roles.OFFICER) + " > " + Roles.authority(Roles.MISSION_MANAGER),
            Roles.authority(Roles.ADMIN) + " > " + Roles.authority(Roles.BANK_MANAGEMENT),
            Roles.authority(Roles.BANK_MANAGEMENT) + " > " + Roles.authority(Roles.BANK_EMPLOYEE)));
  }

  /**
   * Wires the project's {@code CustomJwtGrantedAuthoritiesConverter} into Spring Security's
   * standard {@link JwtAuthenticationConverter}, so every authenticated request sees the merged
   * authority set (Keycloak realm roles + DB-flag-derived roles). The parameter is typed as the
   * Spring {@link Converter} interface rather than the concrete {@code service}-package bean, so
   * this {@code config} class does not depend on the {@code service} layer (which would close a
   * {@code config} &harr; {@code service} package cycle); Spring still injects the single matching
   * bean by type.
   *
   * @param customConverter the project-specific authorities converter bean
   * @return wired {@code JwtAuthenticationConverter}
   */
  @NotNull
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter(
      Converter<Jwt, Collection<GrantedAuthority>> customConverter) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(customConverter);
    return converter;
  }

  /**
   * The window the {@code NO_ROLE} refusals are counted into, published as {@link
   * MetricNames#NO_ROLE_REFUSED_SUBJECTS}.
   *
   * <p>Same shape and same 15-minute window as {@link #refusedSubjectWindow(MeterRegistry)}, and a
   * separate instance on purpose: the two answer different questions and one member can be in both
   * (a role-less account that has also not accepted the terms), so sharing a window would report
   * them as one population.
   *
   * @param meterRegistry the registry the gauge is published to
   * @return the window the role gate records refusals into
   */
  @NotNull
  @Bean
  public RefusedSubjectWindow noRoleRefusedSubjectWindow(MeterRegistry meterRegistry) {
    RefusedSubjectWindow window = new RefusedSubjectWindow(Duration.ofMinutes(15), 5_000);
    Gauge.builder(MetricNames.NO_ROLE_REFUSED_SUBJECTS, window, RefusedSubjectWindow::size)
        .description("Distinct subjects the role gate refused with NO_ROLE in the last 15 min.")
        .register(meterRegistry);
    return window;
  }

  /**
   * The sliding window of distinct subjects the consent gate refused, published as the {@code
   * basetool_terms_refused_subjects} gauge (REQ-SEC-028, REQ-OBS-011).
   *
   * <p>15 minutes is chosen against the alert that reads it: long enough that a member who is
   * refused, reads the terms and takes a while to decide stays counted throughout, short enough
   * that the series falls back to zero within one scrape window of a rollout completing. The 5 000
   * cap is roughly two orders of magnitude above the membership — it exists so an
   * internet-reachable refusal path cannot grow the map without bound, not as a functional limit.
   *
   * @param meterRegistry the registry the gauge is published to
   * @return the window the consent filter records refusals into
   */
  @NotNull
  @Bean
  public RefusedSubjectWindow refusedSubjectWindow(MeterRegistry meterRegistry) {
    RefusedSubjectWindow window = new RefusedSubjectWindow(Duration.ofMinutes(15), 5_000);
    Gauge.builder(MetricNames.TERMS_REFUSED_SUBJECTS, window, RefusedSubjectWindow::size)
        .description("Distinct subjects the Terms-of-Use consent gate refused in the last 15 min.")
        .register(meterRegistry);
    return window;
  }

  /**
   * Builds the main {@link SecurityFilterChain}: CSRF policy (profile-dependent), CORS source,
   * security response headers (CSP, X-Frame-Options, Referrer-Policy, Permissions-Policy,
   * X-Content-Type-Options), the request-authorization matrix and JWT resource-server activation.
   *
   * <p>The matrix is profile-independent — same rules for {@code dev} and {@code prod}. Public
   * endpoints (master data, mission-search, guest mission editing) are listed explicitly; every
   * unlisted request falls through to {@code anyRequest().authenticated()}.
   *
   * @param http Spring Security builder
   * @param jwtAuthenticationConverter wired by {@link #jwtAuthenticationConverter}
   * @param env active environment, used to detect the {@code test} profile — which disables CSRF
   *     for MockMvc tests and stands the consent gate down unless {@link #TERMS_GATE_ARMED_IN_TEST}
   *     re-arms it
   * @param securityProblemResponseHandler renders filter-level 401/403 as RFC&nbsp;7807
   *     problem+json (wired as both the entry point and the access-denied handler)
   * @param messageSource localizes the 403 problem bodies of the three refusing filters ({@code
   *     PendingApprovalAccessFilter}, {@code TermsAcceptanceAccessFilter}, {@code
   *     ActingMemberFilter})
   * @param problemResponseFactory assembles the RFC&nbsp;7807 problem body for those filters
   * @param objectMapper serializes those filters' {@code ProblemDetail}s to JSON
   * @param meterRegistry counts the identity-provider-unavailable 503 on {@code
   *     basetool_http_error_total} (REQ-OBS-011)
   * @param noRoleRefusedSubjectWindow the distinct-subject window {@code
   *     PendingApprovalAccessFilter} records its {@code NO_ROLE} refusals into; a separate instance
   *     from the consent one, because a member can be in both populations at once
   * @param clientAttribution bounds the {@code client_id} label of {@code
   *     basetool_api_client_requests_total} (A8, REQ-OBS-018) — the same mapping the audit trail's
   *     client column records (REQ-AUDIT-005)
   * @return the configured security filter chain
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      @NotNull Environment env,
      SecurityProblemResponseHandler securityProblemResponseHandler,
      MessageSource messageSource,
      ProblemResponseFactory problemResponseFactory,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      TermsConsentCheck termsConsentCheck,
      RefusedSubjectWindow refusedSubjectWindow,
      RefusedSubjectWindow noRoleRefusedSubjectWindow,
      IngestGatewayProperties ingestGatewayProperties,
      ActingMemberAuthorities actingMemberAuthorities,
      RateLimitProperties rateLimitProperties,
      ClientAttribution clientAttribution)
      throws Exception {

    boolean isTest = env.matchesProfiles("test");

    boolean armed =
        !isTest || env.getProperty(TERMS_GATE_ARMED_IN_TEST, Boolean.class, Boolean.FALSE);
    TermsConsentCheck effectiveConsentCheck = armed ? termsConsentCheck : userId -> true;

    if (isTest) {
      http.csrf(
          org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
              ::disable);
    } else {
      CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
      csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(true));
      http.csrf(
          csrf ->
              csrf.csrfTokenRepository(csrfRepo)
                  .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                  .ignoringRequestMatchers(CSRF_EXEMPT_PATHS));
    }

    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(
            headers -> {
              headers.contentSecurityPolicy(
                  csp ->
                      csp.policyDirectives(
                          "default-src 'none'; frame-ancestors 'none'; base-uri 'none';"
                              + " form-action 'none'"));
              headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
              headers.referrerPolicy(
                  ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
              headers.crossOriginOpenerPolicy(
                  coop ->
                      coop.policy(
                          org.springframework.security.web.header.writers
                              .CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy
                              .SAME_ORIGIN));
              headers.crossOriginResourcePolicy(
                  corp ->
                      corp.policy(
                          org.springframework.security.web.header.writers
                              .CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy
                              .SAME_ORIGIN));
              headers.httpStrictTransportSecurity(
                  hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
              headers.addHeaderWriter(
                  new StaticHeadersWriter(
                      "Permissions-Policy",
                      "geolocation=(), camera=(), microphone=(), fullscreen=(),"
                          + " payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(),"
                          + " gyroscope=(), magnetometer=(), display-capture=(),"
                          + " clipboard-read=(), clipboard-write=(), interest-cohort=()"));
              headers.contentTypeOptions(Customizer.withDefaults());
            })
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/v3/api-docs*", "/v3/api-docs/**")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/actuator/loggers/**")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers("/internal/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/terms/document")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/app/version-policy")
                    .permitAll()
                    .requestMatchers("/api/v1/users/search", "/api/v1/users/search/references")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER)
                    .requestMatchers(
                        "/api/v1/users/search-bank", "/api/v1/users/search-bank/references")
                    .hasAnyRole(
                        Roles.ADMIN,
                        Roles.OFFICER,
                        Roles.KRT_MEMBER,
                        Roles.BANK_MANAGEMENT,
                        Roles.BANK_EMPLOYEE)
                    .requestMatchers("/api/v1/users/lookup")
                    .hasAnyRole(
                        Roles.ADMIN,
                        Roles.OFFICER,
                        Roles.KRT_MEMBER,
                        Roles.BANK_MANAGEMENT,
                        Roles.BANK_EMPLOYEE)
                    .requestMatchers("/api/v1/users/me", "/api/v1/users/me/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/users")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER)
                    .requestMatchers(HttpMethod.GET, "/api/v1/users/*")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER)
                    .requestMatchers(HttpMethod.PUT, "/api/v1/users/*/attributes")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers(HttpMethod.GET, "/api/v1/users/*/memberships")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER, Roles.BANK_EMPLOYEE)
                    .requestMatchers("/api/v1/users/**")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers(HttpMethod.GET, "/api/v1/hangar/my-ships")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/hangar/ships")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/hangar/ships/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/hangar/ships/*")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/hangar/import/ships")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/hangar/import/fleetview")
                    .authenticated()
                    .requestMatchers("/api/v1/hangar/**")
                    .hasAnyAuthority(
                        Permissions.HANGAR_READ,
                        Permissions.HANGAR_WRITE,
                        Roles.authority(Roles.ADMIN))
                    .requestMatchers(
                        "/api/v1/inventory/my-inventory", "/api/v1/inventory/my-inventory/**")
                    .authenticated()
                    .requestMatchers("/api/v1/inventory", "/api/v1/inventory/**")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.LOGISTICIAN, Roles.KRT_MEMBER)
                    .requestMatchers("/api/v1/personal-inventory", "/api/v1/personal-inventory/**")
                    .authenticated()
                    .requestMatchers("/api/v1/uex/locations/**")
                    .authenticated()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers("/api/v1/bank/admin/**")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers("/api/v1/audit/**")
                    .hasRole(Roles.ADMIN)
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(securityProblemResponseHandler)
                    .accessDeniedHandler(securityProblemResponseHandler))
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .authenticationEntryPoint(securityProblemResponseHandler)
                    .accessDeniedHandler(securityProblemResponseHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
        .addFilterAfter(
            new ApiClientMetricsFilter(clientAttribution, meterRegistry),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .addFilterAfter(
            new ActingMemberFilter(
                ingestGatewayProperties,
                actingMemberAuthorities,
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .addFilterAfter(
            new PendingApprovalAccessFilter(
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry,
                noRoleRefusedSubjectWindow),
            ActingMemberFilter.class)
        .addFilterAfter(
            new TermsAcceptanceAccessFilter(
                effectiveConsentCheck,
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry,
                refusedSubjectWindow),
            PendingApprovalAccessFilter.class)
        .addFilterAfter(
            new SubjectRateLimitingFilter(
                rateLimitProperties,
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry),
            TermsAcceptanceAccessFilter.class)
        .addFilterBefore(
            new IdentityProviderUnavailableFilter(
                messageSource, problemResponseFactory, objectMapper, meterRegistry),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    return http.build();
  }

  /**
   * Per-environment CORS configuration.
   *
   * <p>The allowed origin patterns come from {@code app.cors.allowed-origin-patterns} — empty by
   * default because the only legitimate caller is the Spring-Boot frontend running server-side, NOT
   * a browser. {@code allowCredentials=false} is intentional and load-bearing: combined with a
   * future misconfigured wildcard origin list it would be the difference between a 403 and a CSRF
   * exposure across the entire API.
   *
   * @return CORS source applied to all paths
   */
  @NotNull
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOriginPatterns(allowedOriginPatterns);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "Accept-Language",
            "X-Correlation-Id",
            "X-Requested-With",
            "X-XSRF-TOKEN"));
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
