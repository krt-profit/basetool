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
import de.greluc.krt.profit.basetool.backend.support.ApiClientMetricsProperties;
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
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
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
 * project-specific {@code is_logistician} / {@code is_mission_manager} flags on {@code app_user}
 * into Spring authorities. The role hierarchy mirrors the CLAUDE.md matrix (admin/officer imply
 * logistician/mission-manager).
 *
 * <p>The {@code authorizeHttpRequests} matrix in {@link #filterChain} is the single, exhaustive
 * source for which endpoints are public, which require authentication, and which require a specific
 * role/authority. The order matters — Spring evaluates the matchers top-down. Method-level
 * {@code @PreAuthorize} on services adds fine-grained checks but never weakens the chain matcher.
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
   * to the backend client / resource id to turn it on.
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
   * Builds the {@code aud}-claim validator (audit L-1): a token passes only when its {@code aud}
   * list intersects {@code expectedAudiences}. Package-private + static so it is unit-testable
   * without a Spring context.
   *
   * @param expectedAudiences the accepted audience values; never {@code null} (an empty set matches
   *     no token).
   * @return an {@link OAuth2TokenValidator} that errors unless the JWT's {@code aud} intersects the
   *     expected set.
   */
  static OAuth2TokenValidator<Jwt> audienceValidator(List<String> expectedAudiences) {
    return new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD,
        aud -> aud != null && !java.util.Collections.disjoint(aud, expectedAudiences));
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
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter(
      Converter<Jwt, Collection<GrantedAuthority>> customConverter) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(customConverter);
    return converter;
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
   * @param apiClientMetricsProperties bounds the {@code client_id} label of {@code
   *     basetool_api_client_requests_total} (A8, REQ-OBS-018)
   * @return the configured security filter chain
   * @throws Exception propagated from {@link HttpSecurity#build()}
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      org.springframework.core.env.Environment env,
      SecurityProblemResponseHandler securityProblemResponseHandler,
      MessageSource messageSource,
      ProblemResponseFactory problemResponseFactory,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      TermsConsentCheck termsConsentCheck,
      RefusedSubjectWindow refusedSubjectWindow,
      IngestGatewayProperties ingestGatewayProperties,
      ActingMemberAuthorities actingMemberAuthorities,
      RateLimitProperties rateLimitProperties,
      ApiClientMetricsProperties apiClientMetricsProperties)
      throws Exception {

    boolean isTest = java.util.Arrays.asList(env.getActiveProfiles()).contains("test");

    // REQ-SEC-028: the consent boundary is ARMED BY DEFAULT and stood down only under the `test`
    // profile, mirroring the CSRF carve-out below. The alternative — a property that must be set to
    // switch it on — was rejected: it ships a gate that looks armed and is not, which is the exact
    // failure TermsVersionProvider refuses to start for. MockMvc callers authenticate as synthetic
    // subjects that have no acceptance row and could not create one, so leaving it armed would
    // 403 roughly 120 pre-existing tests without testing anything about consent;
    // TermsAcceptanceAccessFilterTest drives the real filter directly instead.
    // NOTE: this carve-out is `test`-only. The E2E profile is NOT `test`, so the gate is live
    // there and E2E users must actually accept.
    // ...with one opt-in escape hatch, because the stand-down had a cost that only showed up
    // later: no test could observe the gate at all, so when the acting-member identity swap
    // (ADR-0129) made TermsAcceptanceAccessFilter fail OPEN on the ingest path, the whole suite
    // stayed green. A test class that is specifically about the consent boundary re-arms it for
    // itself. This cannot weaken production: outside the `test` profile the gate is armed
    // unconditionally, and the property is read only when `isTest` already holds.
    boolean armed =
        !isTest || env.getProperty(TERMS_GATE_ARMED_IN_TEST, Boolean.class, Boolean.FALSE);
    TermsConsentCheck effectiveConsentCheck = armed ? termsConsentCheck : userId -> true;

    if (isTest) {
      // CSRF is intentionally disabled in the `test` Spring profile so MockMvc
      // tests can POST without first acquiring a CSRF cookie. The production
      // path (the `else` branch below) keeps cookie-based CSRF enabled with
      // explicit `ignoringRequestMatchers(...)` only for the JWT-bearer-token
      // API endpoints under `/api/v1/**`, which are protected by JWT auth
      // and have no session cookie to attack. The test profile is gated by
      // Spring profile activation and is never enabled in deployed environments.
      // lgtm[java/spring-disabled-csrf-protection]
      http.csrf(
          org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
              ::disable);
    } else {
      // L-2: pin the CSRF cookie's Secure + SameSite attributes explicitly. Spring's default
      // CookieCsrfTokenRepository does not set SameSite, leaving the browser to fall back to
      // {@code Lax}; pinning {@code Strict} aligns the XSRF cookie with the session cookie
      // (see frontend application*.yml `server.servlet.session.cookie.same-site: strict`).
      CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
      csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(true));
      http.csrf(
          csrf ->
              csrf.csrfTokenRepository(csrfRepo)
                  .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                  // The reason each pattern is here, and what a per-endpoint list cost,
                  // are on CSRF_EXEMPT_PATHS. Do not narrow it back to individual paths.
                  .ignoringRequestMatchers(CSRF_EXEMPT_PATHS));
    }

    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(
            headers -> {
              headers.contentSecurityPolicy(
                  // The backend is a pure JSON resource server — it serves no HTML, scripts,
                  // styles, images or fonts of its own (enforced by the ArchUnit "no HTML" rules
                  // and the removal of Swagger UI). The earlier policy relaxed {@code style-src}
                  // with {@code 'unsafe-inline'} and allowed {@code data:} img/font sources purely
                  // so the bundled Swagger UI rendered; with that gone the policy locks down to
                  // {@code default-src 'none'}, which makes every fetch directive
                  // (script/style/img/font/connect/object) inherit {@code 'none'} — nothing can be
                  // loaded into a (would-be) document context. {@code frame-ancestors 'none'}
                  // mirrors X-Frame-Options DENY; {@code base-uri 'none'} and {@code form-action
                  // 'none'} are defence-in-depth against an injected {@code <base>}/{@code <form>}.
                  // {@code upgrade-insecure-requests} is dropped: there are no sub-resources left
                  // to upgrade.
                  csp ->
                      csp.policyDirectives(
                          "default-src 'none'; frame-ancestors 'none'; base-uri 'none';"
                              + " form-action 'none'"));
              headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
              headers.referrerPolicy(
                  ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
              // M-12: Cross-Origin-Opener-Policy + Cross-Origin-Resource-Policy. COOP isolates
              // the browsing-context group so a popup cannot reach back via {@code window.opener}
              // (OAuth-redirect timing attacks etc.); CORP prevents cross-origin embedding via
              // {@code <img src=…>} / {@code <script src=…>}.
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
              // Audit finding H-9: explicit HSTS. Spring Security's default writer only emits the
              // header when {@code request.isSecure()} is true; behind a reverse proxy without
              // forward-headers configuration that check silently disables HSTS. Setting it here
              // makes the policy explicit and independent of the request-scoped scheme detection.
              headers.httpStrictTransportSecurity(
                  hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
              headers.addHeaderWriter(
                  new org.springframework.security.web.header.writers.StaticHeadersWriter(
                      "Permissions-Policy",
                      // L-3: explicit deny for every browser feature the app does not use, so
                      // an injected iframe / shared context cannot opt-in.
                      "geolocation=(), camera=(), microphone=(), fullscreen=(),"
                          + " payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(),"
                          + " gyroscope=(), magnetometer=(), display-capture=(),"
                          + " clipboard-read=(), clipboard-write=(), interest-cohort=()"));
              headers.contentTypeOptions(Customizer.withDefaults());
            })
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/error", "/v3/api-docs/**")
                    // Swagger UI has been removed from the project (springdoc -api starter, no
                    // -ui). Only the raw OpenAPI document is served at /v3/api-docs, and only in
                    // non-prod profiles: the prod profile sets springdoc.api-docs.enabled=false so
                    // this matcher resolves to a 404 there. The committed openapi.json remains the
                    // single source of API documentation.
                    .permitAll()
                    // Spring Boot Actuator health endpoint, used by Docker HEALTHCHECK and by
                    // docker-compose `depends_on: condition: service_healthy`. Other actuator
                    // endpoints stay behind authentication (the `anyRequest().authenticated()`
                    // catch-all below, tightened to ROLE_ADMIN for the mutating loggers POST — see
                    // the next matcher). `management.endpoint.health.show-details=never` keeps the
                    // response to `{"status":"UP"}` so no internal details leak.
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // REQ-OBS-016: `loggers` is exposed so a log level can be raised at RUNTIME
                    // during an incident, and its POST variant MUTATES that level. The backend
                    // configures NO separate management port — Actuator rides the ordinary 11261
                    // connector, unreachable from the internet only because that connector lives
                    // on internal Docker networks — so without this rule the write would fall
                    // through to `anyRequest().authenticated()` below and ANY valid realm JWT
                    // could set the ROOT logger to TRACE, which makes Spring Security / WebClient
                    // / Netty write bearer tokens and request bodies into a log stream retained
                    // for 744 h. Only the mutator is gated; the read (GET /actuator/loggers) stays
                    // on the authenticated catch-all. Frontend and ingest serve Actuator on a
                    // dedicated, UNAUTHENTICATED management port where no identity exists to gate
                    // on, so there the write is removed instead
                    // (`management.endpoint.loggers.access: read-only`, prod profile only).
                    .requestMatchers(HttpMethod.POST, "/actuator/loggers/**")
                    .hasRole(Roles.ADMIN)
                    // Internal machine-to-machine endpoints (REQ-SEC-022): the Keycloak Discord SPI
                    // calls /internal/discord/account-existence during first-broker-login. It bears
                    // no JWT (Keycloak is outside the resource-server trust boundary), so it must
                    // be
                    // permitAll here and bypass the /api/** rate-limiter +
                    // PendingApprovalAccessFilter;
                    // the controller enforces its own constant-time shared-secret header instead.
                    .requestMatchers("/internal/**")
                    .permitAll()
                    // A6 / REQ-SEC-032: carved OUT of the catalog permitAll below, which it would
                    // otherwise fall into via /api/v1/materials/**. The material x terminal price
                    // matrix is the largest single response the API can produce — the frontend
                    // page-walks it at size=100000 — so leaving it anonymous hands an
                    // unauthenticated
                    // caller the cheapest amplification lever on the whole surface. It is operating
                    // data, not guest content: its only consumer is MaterialsPageController, which
                    // carries @PreAuthorize("isAuthenticated()"), so requiring a token here costs
                    // nothing. Ordering matters — Spring Security takes the FIRST matching rule, so
                    // this must stay above the /api/v1/materials/** entry.
                    // ADR-0138 / REQ-SEC-028: the Terms-of-Use WORDING is anonymous, while
                    // /api/v1/terms/status and /acceptance below it stay authenticated. A document
                    // everyone must be able to read before agreeing to anything cannot require
                    // having agreed, and the identical text is already world-readable at /terms on
                    // the web frontend, so this publishes nothing new -- it stops the app from
                    // needing its own drifting copy. Ordering matters: Spring Security takes the
                    // FIRST matching rule, so this exact path must stay above any broader
                    // /api/v1/terms rule and above the authenticated catch-all.
                    .requestMatchers(HttpMethod.GET, "/api/v1/terms/document")
                    .permitAll()
                    // REQ-API-010 / REQ-SEC-037: the served-version floor for the Android
                    // app, anonymous by owner decision (2026-08-24) and the single exception to
                    // the vhost's no-anonymous-paths stance (plan Q8). A version gate that only
                    // answers after a successful login is silent in exactly the case it exists
                    // for: when the break is in the auth flow, the old build cannot log in and
                    // would show an authentication error instead of "Update erforderlich".
                    // It carries three integers and a public release URL -- no caller identity
                    // goes in and none comes out. Same ordering rule as the line above: it must
                    // stay ahead of any broader /api/v1/app rule and the authenticated catch-all.
                    .requestMatchers(HttpMethod.GET, "/api/v1/app/version-policy")
                    .permitAll()
                    // Deliberately NOT method-scoped. A tightening placed above an all-verb
                    // permitAll must itself be all-verb, or the rule underneath grants every verb
                    // the tightening does not claim: with HttpMethod.GET here, a HEAD fell through
                    // to the catalogue permitAll below, and Spring MVC answers HEAD from the
                    // @GetMapping handler - so the query ran anonymously and the response's
                    // Content-Length leaked back, on the two paths REQ-SEC-032 exists to close.
                    // Both paths serve only GET, so all-verb authenticated() costs nothing here.
                    .requestMatchers(
                        "/api/v1/materials/matrix",
                        // Same carve-out, same reason, and it was meant to land with the line
                        // above: the per-material slice of that price matrix. Its only consumer is
                        // the inventory page's "where can I sell this" suggestion, which is
                        // authenticated, so a token costs nothing here either — while leaving it
                        // anonymous publishes UEX trade prices per material to the whole internet
                        // from the API vhost. The nightly edge-deny probe has asserted 401 for it
                        // since the phase-3 paste and got 200: the expectation was right and the
                        // carve-out was simply missing. Ordering matters — Spring Security takes
                        // the FIRST matching rule, so both must stay above /api/v1/materials/**.
                        "/api/v1/materials/*/terminals")
                    .authenticated()
                    .requestMatchers(
                        "/api/v1/frequency-types", "/api/v1/frequency-types/**",
                        "/api/v1/locations", "/api/v1/locations/**",
                        "/api/v1/job-types", "/api/v1/job-types/**",
                        "/api/v1/manufacturers", "/api/v1/manufacturers/**",
                        "/api/v1/materials", "/api/v1/materials/**",
                        "/api/v1/refining-methods", "/api/v1/refining-methods/**",
                        "/api/v1/settings", "/api/v1/settings/**",
                        "/api/v1/ship-types", "/api/v1/ship-types/**",
                        "/api/v1/squadrons", "/api/v1/squadrons/**",
                        "/api/v1/star-systems", "/api/v1/star-systems/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/missions/next")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/missions/search")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/missions/{id}")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/missions", "/api/v1/missions/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/missions")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/refinery-orders/mission/**",
                        "/api/v1/system/**",
                        "/api/v2/system/**")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/missions/*/participants/add",
                        "/api/v1/missions/*/participants/slim")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/missions/*/participants/*/check-in",
                        "/api/v1/missions/*/participants/*/check-out")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/missions/*/participants/*/check-in/slim",
                        "/api/v1/missions/*/participants/*/check-out/slim")
                    .permitAll()
                    // Both order creates require a login (ADR-0149). They were permitAll — the
                    // public request form, which let an outsider ask the organisation for material
                    // without an account and stamped the order onto the intake Spezialkommando
                    // because it had no author. That form is gone: every write in this system is
                    // attributable to a person, and this was the one that was not. Listed
                    // explicitly rather than left to the authenticated catch-all so the change is
                    // visible at the place that used to say the opposite.
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/items")
                    .authenticated()
                    // The item catalogue followed the anonymous item create and has no other
                    // anonymous consumer, so it follows it out again.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/orders/item-catalog",
                        "/api/v1/orders/item-catalog/**")
                    .authenticated()
                    // The active Staffel/SK catalogue stays anonymous. Its stated reason was the
                    // public order form (now gone, ADR-0149), but it is not the only one: it
                    // mirrors the already-permitAll /api/v1/squadrons catalogue, carries no PII —
                    // name + shorthand + kind + profit flag — and the anonymous mission sign-up
                    // still renders org names. Closing it would be a second, unrelated change.
                    .requestMatchers(HttpMethod.GET, "/api/v1/org-units/active")
                    .permitAll()
                    // Finance-entry creation is no longer anonymous: the method-level
                    // @PreAuthorize on MissionFinanceEntryController#createFinanceEntry requires an
                    // authenticated member (isMemberOrAbove), blocking anonymous AND role-less
                    // GUEST
                    // callers. The URL gate only has to stop being permitAll; the method gate is
                    // the
                    // source of truth for the member requirement.
                    .requestMatchers(HttpMethod.POST, "/api/v1/finance-entries")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/missions/*/participants/*",
                        "/api/v1/missions/*/participants/*/payout-preference")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/missions/*/participants/*/slim",
                        "/api/v1/missions/*/participants/*/payout-preference/slim")
                    .permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/missions/*/participants/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/missions/*/participants/*/slim")
                    .permitAll()
                    .requestMatchers("/api/v1/users/search")
                    .hasAnyRole(Roles.ADMIN, Roles.OFFICER, Roles.KRT_MEMBER)
                    // Bank-audience search twin (ADR-0089, the #1193 remoteSource switch): the bank
                    // pickers (register holder, grant Bank-Employee role, approval limits) resolve
                    // candidates across the whole user base and must stay reachable for a bank
                    // employee/manager who holds no org-role (REQ-BANK-008/009/044) — same widening
                    // as /lookup, and the same squadron scope. Kept off /search so the ordinary
                    // picker's gate is unchanged. BANK_EMPLOYEE covers BANK_MANAGEMENT via the role
                    // hierarchy; both are listed so the URL gate does not depend on hierarchy
                    // evaluation at the filter layer.
                    .requestMatchers("/api/v1/users/search-bank")
                    .hasAnyRole(
                        Roles.ADMIN,
                        Roles.OFFICER,
                        Roles.KRT_MEMBER,
                        Roles.BANK_MANAGEMENT,
                        Roles.BANK_EMPLOYEE)
                    // Bank widening (REQ-BANK-009 grants, REQ-BANK-044 deposit/withdrawal
                    // counterparty): bank staff resolve grantees and the Einzahler/Empfänger via
                    // the
                    // user lookup and need not hold any org-role (REQ-BANK-008). BANK_EMPLOYEE
                    // covers
                    // BANK_MANAGEMENT via the role hierarchy; both are listed so the URL gate does
                    // not
                    // depend on hierarchy evaluation at the filter layer.
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
                    // Post Phase-4-Lockdown (MULTI_SQUADRON_PLAN.md section 2): flag-vergabe
                    // (Logistician/Mission-Manager) und attribute-patches sind admin-only. Die
                    // method-level @PreAuthorize auf UserController#patchLogistician /
                    // #patchMissionManager / #updateAttributes verlangt bereits hasRole('ADMIN');
                    // der Path-Matcher hier war ein Relikt der Pre-Phase-4-Konfiguration und wird
                    // jetzt mit der Method-Level-Annotation in Deckung gebracht, damit
                    // SecurityConfig nicht mehr suggeriert OFFICER duerfe diese Endpunkte
                    // erreichen.
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/users/*/logistician")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/users/*/mission-manager")
                    .hasRole(Roles.ADMIN)
                    .requestMatchers(HttpMethod.PUT, "/api/v1/users/*/attributes")
                    .hasRole(Roles.ADMIN)
                    // GET .../memberships ist die Picker-Read-Variante (SPEZIALKOMMANDO_PLAN.md
                    // §7.4) — gibt nur OrgUnit-Names + Shorthands zurueck, keine PII. Wird vom
                    // Frontend OrgUnitContextAdvice (Sidebar-Switcher + Bereichskontext-Chip)
                    // sowie von den R5.d Owner-Picker-Fragments fuer jeden authenticated Caller
                    // gelesen. Ohne diese explizite Regel faellt die URL in die catch-all
                    // `/api/v1/users/**` darunter — die ist `hasRole("ADMIN")` und verursachte
                    // einen 403 fuer Non-Admins beim eigenen Memberships-Lookup (Sidebar-Chip
                    // zeigte dann "Kein Bereichskontext"). Der Method-Level @PreAuthorize auf
                    // UserController#getUserMemberships ist die zweite Verteidigungslinie
                    // (defense in depth) und bleibt das Source-of-truth fuer die zulaessigen
                    // Rollen — die URL-Regel oeffnet nur das Tor.
                    // BANK_EMPLOYEE widening (REQ-BANK-044): the deposit/withdrawal counterparty
                    // org-unit picker resolves the chosen user's memberships here; a bank employee
                    // need not hold any org-role (REQ-BANK-008). BANK_EMPLOYEE covers
                    // BANK_MANAGEMENT
                    // via the role hierarchy.
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
                    // Bank admin carve-out (REQ-BANK-010/-012): wipe reset and the audit log are
                    // URL-gated to ADMIN on top of the method-level @PreAuthorize — bank
                    // management explicitly does NOT pass. The rest of /api/v1/bank/** rides the
                    // authenticated() catch-all plus the BankSecurityService method gates.
                    .requestMatchers("/api/v1/bank/admin/**")
                    .hasRole(Roles.ADMIN)
                    // Activity audit logs (REQ-AUDIT-001, ADR-0037): the per-area viewer and PDF
                    // export are URL-gated to ADMIN on top of the method-level @PreAuthorize.
                    .requestMatchers("/api/v1/audit/**")
                    .hasRole(Roles.ADMIN)
                    .anyRequest()
                    .authenticated())
        // RFC-7807 hardening (REQ-API-004 / REQ-SEC): route filter-level 401/403 through the same
        // problem+json handler the rest of the API uses. The global exceptionHandling entry point
        // covers the no-token / anonymous case; the resource-server overrides cover the
        // bearer-token-rejected case (Spring installs its own bare-401 entry point there
        // otherwise).
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
        // REQ-SEC-017 (PR review #1): a PENDING/REJECTED registration is authenticated but carries
        // only ROLE_PENDING_APPROVAL; this filter 403s it on every /api/** endpoint (except the
        // registration-status read) so the "no access until approved" guarantee holds at the
        // backend
        // boundary, not just via the frontend redirect. Placed after the bearer-token filter so the
        // authorities are already assembled. Emits an RFC-7807 problem+json body with a minted
        // correlationId (it runs before CorrelationIdFilter) — RFC-7807 hardening.
        // ADR-0129: replace the security identity with the member an ingest gateway is acting for,
        // BEFORE the two person-gates below. They must judge the person who is sending, exactly as
        // they did while the gateway still relayed that person's token — otherwise consent
        // (REQ-SEC-028) and approval (REQ-SEC-017) would be evaluated against a service account and
        // the ingest path would silently stop enforcing either. Runs immediately after the
        // bearer-token filter, which is the first point at which there IS a caller to check.
        // A8 / REQ-OBS-018: attribute every authenticated API request to its client software.
        // The window is narrow and both edges matter. AFTER BearerTokenAuthenticationFilter,
        // because before it there is no SecurityContext and the filter would see every request as
        // anonymous — and it skips those silently, so the metric would simply stay empty
        // (measured: no series at all in production between 2026-08-18 and this fix). BEFORE
        // ActingMemberFilter, because that filter swaps an on-behalf-of call's authentication for
        // an ActingMemberAuthentication which carries no claims, so a gateway request observed
        // after it would count as anonymous too. Ahead of the refusing gates for a third reason: a
        // client must not be able to hide from the counter behind its own 403s and 429s.
        //
        // Expressed as addFilterAfter on the bearer filter — the SAME anchor ActingMemberFilter
        // uses — and registered FIRST, which is what puts it between the two. Measured, not
        // reasoned: with two addFilterAfter calls naming one anchor, the earlier registration ends
        // up earlier (registering this one second placed it at 10, behind ActingMemberFilter at 9).
        // Do not "simplify" this to addFilterBefore(…, ActingMemberFilter.class): that spelling
        // reads as if it did the same and instead lands the filter at the bearer filter's own slot
        // — position 8 ahead of the bearer filter at 9, i.e. before there is any SecurityContext,
        // which is exactly how the counter shipped dead. ApiClientMetricsChainTest pins both edges
        // now; each wrong variant above fails it.
        .addFilterAfter(
            new ApiClientMetricsFilter(
                apiClientMetricsProperties, ingestGatewayProperties, meterRegistry),
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
                messageSource, problemResponseFactory, objectMapper, meterRegistry),
            ActingMemberFilter.class)
        // REQ-SEC-028: refuse the API until the Terms of Use are accepted. Enforced HERE rather
        // than only in the frontend because the backend is the one place every caller passes
        // through — the web UI and, since ActingMemberFilter above makes the gateway's call carry
        // the sending member's identity (ADR-0129), the desktop extractor. Placed AFTER the
        // pending-approval filter so a
        // user who is both pending and unconsented gets the approval message, which is the one
        // they can actually act on.
        .addFilterAfter(
            new TermsAcceptanceAccessFilter(
                effectiveConsentCheck,
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry,
                refusedSubjectWindow),
            PendingApprovalAccessFilter.class)
        // A6 / REQ-SEC-032: bound the page size an unauthenticated caller may ask for. Placed here
        // because the only question it asks is "is this caller anonymous", which is settled by the
        // authentication filters above; an authenticated caller passes straight through.
        .addFilterAfter(
            new AnonymousPageSizeFilter(
                messageSource, problemResponseFactory, objectMapper, meterRegistry),
            TermsAcceptanceAccessFilter.class)
        // A3 / REQ-SEC-033: bound how hard one authenticated ACCOUNT can drive the API.
        // Anchored behind the anonymous page-size gate above rather than behind the terms
        // filter, so the order of these two is stated here instead of falling out of the
        // registration order (addFilterAfter inserts directly after its anchor, so two calls
        // naming the same one end up reversed). The two are disjoint anyway — one only ever
        // sees anonymous callers, the other only authenticated ones. The per-IP
        // limiter ahead of the chain bounds a network position, which is the wrong unit in both
        // directions — CGNAT puts many members behind one address, and an address pool escapes it
        // entirely. Placed after the gates above so a pending or unconsented caller is refused on
        // its own terms rather than spending a token first.
        .addFilterAfter(
            new SubjectRateLimitingFilter(
                rateLimitProperties,
                messageSource,
                problemResponseFactory,
                objectMapper,
                meterRegistry),
            AnonymousPageSizeFilter.class)
        // REQ-SEC-024: catch an identity-provider-unreachable failure (JWKS fetch timeout / 5xx /
        // Docker-DNS strand) escaping the bearer-token filter as a re-thrown
        // AuthenticationServiceException and re-map it to a retryable 503 instead of the opaque 500
        // it produces by default. Installed BEFORE the bearer-token filter so its try/catch wraps
        // that filter's execution; a genuine 401/403 never reaches it. WARN-logged and counted so a
        // Keycloak blip does not masquerade as an application error (LogbackErrorSpike).
        .addFilterBefore(
            new IdentityProviderUnavailableFilter(
                messageSource, problemResponseFactory, objectMapper, meterRegistry),
            org.springframework.security.oauth2.server.resource.web.authentication
                .BearerTokenAuthenticationFilter.class)
        // L-11: backend is a pure JWT-bearer resource server — no HTTP session needed for any
        // endpoint. Pinning STATELESS makes the contract explicit: a future bug that introduces
        // {@code @SessionAttributes} or {@code request.getSession(true)} on a permitAll POST is
        // caught at startup rather than silently creating a session per anonymous caller.
        .sessionManagement(
            sm ->
                sm.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));

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
    // Credentials are intentionally disabled. The backend does not authenticate
    // browsers directly; the frontend exchanges tokens server-side and proxies
    // every request. Allowing credentials here would magnify the impact of any
    // future origin-list misconfiguration (open-CORS-with-credentials).
    configuration.setAllowCredentials(false);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
