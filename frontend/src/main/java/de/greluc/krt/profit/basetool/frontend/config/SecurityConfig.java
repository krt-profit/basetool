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

  /**
   * Main security filter chain. Wires CSP-nonce, bot-protection, session-debug, request-logging and
   * backend-role-sync filters; configures OAuth2 login against Keycloak with smart OIDC logout; and
   * declares the path-by-path permitAll / authenticated matrix. The injected Keycloak issuer URI
   * feeds the CSP {@code form-action} allow-list so the POST-logout redirect to Keycloak's
   * end-session endpoint is not blocked by the browser.
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
    // M-15: SessionDebugFilter is only registered in dev / test (see its {@code @Profile}). In
    // prod the bean does not exist; we skip the addFilterBefore call entirely to keep the chain
    // clean rather than wiring a no-op.
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
        // Audit finding H-6: previously `/missions/**`, `/operations/**`, `/hangar/import/**`,
        // `/hangar/ships/all` and `/inventory/**` were carved out of CSRF protection entirely.
        // Those routes are session-cookie-authenticated frontend handlers — exactly the surface
        // CSRF protects. `SameSite=Strict` on the session cookie mitigates classical cross-site
        // form-POSTs, but defence-in-depth wants the token check enabled too. AJAX call sites
        // attach the CSRF header via the shared `krtCsrf` reader (see `krt-fetch.js`), which a
        // bare-403 self-heals by refetching `GET /csrf` and retrying once; Thymeleaf forms with
        // `th:action` auto-include the `_csrf` hidden field through Spring Security's view
        // integration. The default Spring Security CSRF setup is therefore left intact, no
        // ignoringRequestMatchers needed.
        .csrf(org.springframework.security.config.Customizer.withDefaults())
        .headers(SecurityHeaders.frontend(keycloakIssuerUri))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/missions/*/managers",
                        "/missions/*/managers/*")
                    .authenticated()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.DELETE, "/missions/*/managers/*")
                    .authenticated()
                    // Actuator health endpoint reached by the Docker HEALTHCHECK / compose
                    // service_healthy gating. Other actuator endpoints fall through to the
                    // anyRequest().authenticated() catch-all below.
                    .requestMatchers("/actuator/health", "/actuator/health/**")
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
                        // M-17: static robots.txt with "Disallow: /" so legitimate crawlers
                        // (Google, Bing, archive.org) get an explicit no-index preference
                        // instead of a custom-app-signal 404.
                        "/robots.txt",
                        // Browser-side asset paths that must never trigger an OAuth2 entry-point
                        // (and therefore must never land in HttpSessionRequestCache as a saved
                        // request). Background sourcemap lookups fired by DevTools and browser
                        // extensions (Sentry Replay's /sm/<hash>.map, plus generic /**/*.map
                        // probes against vendor bundles we ship without sourcemaps) used to fall
                        // into the anyRequest().authenticated() catch-all — the saved URL was
                        // then replayed by SavedRequestAwareAuthenticationSuccessHandler after a
                        // user clicked Login, landing them on the custom 404 page instead of /.
                        // Resource handler returns 404 for the missing files, which is the
                        // correct contract for the browser. Defense-in-depth for any asset path
                        // missed here lives in AssetAwareAuthenticationSuccessHandler.
                        "/favicon.ico",
                        "/sm/**",
                        "/**/*.map")
                    .permitAll()
                    .requestMatchers("/missions", "/missions/")
                    .permitAll()
                    .requestMatchers("/missions/**")
                    .permitAll() // Still permitAll for general access, @PreAuthorize or logic
                    // inside handles details
                    // Multiplexed tool-wide live-sync WebSocket (REQ-FE-015, ADR-0094): one socket
                    // per tab carrying every peer-sync topic. Authenticated only — anonymous guests
                    // get no socket (their order creates are relayed server-side instead), and both
                    // publish and subscribe require an authenticated principal; only opaque section
                    // keys cross it and each fragment re-pull re-authorizes per viewer.
                    .requestMatchers("/ws/sync")
                    .authenticated()
                    .requestMatchers("/operations", "/operations/")
                    .permitAll()
                    .requestMatchers("/operations/**")
                    .permitAll()
                    .requestMatchers("/orders", "/orders/")
                    .permitAll()
                    .requestMatchers("/orders/**")
                    .permitAll()
                    // Catalog picker live-search relays (REQ-FE-016): public because the anonymous
                    // order form carries a material picker; they proxy permitAll backend catalog
                    // endpoints and expose only public catalog names (materials / locations).
                    .requestMatchers("/catalog/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .loginPage("/oauth2/authorization/keycloak")
                    // #1041 item 18: count each failed login (with a bounded, exception-type-mapped
                    // reason) into basetool_login_total before the redirect to /?error. A
                    // code-to-token / JWKS / state failure breaks all logins with no other frontend
                    // signal — KeycloakLoginErrorSpike's event regex misses code-to-token errors.
                    .failureHandler(new LoginFailureMetricsHandler(meterRegistry, "/?error"))
                    .successHandler(oauth2LoginSuccessHandler)
                    // ADR-0115: run the authorization_code token exchange on the pool-hardened
                    // OAuth token client (idle-evicting frontend-oauth-pool) instead of
                    // reactor-netty's un-evicting global pool, closing the Keycloak-hairpin
                    // PrematureClose on the login path as well as the refresh path.
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
                        // L-3: POST-only logout. A GET-triggerable /logout is CSRF-able (a
                        // cross-site link could force-logout the victim); requiring POST means the
                        // sidebar's Thymeleaf form must carry the _csrf token. SameSite=Strict
                        // already mitigates this, so it is defense in depth.
                        request ->
                            "POST".equals(request.getMethod())
                                && request
                                    .getRequestURI()
                                    .equals(request.getContextPath() + "/logout"))
                    .logoutSuccessHandler(oidcLogoutSuccessHandler))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(ssoReAuthenticationEntryPoint)
                    // #1041 item 18: count CSRF-token rejections (basetool_csrf_rejections_total)
                    // before the default 403. krtFetch's silent single-retry self-heal otherwise
                    // masks a systematic CSRF-wiring regression as intermittent failed writes.
                    .accessDeniedHandler(new CsrfMetricsAccessDeniedHandler(meterRegistry)))
        // M-14 (+ security audit gap-fill): explicit session-management policy.
        //   * sessionFixation(changeSessionId) is Spring Security's default since 4.x; pinning it
        //     here makes the contract explicit so a future regression that switches to {@code
        //     none} or {@code migrateSession} is visible in code review.
        //   * maximumSessions(MAX_CONCURRENT_SESSIONS) caps a single user to ten concurrent
        //     sessions. The cap exists so a stolen cookie used in parallel eventually pushes the
        //     legitimate session out, but the 30-day idle session TTL
        //     (server.servlet.session.timeout=720h) means long-lived sessions accumulate: a low cap
        //     (was 2) evicted real multi-device/-browser users and surfaced the "session expired
        //     (concurrent logins)" message (2026-07). 10 accommodates realistic
        //     multi-device/-browser use while still bounding parallel cookie abuse.
        //     maxSessionsPreventsLogin(false) keeps the UX as "most recent login wins" rather than
        //     "new login refused" — combined with the cookie SameSite=Strict this is the right
        //     trade-off for a member-facing app.
        //   * expiredSessionStrategy(...) makes the eviction observable. Until it was wired, an
        //     eviction produced no signal at all: Spring's own trace is DEBUG-only on a logger the
        //     app pins to INFO, and basetool_active_sessions cannot see it either because
        //     expireNow() MARKS the SessionInformation rather than deleting the session, so the
        //     gauge does not even dip. See SessionEvictionLoggingStrategy for what it logs/counts
        //     and why the victim-facing response is byte-for-byte unchanged.
        //   * Sessions are Redis-indexed (@EnableRedisIndexedHttpSession), so the default in-memory
        //     SessionRegistryImpl (fed by HttpSessionEventPublisher) NEVER sees them — the cap was
        // a
        //     silent no-op. The Redis-backed SpringSessionBackedSessionRegistry (RedisSessionConfig
        //     #sessionRegistry) is wired in below so the cap is actually enforced and survives a
        //     restart. The bean is absent in the `test` profile (RedisSessionConfig is
        //     @Profile("!test")); there maximumSessions falls back to the default registry, which
        // is
        //     harmless because the suite never exercises concurrent-session eviction.
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
  @Bean
  public org.springframework.security.web.session.HttpSessionEventPublisher
      httpSessionEventPublisher() {
    return new org.springframework.security.web.session.HttpSessionEventPublisher();
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
  @Bean
  public AuthenticationSuccessHandler oauth2LoginSuccessHandler(
      @Value("${app.session.authenticated-timeout:720h}") Duration authenticatedSessionTimeout) {
    // #1041 item 18: the outer metrics handler counts a success into
    // basetool_login_total{outcome="success"} — the denominator FrontendLoginBroken checks against.
    // REQ-SEC-025: the middle handler upgrades the just-authenticated session's idle window from
    // the
    // short anonymous default (which keeps orphan CSRF sessions from accreting) to the long login
    // window, so real members keep the 30-day "stay logged in" behaviour.
    return new LoginSuccessMetricsHandler(
        meterRegistry,
        new SessionLifetimeUpgradeSuccessHandler(
            authenticatedSessionTimeout, new AssetAwareAuthenticationSuccessHandler()));
  }

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
        // Use Keycloak SSO session for silent re-authentication after service restarts.
        // If the Keycloak SSO session is still active, the user is re-authenticated
        // transparently without interaction. If not, Keycloak shows the login page.
        String prompt = request.getParameter("prompt");
        if ("none".equals(prompt)) {
          additionalParameters.put("prompt", "none");
        }
        // Additive "Mit Discord anmelden" path: an `idp=discord` query param (set by the
        // login link) is forwarded to Keycloak as kc_idp_hint so it jumps straight to the
        // Discord identity provider. Only the known alias is honoured — never an arbitrary
        // request value — so the param cannot be used to redirect to an unintended IdP.
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
              // Audit finding H-11: only log the attribute keys, never the values. The attribute
              // map still carries `email` / `preferred_username` (and, on a Keycloak that has not
              // yet had its name mappers removed, possibly `given_name` / `family_name`) — all of
              // which the PiiMasker only partially scrubs and which this package's TRACE-by-default
              // Logback config would emit on every login in production.
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
