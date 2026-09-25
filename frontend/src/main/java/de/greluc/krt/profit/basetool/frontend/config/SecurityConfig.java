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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

/** Spring configuration for Security. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

  /**
   * Concurrent-session cap per principal, also reported by {@link SessionEvictionLoggingStrategy}.
   */
  private static final int MAX_CONCURRENT_SESSIONS = 10;

  private final RequestLoggingFilter requestLoggingFilter;
  private final BackendRoleSyncFilter backendRoleSyncFilter;
  private final TermsAcceptanceGateFilter termsAcceptanceGateFilter;
  private final BotProtectionFilter botProtectionFilter;

  /**
   * Optional — only wired in {@code dev} / {@code test} profiles (see {@link SessionDebugFilter}'s
   * {@code @Profile} annotation). In prod the bean does not exist and the filter is skipped (audit
   * finding M-15: the filter logs raw session ids + principal names which must never reach prod
   * logs even if an operator flips the per-class log level).
   */
  private final org.springframework.beans.factory.ObjectProvider<SessionDebugFilter>
      sessionDebugFilter;

  private final SsoReAuthenticationEntryPoint ssoReAuthenticationEntryPoint;
  private final CspNonceFilter cspNonceFilter;
  private final MeterRegistry meterRegistry;

  /**
   * Declares the Keycloak-role inheritance: {@code ADMIN} inherits {@code LOGISTICIAN} and {@code
   * MISSION_MANAGER}; {@code OFFICER} inherits {@code LOGISTICIAN} and {@code MISSION_MANAGER}.
   * Mirrored from {@code ROLES_AND_PERMISSIONS.md} - keep both in sync.
   */
  @Bean
  public static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(
        """
        ROLE_ADMIN > ROLE_LOGISTICIAN
        ROLE_OFFICER > ROLE_LOGISTICIAN
        ROLE_ADMIN > ROLE_MISSION_MANAGER
        ROLE_OFFICER > ROLE_MISSION_MANAGER
        ROLE_ADMIN > ROLE_BANK_MANAGEMENT
        ROLE_BANK_MANAGEMENT > ROLE_BANK_EMPLOYEE
        """);
  }

  /** The browser's own word for "this is a page navigation" (Fetch Metadata). */
  private static final String SEC_FETCH_MODE_HEADER = "Sec-Fetch-Mode";

  /** The {@code Sec-Fetch-Mode} value a top-level navigation carries. */
  private static final String NAVIGATE_FETCH_MODE = "navigate";

  /**
   * Header marking one of our own monitoring probes, so {@link #navigationRequestCache()} does not
   * save it.
   */
  private static final String MONITORING_PROBE_HEADER = "X-Basetool-Probe";

  /**
   * Main security filter chain: wires the CSP-nonce, bot-protection, session-debug, request-logging
   * and backend-role-sync filters, OAuth2 login against Keycloak with OIDC logout, and the per-path
   * access matrix.
   *
   * @param http the builder to configure
   * @param clientRegistrationRepository the OAuth2 client registry the entry point redirects
   *     through
   * @param keycloakIssuerUri the issuer URI fed into the CSP {@code form-action} allow-list
   * @param sessionRegistryProvider the Redis-backed session registry, absent in the {@code test}
   *     profile
   * @param oauth2LoginSuccessHandler the post-login handler chain
   * @param navigationRequestCache the shared request cache
   * @param oauthAuthorizationCodeTokenResponseClient the pool-hardened token client (ADR-0115)
   * @return the configured filter chain
   * @throws Exception if the builder rejects the configuration
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ClientRegistrationRepository clientRegistrationRepository,
      @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri:}")
          String keycloakIssuerUri,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.security.core.session.SessionRegistry>
          sessionRegistryProvider,
      AuthenticationSuccessHandler oauth2LoginSuccessHandler,
      RequestCache navigationRequestCache,
      org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient<
              org.springframework.security.oauth2.client.endpoint
                  .OAuth2AuthorizationCodeGrantRequest>
          oauthAuthorizationCodeTokenResponseClient)
      throws Exception {
    SmartOidcLogoutSuccessHandler oidcLogoutSuccessHandler =
        new SmartOidcLogoutSuccessHandler(clientRegistrationRepository, "{baseUrl}");

    http.addFilterBefore(
            cspNonceFilter, org.springframework.security.web.header.HeaderWriterFilter.class)
        .addFilterBefore(
            botProtectionFilter,
            org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter
                .class);
    sessionDebugFilter.ifAvailable(
        f ->
            http.addFilterBefore(
                f,
                org.springframework.security.web.context.request.async
                    .WebAsyncManagerIntegrationFilter.class));
    http.addFilterBefore(
            requestLoggingFilter,
            org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter
                .class)
        .addFilterBefore(backendRoleSyncFilter, AuthorizationFilter.class)
        .addFilterAfter(termsAcceptanceGateFilter, BackendRoleSyncFilter.class)
        .requestCache(cache -> cache.requestCache(navigationRequestCache))
        .csrf(org.springframework.security.config.Customizer.withDefaults())
        .headers(SecurityHeaders.frontend(keycloakIssuerUri))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/error",
                        "/error/**",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/logos/**",
                        "/fonts/**",
                        "/impressum",
                        "/privacy",
                        "/terms",
                        "/licenses",
                        "/robots.txt",
                        "/.well-known/assetlinks.json",
                        "/manifest.webmanifest",
                        "/app/callback",
                        "/app/link-help",
                        "/favicon.ico",
                        "/sm/**",
                        "/**/*.map")
                    .permitAll()
                    .requestMatchers("/ws/sync")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .loginPage("/oauth2/authorization/keycloak")
                    .failureHandler(new LoginFailureMetricsHandler(meterRegistry, "/?error"))
                    .successHandler(oauth2LoginSuccessHandler)
                    .tokenEndpoint(
                        token ->
                            token.accessTokenResponseClient(
                                oauthAuthorizationCodeTokenResponseClient))
                    .authorizationEndpoint(
                        auth ->
                            auth.authorizationRequestResolver(
                                authorizationRequestResolver(clientRegistrationRepository)))
                    .userInfoEndpoint(
                        userInfo -> userInfo.userAuthoritiesMapper(userAuthoritiesMapper())))
        .logout(
            logout ->
                logout
                    .logoutRequestMatcher(
                        request ->
                            "POST".equals(request.getMethod())
                                && request
                                    .getRequestURI()
                                    .equals(request.getContextPath() + "/logout"))
                    .logoutSuccessHandler(oidcLogoutSuccessHandler))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(ssoReAuthenticationEntryPoint)
                    .accessDeniedHandler(new CsrfMetricsAccessDeniedHandler(meterRegistry)))
        .sessionManagement(
            sm -> {
              var concurrency =
                  sm.sessionFixation(
                          org.springframework.security.config.annotation.web.configurers
                                  .SessionManagementConfigurer.SessionFixationConfigurer
                              ::changeSessionId)
                      .maximumSessions(MAX_CONCURRENT_SESSIONS)
                      .maxSessionsPreventsLogin(false)
                      .expiredSessionStrategy(
                          new SessionEvictionLoggingStrategy(
                              meterRegistry, MAX_CONCURRENT_SESSIONS));
              sessionRegistryProvider.ifAvailable(concurrency::sessionRegistry);
            });
    return http.build();
  }

  /**
   * Publishes servlet session-lifecycle events to Spring's in-memory {@link
   * org.springframework.security.core.session.SessionRegistry}, used for the session cap in
   * profiles without Redis-indexed sessions (e.g. {@code test}).
   *
   * @return the publisher bean
   */
  @NotNull
  @Bean
  public org.springframework.security.web.session.HttpSessionEventPublisher
      httpSessionEventPublisher() {
    return new org.springframework.security.web.session.HttpSessionEventPublisher();
  }

  /**
   * The one request cache in the frontend, and the only place a pre-login request may create a
   * session.
   *
   * <p>Saves only {@code GET} browser navigations that are not monitoring probes; everything else
   * is refused without a saved request or session. Shared with {@link
   * AssetAwareAuthenticationSuccessHandler}.
   *
   * @return the shared request cache; never {@code null}
   */
  @NotNull
  @Bean
  public RequestCache navigationRequestCache() {
    HttpSessionRequestCache cache = new HttpSessionRequestCache();
    cache.setRequestMatcher(
        request ->
            org.springframework.http.HttpMethod.GET.matches(request.getMethod())
                && isNavigation(request)
                && !isMonitoringProbe(request));
    return cache;
  }

  /**
   * Whether this request is a browser navigation: {@code Sec-Fetch-Mode: navigate} decides when
   * present, with an HTML {@code Accept} as fallback only when it is absent.
   *
   * @param request the request to inspect; never {@code null}
   * @return {@code true} for a declared navigation, or for an HTML {@code Accept} when the client
   *     sends no Fetch Metadata
   */
  private static boolean isNavigation(@NotNull jakarta.servlet.http.HttpServletRequest request) {
    String fetchMode = request.getHeader(SEC_FETCH_MODE_HEADER);
    if (fetchMode != null) {
      return NAVIGATE_FETCH_MODE.equalsIgnoreCase(fetchMode);
    }
    return acceptsHtml(request);
  }

  /**
   * Whether this request carries the monitoring-probe marker header, so saving it would not cost a
   * Redis session. The header grants nothing.
   *
   * @param request the request to inspect; never {@code null}
   * @return {@code true} when the request carries the probe marker
   */
  private static boolean isMonitoringProbe(
      @NotNull jakarta.servlet.http.HttpServletRequest request) {
    return request.getHeader(MONITORING_PROBE_HEADER) != null;
  }

  /**
   * Whether the {@code Accept} header names {@code text/html}; the fallback when a client sends no
   * {@code Sec-Fetch-Mode}.
   *
   * @param request the request to inspect; never {@code null}
   * @return {@code true} when the {@code Accept} header names {@code text/html}
   */
  private static boolean acceptsHtml(@NotNull jakarta.servlet.http.HttpServletRequest request) {
    String accept = request.getHeader(org.springframework.http.HttpHeaders.ACCEPT);
    return accept != null
        && accept
            .toLowerCase(java.util.Locale.ROOT)
            .contains(org.springframework.http.MediaType.TEXT_HTML_VALUE);
  }

  /**
   * Builds the post-OAuth2-login success handler chain: {@link LoginSuccessMetricsHandler} around
   * {@link SessionLifetimeUpgradeSuccessHandler} (REQ-SEC-025, ADR-0088) around {@link
   * AssetAwareAuthenticationSuccessHandler}, which drops saved requests pointing at static assets.
   *
   * @param authenticatedSessionTimeout the idle window a login promotes its session to ({@code
   *     app.session.authenticated-timeout})
   * @return the success handler wired into {@code .oauth2Login().successHandler(...)}
   */
  @NotNull
  @Bean
  public AuthenticationSuccessHandler oauth2LoginSuccessHandler(
      @Value("${app.session.authenticated-timeout:720h}") Duration authenticatedSessionTimeout,
      RequestCache navigationRequestCache) {
    return new LoginSuccessMetricsHandler(
        meterRegistry,
        new SessionLifetimeUpgradeSuccessHandler(
            authenticatedSessionTimeout,
            new AssetAwareAuthenticationSuccessHandler(navigationRequestCache)));
  }

  @NotNull
  private OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      ClientRegistrationRepository clientRegistrationRepository) {
    DefaultOAuth2AuthorizationRequestResolver defaultResolver =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization");

    return new OAuth2AuthorizationRequestResolver() {
      @Override
      public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest req = defaultResolver.resolve(request);
        return customizeAuthorizationRequest(req, request);
      }

      @Override
      public OAuth2AuthorizationRequest resolve(
          HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest req = defaultResolver.resolve(request, clientRegistrationId);
        return customizeAuthorizationRequest(req, request);
      }

      @Nullable
      private OAuth2AuthorizationRequest customizeAuthorizationRequest(
          OAuth2AuthorizationRequest req, HttpServletRequest request) {
        if (req == null) {
          return null;
        }
        java.util.Locale locale =
            org.springframework.web.servlet.support.RequestContextUtils.getLocale(request);
        Map<String, Object> additionalParameters =
            new java.util.HashMap<>(req.getAdditionalParameters());
        additionalParameters.put("ui_locales", locale.getLanguage());
        String prompt = request.getParameter("prompt");
        if ("none".equals(prompt)) {
          additionalParameters.put("prompt", "none");
        }
        if ("discord".equals(request.getParameter("idp"))) {
          additionalParameters.put("kc_idp_hint", "discord");
        }
        return OAuth2AuthorizationRequest.from(req)
            .additionalParameters(additionalParameters)
            .build();
      }
    };
  }

  /**
   * Maps Keycloak realm roles from the OIDC token's {@code realm_access.roles} claim onto Spring
   * {@code ROLE_…} authorities (upper-cased, spaces replaced with underscores).
   */
  @Bean
  @SuppressWarnings("unchecked")
  public GrantedAuthoritiesMapper userAuthoritiesMapper() {
    return (authorities) -> {
      Set<GrantedAuthority> mappedAuthorities = new HashSet<>();

      authorities.forEach(
          authority -> {
            mappedAuthorities.add(authority);
            if (authority instanceof OidcUserAuthority oidcUserAuthority) {
              log.debug(
                  "OidcUserAuthority attribute keys: {}",
                  oidcUserAuthority.getAttributes().keySet());
              Map<String, Object> realmAccess =
                  (Map<String, Object>) oidcUserAuthority.getAttributes().get("realm_access");
              if (realmAccess != null && realmAccess.containsKey("roles")) {
                Collection<String> roles = (Collection<String>) realmAccess.get("roles");
                log.info("Mapping realm roles from Keycloak: {}", roles);
                roles.forEach(
                    role -> {
                      String mappedRole = "ROLE_" + role.toUpperCase().replace(" ", "_");
                      log.debug("Mapped role: {}", mappedRole);
                      mappedAuthorities.add(new SimpleGrantedAuthority(mappedRole));
                    });
              }
            }
          });

      return mappedAuthorities;
    };
  }

  /**
   * Counts and logs a concurrent-session eviction without changing what the evicted user sees.
   *
   * <p>The log line identifies the user only through the {@code userId} MDC key (else {@value
   * #UNKNOWN_USER}), never the principal name or session id (REQ-OBS-004); the counter {@link
   * MetricNames#SESSION_EVICTED} is unlabelled. The response reproduces Spring Security's default
   * expired-session output byte for byte.
   */
  static final class SessionEvictionLoggingStrategy implements SessionInformationExpiredStrategy {

    /**
     * Response body of Spring Security's default expired-session strategy, copied verbatim;
     * changing it changes what the evicted user sees.
     */
    private static final String DEFAULT_EXPIRED_BODY =
        "This session has been expired (possibly due to multiple concurrent logins being attempted"
            + " as the same user).";

    /** MDC key holding the authenticated caller's Keycloak {@code sub}. */
    private static final String USER_ID_MDC_KEY = "userId";

    /** Rendered when the {@code userId} MDC key is not populated on this thread. */
    private static final String UNKNOWN_USER = "unknown";

    private final MeterRegistry meterRegistry;
    private final int maximumSessions;

    /**
     * Builds the strategy with the counter registry and the cap value it reports.
     *
     * @param meterRegistry registry for the {@code basetool_session_evicted_total} counter
     * @param maximumSessions the configured concurrent-session cap, echoed into the log line
     */
    SessionEvictionLoggingStrategy(@NotNull MeterRegistry meterRegistry, int maximumSessions) {
      this.meterRegistry = meterRegistry;
      this.maximumSessions = maximumSessions;
    }

    /**
     * Counts and logs the eviction at WARN, then writes Spring Security's default expired-session
     * response.
     *
     * @param event the expiry event carrying the victim's request and response
     * @throws IOException if writing or flushing the response body fails
     */
    @Override
    public void onExpiredSessionDetected(@NotNull SessionInformationExpiredEvent event)
        throws IOException {
      meterRegistry.counter(MetricNames.SESSION_EVICTED).increment();
      String userId = MDC.get(USER_ID_MDC_KEY);
      log.warn(
          "Concurrent-session cap reached (maximumSessions={}); expired the oldest session of"
              + " userId={}",
          maximumSessions,
          userId == null || userId.isBlank() ? UNKNOWN_USER : userId);
      HttpServletResponse response = event.getResponse();
      response.getWriter().print(DEFAULT_EXPIRED_BODY);
      response.flushBuffer();
    }
  }
}
