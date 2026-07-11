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

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/** Spring configuration for Security. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {
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

  // CSP migration milestone: the ~200 inline event-handler attributes (onclick="…",
  // onchange="…", onsubmit="…", oninput="…", onkeyup="…") that historically pinned us to a
  // {@code script-src-attr 'unsafe-inline'} allowance have been moved to delegated handlers
  // via the {@code data-trigger}-based dispatcher in {@code event-delegation.js} +
  // {@code common-handlers.js} and per-page {@code krtEvents.on(...)} bindings in template
  // {@code <script th:attr="nonce=${cspNonce}">} blocks. With zero inline {@code on*=}
  // attributes remaining in the templates ({@code grep} verified), the policy drops
  // {@code script-src-attr} entirely — the directive defaults to {@code 'none'} when omitted
  // (CSP3 spec, MDN <a href=
  // "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/script-src-attr"
  // >script-src-attr</a>), which slams the door on stored-XSS via a future template that
  // accidentally re-introduces an inline event handler. {@code <script>} elements stay
  // nonce-gated through {@code script-src} below — unchanged.
  // Audit findings M-9 + M-10:
  //   * M-10: dropped the broad {@code https:} fallback from {@code script-src}. Browsers that
  //     understand {@code 'strict-dynamic'} (Chrome 52+, Firefox 52+, Safari 15.4+) ignore the
  //     fallback anyway, but older browsers fell back to "any HTTPS origin loads scripts" — that
  //     widened the policy unnecessarily for &lt;1% of installed-base browsers. With the fallback
  //     removed the policy is uniformly strict across browsers.
  //   * M-9: added {@code form-action 'self'} (an injected {@code &lt;form action=evil&gt;} cannot
  //     POST any visible field — including the CSRF token — to a third-party origin) and {@code
  //     upgrade-insecure-requests} (auto-rewrites HTTP subresources to HTTPS so a mixed-content
  //     bug never falls back to plain HTTP). The Keycloak origin (derived per-environment from the
  //     OIDC issuer-uri) is appended to {@code form-action} at runtime in {@code
  //     cspNonceHeaderWriter}: the POST {@code /logout} form's success redirect targets Keycloak's
  //     cross-origin {@code end_session_endpoint}, and with {@code 'self'} alone Chromium blocks
  //     that redirect — the local session is cleared but the Keycloak SSO session stays alive, so
  //     the next login silently re-authenticates (the regression after logout became POST-only).
  // {@code style-src} no longer carries {@code 'unsafe-inline'} (audit finding L-3): every {@code
  // <style>} block in the templates renders with {@code th:attr="nonce=${cspNonce}"}, so an
  // injected {@code <style>} tag from a stored-XSS vector cannot be evaluated. The former {@code
  // style-src-attr 'unsafe-inline'} fallback for inline {@code style=""} attributes has been
  // dropped to {@code 'none'}: all inline style attributes were migrated out of the templates —
  // static values to the generated {@code inline-migration.css} classes, and the data-driven ones
  // (modal show/hide, opacity/colour toggles → {@code th:classappend} classes; progress-bar widths
  // → a {@code data-krtm-width} attribute applied via the CSSOM in {@code inline-style-apply.js},
  // which {@code style-src-attr} does not govern). With no {@code style=""} attribute remaining, an
  // injected one is now blocked by the browser, closing the CSS-injection residual entirely.
  private static final String CSP_TEMPLATE =
      "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
          + "form-action %2$s; upgrade-insecure-requests; "
          + "img-src 'self' data:; font-src 'self' data:; "
          + "style-src 'self' 'nonce-%1$s'; "
          + "style-src-attr 'none'; "
          + "script-src 'nonce-%1$s' 'strict-dynamic'";

  /**
   * Builds the per-request CSP header writer. The nonce is substituted per request; the {@code
   * form-action} source list is computed once here (this method runs a single time while the filter
   * chain is assembled). {@code 'self'} covers every same-origin form in the app. The Keycloak
   * origin is appended because exactly one form submits cross-origin: the POST {@code /logout},
   * whose success redirect targets Keycloak's {@code end_session_endpoint}. Without the Keycloak
   * origin in {@code form-action}, Chromium blocks that redirect — the local Spring session is
   * cleared but the Keycloak SSO session survives, so the next login silently re-authenticates
   * instead of prompting for credentials.
   *
   * @param issuerUri the configured Keycloak issuer URI, used to derive the allowed logout-redirect
   *     origin
   * @return a header writer that emits the {@code Content-Security-Policy} response header
   */
  private org.springframework.security.web.header.HeaderWriter cspNonceHeaderWriter(
      String issuerUri) {
    String keycloakOrigin = keycloakOriginOf(issuerUri);
    String formAction = keycloakOrigin.isEmpty() ? "'self'" : "'self' " + keycloakOrigin;
    return (request, response) -> {
      Object nonceAttr = request.getAttribute(CspNonceFilter.REQUEST_ATTRIBUTE);
      String nonce = nonceAttr != null ? nonceAttr.toString() : "";
      response.setHeader("Content-Security-Policy", String.format(CSP_TEMPLATE, nonce, formAction));
    };
  }

  /**
   * Derives the origin ({@code scheme://host[:port]}) from the configured OIDC issuer URI, for use
   * in the CSP {@code form-action} directive. Keycloak's {@code end_session_endpoint} shares the
   * issuer's origin, so this is precisely the origin the POST-logout redirect must be allowed to
   * reach.
   *
   * @param issuerUri the configured Keycloak issuer URI; may be {@code null}, blank, or unparseable
   * @return the {@code scheme://host[:port]} origin, or an empty string if it cannot be derived (in
   *     which case {@code form-action} stays {@code 'self'}-only)
   */
  private static String keycloakOriginOf(String issuerUri) {
    if (issuerUri == null || issuerUri.isBlank()) {
      return "";
    }
    try {
      java.net.URI uri = java.net.URI.create(issuerUri.trim());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null) {
        return "";
      }
      return uri.getPort() == -1
          ? scheme + "://" + host
          : scheme + "://" + host + ":" + uri.getPort();
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Could not derive the Keycloak origin from issuer-uri '{}' for the CSP form-action"
              + " directive; the POST /logout redirect to Keycloak may be blocked by the browser.",
          issuerUri);
      return "";
    }
  }

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
      AuthenticationSuccessHandler oauth2LoginSuccessHandler)
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
        .headers(
            headers -> {
              headers.addHeaderWriter(cspNonceHeaderWriter(keycloakIssuerUri));
              headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::deny);
              headers.referrerPolicy(
                  ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
              // M-12: Cross-Origin-Opener-Policy + Cross-Origin-Resource-Policy. Same rationale
              // as the backend — COOP isolates the browsing context group, CORP prevents
              // cross-origin embedding of frontend resources via {@code <img>} / {@code <script>}.
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
              // Audit finding H-9: explicit HSTS. Frontend is reached over HTTPS in production;
              // behind nginx-proxy-manager with X-Forwarded-Proto the default writer works,
              // but pinning the policy here makes the contract explicit and prod-safe even if
              // the proxy headers are misconfigured.
              headers.httpStrictTransportSecurity(
                  hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000L));
              headers.addHeaderWriter(
                  new org.springframework.security.web.header.writers.StaticHeadersWriter(
                      "Permissions-Policy",
                      // L-3: explicit deny for every browser feature the app does not use.
                      "geolocation=(), camera=(), microphone=(), fullscreen=(),"
                          + " payment=(), usb=(), serial=(), bluetooth=(), accelerometer=(),"
                          + " gyroscope=(), magnetometer=(), display-capture=(),"
                          + " clipboard-read=(), clipboard-write=(), interest-cohort=()"));
              headers.contentTypeOptions(Customizer.withDefaults());
            })
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
                    // Mission-detail presence WebSocket: only authenticated users can join the
                    // awareness channel (anonymous guests browsing a mission detail page must
                    // not appear as "editors" and must not see who is editing). The OIDC
                    // session is reused by the WebSocket handshake.
                    .requestMatchers("/ws/missions/**")
                    .authenticated()
                    // Materialbörse board live-sync WebSocket: authenticated members only. Only the
                    // opaque "board" section key crosses the socket and each peer re-pulls its own
                    // KRT_MEMBER-gated board fragment, so an authenticated guest learns nothing
                    // here.
                    .requestMatchers("/ws/materialboerse/**")
                    .authenticated()
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
        //   * maximumSessions(10) caps a single user to ten concurrent sessions. The cap exists so
        //     a stolen cookie used in parallel eventually pushes the legitimate session out, but
        // the
        //     30-day idle session TTL (server.servlet.session.timeout=720h) means long-lived
        //     sessions accumulate: a low cap (was 2) evicted real multi-device/-browser users and
        //     surfaced the "session expired (concurrent logins)" message (2026-07). 10 accommodates
        //     realistic multi-device/-browser use while still bounding parallel cookie abuse.
        //     maxSessionsPreventsLogin(false) keeps the UX as "most recent login wins" rather than
        //     "new login refused" — combined with the cookie SameSite=Strict this is the right
        //     trade-off for a member-facing app.
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
                      .maximumSessions(10)
                      .maxSessionsPreventsLogin(false);
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
}
