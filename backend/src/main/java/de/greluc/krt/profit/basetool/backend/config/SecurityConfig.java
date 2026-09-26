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
 * Backend security configuration: JWT resource server, role hierarchy, CSRF policy and the request
 * authorization matrix.
 *
 * <p>The matrix in {@link #filterChain} is the exhaustive source of which endpoints are public or
 * require a role; method-level {@code @PreAuthorize} only narrows it. Only {@code /error}, {@code
 * /actuator/health(/**)}, {@code /internal/**} and the {@code GET} reads {@code
 * /api/v1/terms/document} and {@code /api/v1/app/version-policy} answer without a token
 * (REQ-SEC-052), and a token mapping to no application role is refused with {@code 403 NO_ROLE}
 * (REQ-SEC-053).
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
   * Paths exempt from cookie-based CSRF, because this stateless chain authenticates only with a
   * bearer JWT, which a browser never attaches by itself.
   */
  static final String[] CSRF_EXEMPT_PATHS = {"/api/v1/**", "/internal/**"};

  /**
   * Cross-origin allowlist for the backend API; empty by default, so every cross-origin browser
   * call is rejected with {@code 403}.
   */
  @Value("${app.cors.allowed-origin-patterns:}")
  private List<String> allowedOriginPatterns;

  /**
   * Expected JWT {@code aud} values for the opt-in audience check; empty disables it. Under the
   * {@code prod} profile {@link JwtAudienceStartupCheck} refuses to start while it is blank.
   */
  @Value("${app.security.jwt.expected-audiences:}")
  private List<String> expectedAudiences;

  /**
   * Creates the resource-server {@link JwtDecoder} when an expected audience or an internal JWKS
   * URL is configured (REQ-SEC-024); otherwise Spring Boot's auto-configured decoder is used.
   *
   * <p>Validates signature, issuer and timestamps, plus {@code aud} when audiences are configured;
   * {@code iss} is always checked against the public issuer.
   *
   * @param issuerUri the Keycloak issuer location used for {@code iss} validation
   * @param jwkSetUri the internal JWKS URL, or blank to derive keys from the issuer
   * @param sslBundles the SSL bundles holding the {@code keycloak-trust} pin
   * @return a Nimbus decoder wired for the configured checks
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
   * Builds the {@link NimbusJwtDecoder} for {@link #resourceServerJwtDecoder}: issuer-location
   * discovery for a blank {@code jwkSetUri}, otherwise a lazy key fetch from that URL over a {@link
   * KeycloakTrustSupport}-pinned client, falling back to the default client without a {@code
   * keycloak-trust} bundle.
   *
   * @param issuerUri the Keycloak issuer location
   * @param jwkSetUri the internal JWKS URL, or blank for issuer-location discovery
   * @param sslBundles the registered SSL bundles
   * @return the Nimbus decoder, without validators
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
   * intersects {@code expectedAudiences}.
   *
   * @param expectedAudiences the accepted audience values; an empty list matches no token
   * @return a validator that fails unless the JWT's {@code aud} intersects the expected set
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
   * Wires the project's authorities converter into a {@link JwtAuthenticationConverter}, so every
   * authenticated request carries Keycloak realm roles plus the DB-flag-derived roles.
   *
   * @param customConverter the project-specific authorities converter bean
   * @return the wired {@code JwtAuthenticationConverter}
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
   * The 15-minute window of distinct subjects refused with {@code NO_ROLE}, published as {@link
   * MetricNames#NO_ROLE_REFUSED_SUBJECTS}; separate from the consent window because one member can
   * be in both populations.
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
   * The 15-minute sliding window of distinct subjects the consent gate refused, published as the
   * {@code basetool_terms_refused_subjects} gauge (REQ-SEC-028) and capped at 5 000 entries.
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
   * Builds the main {@link SecurityFilterChain}: CSRF policy, CORS, security response headers, the
   * profile-independent request-authorization matrix and the JWT resource server.
   *
   * @param http the Spring Security builder
   * @param jwtAuthenticationConverter the converter from {@link #jwtAuthenticationConverter}
   * @param env the environment; the {@code test} profile disables CSRF and stands the consent gate
   *     down unless {@link #TERMS_GATE_ARMED_IN_TEST} re-arms it
   * @param securityProblemResponseHandler renders filter-level 401/403 as problem+json
   * @param messageSource localizes the 403 bodies of the refusing filters
   * @param problemResponseFactory assembles the RFC&nbsp;7807 body for those filters
   * @param objectMapper serializes those filters' {@code ProblemDetail}s
   * @param meterRegistry counts the identity-provider-unavailable 503
   * @param noRoleRefusedSubjectWindow the window the {@code NO_ROLE} refusals are recorded into
   * @param clientAttribution bounds the {@code client_id} label of the API client request counter
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
      // lgtm[java/spring-disabled-csrf-protection]
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
   * Builds the CORS source from {@code app.cors.allowed-origin-patterns}, with credentials never
   * allowed.
   *
   * @return the CORS source applied to all paths
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
