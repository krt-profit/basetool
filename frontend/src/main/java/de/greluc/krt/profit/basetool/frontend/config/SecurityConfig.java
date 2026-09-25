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
   * Concurrent-session cap per principal, enforced by Spring Security's concurrency control. Ten
   * accommodates realistic multi-device / multi-browser use (a low cap of 2 evicted real users in
   * 2026-07) while still bounding parallel abuse of a stolen cookie. Named rather than inlined so
   * the value the eviction is logged with cannot drift from the value that is configured — see
   * {@link SessionEvictionLoggingStrategy}.
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
   * Marks one of our own monitoring probes, so the request cache does not remember it.
   *
   * <p>Set by the {@code http_members_only_redirect} blackbox module and by the nightly {@code
   * edge-deny-probe} workflow. Both assert {@code Sec-Fetch-Mode: navigate} deliberately - a
   * background call answers {@code 401} by design (REQ-SEC-012), so probing without it would assert
   * the wrong half of the contract - and that is exactly the branch {@link
   * #navigationRequestCache()} saves.
   */
  private static final String MONITORING_PROBE_HEADER = "X-Basetool-Probe";

  /**
   * Main security filter chain. Wires CSP-nonce, bot-protection, session-debug, request-logging and
   * backend-role-sync filters; configures OAuth2 login against Keycloak with smart OIDC logout; and
   * declares the path-by-path permitAll / authenticated matrix. The injected Keycloak issuer URI
   * feeds the CSP {@code form-action} allow-list so the POST-logout redirect to Keycloak's
   * end-session endpoint is not blocked by the browser.
   *
   * @param http the builder to configure
   * @param clientRegistrationRepository the OAuth2 client registry the entry point redirects
   *     through
   * @param keycloakIssuerUri the issuer URI fed into the CSP {@code form-action} allow-list
   * @param sessionRegistryProvider the Redis-backed session registry, absent in the {@code test}
   *     profile
   * @param oauth2LoginSuccessHandler the post-login handler chain
   * @param navigationRequestCache the one request cache, shared with that handler and its delegate
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
   * Bridges servlet-container session-lifecycle events to Spring's default in-memory {@link
   * org.springframework.security.core.session.SessionRegistry}. This only matters in profiles
   * WITHOUT Redis-indexed sessions (e.g. {@code test}): there {@code maximumSessions(...)} falls
   * back to {@code SessionRegistryImpl}, which this publisher feeds.
   *
   * <p>In prod/dev the sessions are Redis-indexed and the cap is backed by the {@code
   * SpringSessionBackedSessionRegistry} instead (see {@code RedisSessionConfig#sessionRegistry} and
   * the {@code sessionManagement} wiring above) — that registry reads the Redis principal-name
   * index directly and does not depend on this publisher. Kept because it is harmless and keeps the
   * non-Redis fallback functional. Audit finding M-14 (corrected by the gap-fill audit).
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
   * <p>Spring Security's default {@link HttpSessionRequestCache} saves <em>every</em> refused
   * request so the caller can be sent back after login. That was tolerable while most of the tool
   * answered anonymously; with REQ-SEC-052 every path refuses, so each background call a logged-out
   * browser makes — a poll, a prefetch, an {@code Accept: application/json} fetch, an SSE reconnect
   * — would mint a session in Redis to hold a URL nobody will ever be redirected to.
   *
   * <p>The matcher therefore admits only what a redirect-after-login can sensibly replay: a {@code
   * GET} the browser itself calls a navigation. {@code Sec-Fetch-Mode: navigate} is the browser's
   * own word for it and is sent by every engine the tool supports; the {@code Accept: text/html}
   * fallback covers a client that does not send Fetch Metadata. Everything else is refused without
   * a saved request and therefore without a session.
   *
   * <p><strong>One instance, injected in both directions.</strong> {@link
   * AssetAwareAuthenticationSuccessHandler} used to construct a private {@code new
   * HttpSessionRequestCache()} and its {@code SavedRequestAwareAuthenticationSuccessHandler}
   * delegate a third — three caches over the same session attribute, which happened to agree only
   * because they all used the default attribute name. A matcher on one of them would have been read
   * by none of the others.
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
   * Whether this request is a browser navigation, and therefore worth remembering for the redirect
   * after the login.
   *
   * <p><b>The Fetch Metadata header wins where it is present; the {@code Accept} test is the
   * fallback, not a second chance.</b> The two were ORed at first, which meant a background call
   * that says {@code Sec-Fetch-Mode: cors} and also asks for HTML — a fragment refetch, an
   * htmx-style partial, a prefetch — saved a request and minted a Redis session anyway. That is the
   * churn WP-F 11 exists to remove, and it lands hardest on exactly the five page families this
   * change moved out of {@code permitAll}; it can also overwrite a genuine deep link with a
   * fragment URL, so the member returns from the login to half a page. A browser that sends the
   * header has already answered the question, and {@code
   * SsoReAuthenticationEntryPoint.isBackgroundRequest} treats it the same way.
   *
   * @param request the request to inspect; never {@code null}
   * @return {@code true} for a declared navigation, or for an HTML {@code Accept} when the client
   *     sends no Fetch Metadata at all
   */
  private static boolean isNavigation(@NotNull jakarta.servlet.http.HttpServletRequest request) {
    String fetchMode = request.getHeader(SEC_FETCH_MODE_HEADER);
    if (fetchMode != null) {
      return NAVIGATE_FETCH_MODE.equalsIgnoreCase(fetchMode);
    }
    return acceptsHtml(request);
  }

  /**
   * Whether this request is one of our own monitoring probes, which must not be remembered.
   *
   * <p><b>Saving a probe costs a Redis session, permanently.</b> {@code HttpSessionRequestCache}
   * calls {@code request.getSession()} to hold the saved request, and
   * {@code @EnableRedisIndexedHttpSession} writes it. The members-only probe hits three targets
   * every 30 seconds and declares itself a navigation on purpose, so against the 30-minute {@code
   * app.session.anonymous-timeout} it settles at roughly 180 permanently resident anonymous
   * sessions and some 8,600 session writes a day, indefinitely - plus three more per nightly
   * edge-deny run. That is precisely the accretion WP-F 11 exists to remove and the mechanism
   * behind the >16k-orphan incident this cache's own comment cites: monitoring the fix must not
   * reintroduce what the fix removed.
   *
   * <p>Keyed on a header rather than on the User-Agent because the probe can simply declare itself,
   * and a User-Agent match would be a guess that breaks the day the exporter is upgraded. The
   * header being client-settable is not a weakness: the only thing sending it can do is give up
   * <em>your own</em> deep-link replay after login. It grants nothing, reveals nothing, and reaches
   * no authorization decision - {@code anyRequest().authenticated()} refuses the request either
   * way.
   *
   * @param request the request to inspect; never {@code null}
   * @return {@code true} when the request carries the probe marker
   */
  private static boolean isMonitoringProbe(
      @NotNull jakarta.servlet.http.HttpServletRequest request) {
    return request.getHeader(MONITORING_PROBE_HEADER) != null;
  }

  /**
   * Whether the request asks for HTML, used as the fallback when a client sends no {@code
   * Sec-Fetch-Mode}. Deliberately a substring test on the raw header: a browser navigation sends a
   * long {@code Accept} list beginning with {@code text/html}, and a background fetch does not name
   * it at all.
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
   * Builds the post-OAuth2-login success handler chain. Innermost is the {@link
   * AssetAwareAuthenticationSuccessHandler}, which wraps the default {@link
   * org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler}
   * so that saved requests pointing to static-asset URLs (background sourcemap lookups,
   * favicon/font/CSS probes from DevTools and browser extensions) are dropped and the user is sent
   * to the context root instead of a 404 asset path. That is wrapped by {@link
   * SessionLifetimeUpgradeSuccessHandler}, which promotes the session from the short anonymous idle
   * window to the long authenticated window (REQ-SEC-025, ADR-0088), and finally by {@link
   * LoginSuccessMetricsHandler}, which counts the successful login.
   *
   * @param authenticatedSessionTimeout the idle window an authenticated session should carry
   *     ({@code app.session.authenticated-timeout}, default the 30-day "stay logged in" window); a
   *     login promotes its short anonymous session to this window
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
   * Makes a concurrent-session eviction observable without changing one byte of what the evicted
   * user sees.
   *
   * <p>Spring Security expires the oldest session of a principal once {@code maximumSessions} is
   * exceeded, and until this strategy was wired that event left <b>no</b> usable trace: Spring's
   * own account of it is DEBUG-level on {@code org.springframework.security}, which the app pins to
   * {@code INFO} in {@code logback-spring.xml} and {@code application.yml}, and {@code
   * basetool_active_sessions} cannot see it either because {@code SessionInformation.expireNow()}
   * only <em>marks</em> the session — the registry entry (and the Redis session behind it) still
   * exists, so the gauge does not so much as dip. The visible end of the chain was a member's
   * unexplained logout, with nothing on the server side to correlate it against. That matters
   * because the two causes look identical from the outside: a user genuinely cycling through more
   * than {@link SecurityConfig#MAX_CONCURRENT_SESSIONS} devices, and a registry that stopped
   * reaping stale Redis entries so the cap fills with dead sessions and starts evicting live ones.
   *
   * <p>Identity in the log line comes from the {@code userId} MDC key ({@code sub}) only — never
   * the {@link org.springframework.security.core.session.SessionInformation#getPrincipal()
   * principal name}, which on this app is the Keycloak {@code preferred_username} / callsign, and
   * never the session id (REQ-OBS-004). Note that the security filter chain runs ahead of the
   * frontend's {@code CorrelationIdFilter}, so on many requests the key is not populated yet and
   * the line degrades to {@value #UNKNOWN_USER}; that is the accepted trade for not reaching for a
   * forbidden identifier. The {@code correlationId} of the victim's request is carried by the log
   * pattern itself under the same caveat.
   *
   * <p>The counter is unlabelled by construction: neither principal nor session id may become a tag
   * value, and no other bounded dimension exists here (see {@link MetricNames#SESSION_EVICTED}).
   *
   * <p><b>Victim-facing behaviour is preserved verbatim.</b> With neither an expired-URL nor a
   * strategy configured, Spring Security answers the expired session from {@code
   * ConcurrentSessionFilter}'s own default strategy, which prints {@value #DEFAULT_EXPIRED_BODY}
   * and flushes the buffer. That class ({@code
   * ConcurrentSessionFilter.ResponseBodySessionInformationExpiredStrategy}) is package-private and
   * final, so it cannot be constructed and delegated to from here; the two statements are therefore
   * reproduced literally below and must stay byte-identical to it. Do not "improve" the message or
   * add a redirect here — that is a user-visible behaviour change, not an observability change, and
   * it belongs in its own PR with the i18n and UX work it implies.
   */
  static final class SessionEvictionLoggingStrategy implements SessionInformationExpiredStrategy {

    /**
     * Response body Spring Security's default expired-session strategy writes. Copied verbatim from
     * {@code ConcurrentSessionFilter.ResponseBodySessionInformationExpiredStrategy} because that
     * class is not visible from here; changing it changes what the evicted user sees.
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
     * @param meterRegistry registry the {@code basetool_session_evicted_total} counter is bumped
     *     against
     * @param maximumSessions the configured concurrent-session cap, echoed into the log line so the
     *     reader can tell "the cap is too low" from "the registry is full of dead sessions"
     */
    SessionEvictionLoggingStrategy(@NotNull MeterRegistry meterRegistry, int maximumSessions) {
      this.meterRegistry = meterRegistry;
      this.maximumSessions = maximumSessions;
    }

    /**
     * Counts and logs the eviction, then reproduces Spring Security's default expired-session
     * response verbatim.
     *
     * <p>WARN is the right level: this is not attacker-triggerable noise (reaching it requires a
     * successful authentication) and every occurrence is either a real user losing a session or the
     * registry-leak failure mode described on the class.
     *
     * @param event the expiry event carrying the request/response of the victim's request
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
