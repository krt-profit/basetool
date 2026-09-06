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

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter handling Backend Role Sync.
 *
 * <p>Two pieces of session state drive it, and <strong>both are refreshed on a TTL rather than
 * resolved once per session</strong> (REQ-SEC-013). Pinning either for the session's whole lifetime
 * is what forced a freshly approved Discord member to log out and back in — twice: once because the
 * session still carried the {@code PENDING} verdict cached before the admin decided, and once more
 * because the roles/units the admin assigned afterwards never reached the already-synced principal.
 * With an authenticated session living 720&nbsp;h (REQ-SEC-025) neither ever self-healed.
 *
 * <ul>
 *   <li><b>Approval state</b> — {@code ACTIVE} is terminal (the backend only ever decides a still-
 *       {@code PENDING} registration) and stays cached for free; a non-terminal {@code
 *       PENDING}/{@code REJECTED} verdict is re-read every {@link #APPROVAL_RECHECK_MILLIS}, so an
 *       approval lands within seconds without any re-login.
 *   <li><b>Backend roles/permissions</b> — re-synced every {@link #ROLE_RESYNC_MILLIS}, and
 *       immediately on the {@code PENDING → ACTIVE} transition, so authorities granted after the
 *       session started reach the principal on the next request.
 * </ul>
 *
 * <p><b>The sync reconciles in both directions</b>: it adds what the backend now grants and drops
 * what it no longer grants, so a revoked role or permission leaves the principal on the next
 * re-sync instead of lingering for the session's lifetime and rendering UI that only 403s. Which
 * authorities may be dropped, and why the rule differs between {@code ROLE_*} and everything else,
 * is documented on {@link #syncRoles}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackendRoleSyncFilter extends OncePerRequestFilter {

  private final BackendApiClient backendApiClient;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  /** Session attribute holding the epoch-millis stamp of the last successful backend role sync. */
  static final String ROLES_SYNCED_AT_FLAG = "BACKEND_ROLES_SYNCED_AT";

  /**
   * Session attribute holding the authority names the last successful sync asserted. It is what
   * lets a later sync tell a permission <em>this filter</em> granted apart from a login-owned
   * {@code OIDC_USER} / {@code SCOPE_*} authority, and therefore revoke the former without ever
   * being able to strip the latter (ADR-0122).
   */
  static final String SYNCED_AUTHORITIES_FLAG = "BACKEND_SYNCED_AUTHORITIES";

  /** Session attribute holding the last resolved approval status. */
  static final String APPROVAL_STATE_FLAG = "BACKEND_APPROVAL_STATE";

  /** Session attribute holding the epoch-millis stamp of the last approval-status read. */
  static final String APPROVAL_CHECKED_AT_FLAG = "BACKEND_APPROVAL_CHECKED_AT";

  /** Approved registration — the terminal state, cached for the rest of the session. */
  static final String STATE_ACTIVE = "ACTIVE";

  /** Registration awaiting an admin decision. */
  private static final String STATE_PENDING = "PENDING";

  /** Registration declined by an admin. */
  private static final String STATE_REJECTED = "REJECTED";

  /**
   * The account is approved but holds no application role (REQ-SEC-053, ADR-0159).
   *
   * <p>Not a registration status: the backend never sends it in {@code approvalStatus}. It is
   * derived from the refusal — a role-less caller is answered {@code 403 NO_ROLE} on every
   * {@code /api} path except the three exempt ones, so the role sync's own read of
   * {@code /api/v1/users/me} is where the frontend meets it. Kept in the same session attribute as
   * the approval verdict because it routes to the same page and expires the same way.
   */
  static final String STATE_NO_ROLE = "NO_ROLE";

  /** The stable problem code the backend answers a role-less caller with (REQ-SEC-053). */
  private static final String NO_ROLE_CODE = "NO_ROLE";

  /** Path of the waiting page a non-approved registration is routed to. */
  private static final String PENDING_APPROVAL_PATH = "/pending-approval";

  /**
   * How long a non-terminal ({@code PENDING}/{@code REJECTED}) approval verdict may be reused
   * before the backend is asked again. Short enough that an admin decision reaches the waiting user
   * essentially live, and it costs nothing in the normal case: an {@code ACTIVE} session never
   * re-reads at all, and a non-approved user can only reach the waiting page anyway.
   */
  private static final long APPROVAL_RECHECK_MILLIS = 15_000L;

  /**
   * How long the backend-derived roles/permissions on the principal may age before {@link
   * #syncRoles} runs again. Bounds how long a role, permission or org-unit membership granted
   * mid-session stays invisible to the frontend's {@code sec:authorize} / {@code @PreAuthorize}
   * gates; one {@code /api/v1/users/me} read per minute per active session is the price.
   */
  private static final long ROLE_RESYNC_MILLIS = 60_000L;

  /**
   * Returns a stable, non-reversible 8-hex-char digest of the OIDC principal name suitable for log
   * correlation. CLAUDE.md "Never log names, emails, or tokens" — every log line in this filter
   * routes the principal through this helper instead of dumping {@code token.getName()} verbatim.
   * Deterministic per name within a JVM run; collisions across users are statistically irrelevant
   * for the short-lived correlation window the logs are read against.
   *
   * @param name OIDC principal name (typically the JWT {@code sub}); may be {@code null} or empty.
   * @return a short tag like {@code "u-1a2b3c4d"}, or {@code "<anon>"} for null/empty input.
   */
  private static String maskPrincipal(String name) {
    if (name == null || name.isEmpty()) {
      return "<anon>";
    }
    return String.format("u-%08x", name.hashCode());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth != null
        && auth.isAuthenticated()
        && auth instanceof OAuth2AuthenticationToken token
        && !isStaticAsset(request)) {
      HttpSession session = request.getSession(false);
      if (session != null) {
        // Epic #720, Track 1: a PENDING/REJECTED Discord registration is routed to the
        // account-status page rather than left to collect 403s. REQ-SEC-053 adds a third state to
        // the same routing: an approved account holding no role, which reaches the identical dead
        // end for a different reason and needs different words for it. The backend is the source of
        // truth for all three (it withholds every authority in each case), so this redirect is UX,
        // not the access control.
        String approval = resolveApprovalState(session);
        if (STATE_PENDING.equals(approval)
            || STATE_REJECTED.equals(approval)
            || STATE_NO_ROLE.equals(approval)) {
          if (isApprovalExempt(request)) {
            filterChain.doFilter(request, response);
          } else {
            response.sendRedirect(request.getContextPath() + PENDING_APPROVAL_PATH);
          }
          return;
        }

        // REQ-SEC-028: while the Terms-of-Use gate is closed for this session, the backend refuses
        // /api/v1/users/me with 403 TERMS_NOT_ACCEPTED. The failure path below deliberately leaves
        // the sync stamp unset so the next request retries, which turns "unconsented" into one
        // futile round trip per non-static request — for every member at once, right after a
        // wording change. Skipping it costs nothing: the sync could not have succeeded, and the
        // moment consent is recorded the cached verdict is cleared and the next request syncs.
        if (TermsAcceptanceGateFilter.consentKnownMissing(request)) {
          filterChain.doFilter(request, response);
          return;
        }

        if (isDue(session.getAttribute(ROLES_SYNCED_AT_FLAG), ROLE_RESYNC_MILLIS)) {
          log.debug(
              "Session exists, starting role sync for user: {}", maskPrincipal(token.getName()));
          // Only stamp the session when the backend role read genuinely succeeded. A Resilience4j
          // fallback (null) or a thrown error must NOT stamp it — otherwise a single backend hiccup
          // would leave the OidcUser principal without its ROLE_* authorities for a whole
          // re-sync interval instead of retrying on the very next request (REQ-SEC-013).
          if (syncRoles(token, session, request, response)) {
            session.setAttribute(ROLES_SYNCED_AT_FLAG, System.currentTimeMillis());
          }
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Resolves the caller's approval status for this request, re-reading it from the backend when the
   * cached verdict is non-terminal and older than {@link #APPROVAL_RECHECK_MILLIS} (REQ-SEC-013).
   *
   * <p>{@link #STATE_ACTIVE} is terminal — the backend refuses to decide anything but a
   * still-{@code PENDING} registration — so an approved session short-circuits here without ever
   * touching the backend again. A {@code PENDING}/{@code REJECTED} verdict is the one that can
   * change underneath a live session, so it is the one that expires; caching it for the session's
   * whole 720&nbsp;h lifetime is what used to strand an approved member on the waiting page until
   * they logged out and back in.
   *
   * <p>When the re-read observes the {@code → ACTIVE} transition it also drops the role-sync stamp,
   * so the authorities the approval unlocks are pulled on this very request rather than up to
   * {@link #ROLE_RESYNC_MILLIS} later.
   *
   * @param session the current (non-{@code null}) HTTP session carrying the cached verdict
   * @return the approval status, or {@code null} when it has never been read successfully — treated
   *     as "not pending" by the caller so a backend outage never traps an approved user
   */
  @Nullable
  private String resolveApprovalState(@NotNull HttpSession session) {
    String cached = (String) session.getAttribute(APPROVAL_STATE_FLAG);
    if (STATE_ACTIVE.equals(cached)) {
      return cached;
    }
    if (cached != null
        && !isDue(session.getAttribute(APPROVAL_CHECKED_AT_FLAG), APPROVAL_RECHECK_MILLIS)) {
      return cached;
    }

    String fresh = fetchApprovalStatus();
    if (fresh == null) {
      // Backend unreadable: keep whatever we had rather than downgrading a known verdict, and leave
      // the stamp untouched so the next request retries instead of waiting out the interval.
      return cached;
    }
    session.setAttribute(APPROVAL_STATE_FLAG, fresh);
    session.setAttribute(APPROVAL_CHECKED_AT_FLAG, System.currentTimeMillis());

    if (STATE_ACTIVE.equals(fresh) && cached != null) {
      session.removeAttribute(ROLES_SYNCED_AT_FLAG);
      log.info("Registration approved mid-session; re-syncing backend roles on this request.");
    }
    return fresh;
  }

  /**
   * Drops the cached approval verdict so the next request re-reads it from the backend instead of
   * reusing one that the caller already knows is stale.
   *
   * <p>Exists for exactly one caller: {@code PendingApprovalPageController} redirects an {@code
   * ACTIVE} caller off the waiting page, and the waiting page is reached precisely because this
   * filter believes the caller is not approved. Without this, the redirect and the filter disagree
   * for up to {@link #APPROVAL_RECHECK_MILLIS} and bounce the browser between {@code /} and {@code
   * /pending-approval} until the cached verdict expires — the redirect is served from the session,
   * so it costs no backend read and the loop runs at full speed straight into the browser's
   * redirect cap. Clearing both attributes puts {@link #resolveApprovalState} back on its
   * never-read path, where a successful read admits the caller and a failed one yields {@code null}
   * (not pending) rather than resurrecting the stale verdict, so neither outcome can loop.
   *
   * @param session the caller's session, or {@code null} when there is none — then a no-op, since a
   *     session-less request carries no cached verdict to begin with
   */
  public static void forgetApprovalVerdict(@Nullable HttpSession session) {
    if (session == null) {
      return;
    }
    session.removeAttribute(APPROVAL_STATE_FLAG);
    session.removeAttribute(APPROVAL_CHECKED_AT_FLAG);
  }

  /**
   * Whether a periodically refreshed piece of session state is stale and must be re-read.
   *
   * @param stamp the stored epoch-millis stamp of the last successful read; anything that is not a
   *     {@link Number} (absent attribute, or a boolean flag written by a pre-upgrade session that
   *     survived a deploy in Redis) counts as never read. {@link Number} rather than {@code Long}
   *     because the session round-trips through a JSON serializer, which may hand back a different
   *     numeric type — and a mismatch there would silently degrade into a read per request.
   * @param intervalMillis the maximum age the stored value may reach
   * @return {@code true} when the value must be re-read — including when the stamp lies in the
   *     future, so a backwards clock jump cannot freeze the refresh
   */
  private static boolean isDue(@Nullable Object stamp, long intervalMillis) {
    if (!(stamp instanceof Number stampMillis)) {
      return true;
    }
    long age = System.currentTimeMillis() - stampMillis.longValue();
    return age >= intervalMillis || age < 0;
  }

  /**
   * Reads the caller's approval status from the backend. Returns {@code null} on any failure —
   * treated as "not pending" so a backend outage never traps an approved user on the waiting page
   * (the backend still withholds every authority from a genuinely pending account, so this is UX,
   * not the access boundary). Call frequency is bounded by {@link #resolveApprovalState}, which
   * caches the verdict and only lets a non-terminal one expire.
   *
   * @return the approval status ({@code PENDING}/{@code ACTIVE}/{@code REJECTED}), or {@code null}
   *     when it could not be read
   */
  @Nullable
  private String fetchApprovalStatus() {
    try {
      RegistrationStatusDto dto =
          backendApiClient.get("/api/v1/users/me/registration-status", RegistrationStatusDto.class);
      return dto == null ? null : dto.approvalStatus();
    } catch (BackendServiceException e) {
      // Boundary already logged it; this probe re-runs on every request until it succeeds, so keep
      // the expected backend-unavailable case at DEBUG to avoid per-request WARN spam
      // (REQ-OBS-001).
      log.debug("Could not read approval status; treating as non-pending for this request.", e);
      return null;
    } catch (Exception e) {
      log.warn("Could not read approval status; treating as non-pending for this request.", e);
      return null;
    }
  }

  /**
   * Whether the request must NOT be redirected to the waiting page — the waiting page itself and
   * its status poll (else it loops / the poll would fetch HTML), logout, the OAuth endpoints, the
   * error page and static assets.
   *
   * @param request the current request
   * @return {@code true} when the request is exempt from the pending-approval redirect
   */
  private static boolean isApprovalExempt(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals(PENDING_APPROVAL_PATH)
        || path.startsWith(PENDING_APPROVAL_PATH + "/")
        || path.startsWith("/logout")
        || path.startsWith("/oauth2")
        || path.startsWith("/login")
        || path.startsWith("/error")
        || path.startsWith("/actuator")
        || isStaticAsset(request);
  }

  /**
   * Whether the request targets a static asset, in which case the filter skips its whole body.
   *
   * <p>The approval and role state are now refreshed on a TTL instead of once per session, so
   * without this every CSS/JS/font request of a page load would be a candidate for a backend read.
   * Skipping assets keeps the refresh cost at roughly one read per interval per session while still
   * letting every real navigation (and the waiting page's status poll) drive it.
   *
   * @param request the current request
   * @return {@code true} for static-asset paths the filter has nothing to do for
   */
  private static boolean isStaticAsset(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.startsWith("/images/")
        || path.startsWith("/logos/")
        || path.startsWith("/fonts/")
        || path.equals("/favicon.ico")
        || path.endsWith(".map");
  }

  /**
   * Reads the caller's backend roles/permissions via {@code /api/v1/users/me} and reconciles the
   * {@link OAuth2AuthenticationToken}'s authorities against them — adding what the backend now
   * grants and <b>dropping what it no longer grants</b> — rebuilding the {@link OidcUser} principal
   * plus the token whenever the set actually changed.
   *
   * <p>The reconciliation is asymmetric by design, because the two kinds of authority have
   * different owners (ADR-0122):
   *
   * <ul>
   *   <li><b>{@code ROLE_*}</b> — the backend response is authoritative for the entire role
   *       vocabulary. Its local {@code role} catalog is where realm roles are mirrored to and is
   *       exactly what the backend's own {@code @PreAuthorize} gates consult, so a role the backend
   *       no longer reports is one it will no longer honour, and leaving it on the principal only
   *       renders UI that 403s. A role dropped in Keycloak reaches that mirror with the next
   *       access-token refresh, and this sync then drops it from the session too. Technical realm
   *       roles that have no catalog entry ({@code offline_access}, {@code default-roles-*}) fall
   *       away with it — nothing gates on them.
   *   <li><b>Everything else</b> — only what a <em>previous</em> sync asserted may be dropped,
   *       tracked in {@link #SYNCED_AUTHORITIES_FLAG}. Permission strings carry no prefix and are
   *       therefore indistinguishable from the login-owned {@code OIDC_USER} / {@code SCOPE_*}
   *       authorities; keying the removal on what this filter itself granted makes it structurally
   *       impossible to strip one of those.
   * </ul>
   *
   * <p>A failed read changes nothing: the caller leaves the session unstamped and retries.
   *
   * @param token the current OAuth2 authentication to reconcile
   * @param session the current session, carrying (and receiving) the set of authorities the last
   *     successful sync asserted
   * @param request the servlet request, used to persist the rebuilt security context
   * @param response the servlet response, used to persist the rebuilt security context
   * @return {@code true} when the backend read succeeded (a non-null user came back, whether or not
   *     anything changed); {@code false} when the call returned no user or threw, which signals the
   *     caller to leave the session unstamped and retry on the next request
   */
  private boolean syncRoles(
      OAuth2AuthenticationToken token,
      HttpSession session,
      HttpServletRequest request,
      HttpServletResponse response) {
    try {
      log.debug("Syncing backend roles for user: {}", maskPrincipal(token.getName()));
      UserDto user = backendApiClient.get("/api/v1/users/me", UserDto.class);

      if (user == null) {
        // Resilience4j fallback returns null when the backend is unavailable (e.g. mid-deploy).
        // Returning false leaves the sync stamp unset so the next request retries instead of
        // poisoning the session with a principal that never received its ROLE_* (REQ-SEC-013).
        log.warn(
            "Backend role sync skipped: /api/v1/users/me returned no user for {}; leaving the"
                + " session unsynced so the next request retries.",
            maskPrincipal(token.getName()));
        return false;
      }

      BackendAuthorities backend = BackendAuthorities.of(user);
      log.debug("Backend asserted {} authority/authorities", backend.asserted().size());

      List<GrantedAuthority> updatedAuthorities =
          reconcile(token.getAuthorities(), backend, previouslyAsserted(session));
      session.setAttribute(SYNCED_AUTHORITIES_FLAG, new ArrayList<>(backend.asserted()));

      boolean modified = !names(updatedAuthorities).equals(names(token.getAuthorities()));
      if (modified) {
        log.info(
            "Backend role sync changed the authorities of user {}: {} -> {} (grants and revocations"
                + " both applied).",
            maskPrincipal(token.getName()),
            token.getAuthorities().size(),
            updatedAuthorities.size());
        OAuth2AuthenticationToken newAuth;
        if (token.getPrincipal() instanceof OidcUser oidcUser) {
          // We must preserve the nameAttributeKey to avoid changing the principal name,
          // which would break OAuth2AuthorizedClient lookups.
          String nameAttributeKey = "sub"; // Default
          String currentName = oidcUser.getName();

          if (currentName != null) {
            if (currentName.equals(oidcUser.getPreferredUsername())) {
              nameAttributeKey = "preferred_username";
            } else if (currentName.equals(oidcUser.getEmail())) {
              nameAttributeKey = "email";
            } else {
              // Search for the key that matches the current name
              for (java.util.Map.Entry<String, Object> entry :
                  oidcUser.getAttributes().entrySet()) {
                if (currentName.equals(String.valueOf(entry.getValue()))) {
                  nameAttributeKey = entry.getKey();
                  break;
                }
              }
            }
          }

          log.debug(
              "Using nameAttributeKey: {} for new OidcUser (current name: {})",
              nameAttributeKey,
              maskPrincipal(currentName));
          OidcUser newPrincipal =
              new DefaultOidcUser(
                  updatedAuthorities,
                  oidcUser.getIdToken(),
                  oidcUser.getUserInfo(),
                  nameAttributeKey);

          if (!newPrincipal.getName().equals(currentName)) {
            log.warn(
                "Principal name changed during sync! Old: {}, New: {}. This may break OAuth2"
                    + " lookups.",
                maskPrincipal(currentName),
                maskPrincipal(newPrincipal.getName()));
          }

          newAuth =
              new OAuth2AuthenticationToken(
                  newPrincipal, updatedAuthorities, token.getAuthorizedClientRegistrationId());
        } else {
          newAuth =
              new OAuth2AuthenticationToken(
                  token.getPrincipal(),
                  updatedAuthorities,
                  token.getAuthorizedClientRegistrationId());
        }

        newAuth.setDetails(token.getDetails());
        log.info(
            "Replaced Authentication in SecurityContext for user: {} (New name: {})",
            maskPrincipal(token.getName()),
            maskPrincipal(newAuth.getName()));

        org.springframework.security.core.context.SecurityContext context =
            SecurityContextHolder.getContext();
        context.setAuthentication(newAuth);
        securityContextRepository.saveContext(context, request, response);
      } else {
        log.debug(
            "Authorities already match the backend for user: {}", maskPrincipal(token.getName()));
      }

      return true;
    } catch (BackendServiceException | ReauthenticationRequiredException e) {
      // REQ-SEC-053: the role read is the frontend's first meeting with a role-less account — the
      // backend refuses it with 403 NO_ROLE like every other /api call. Cached as the session's
      // verdict so the next request routes to the account-status page instead of repeating the
      // same refusal on every navigation, and expiring on the same interval as the approval verdict
      // so an administrator granting a role reaches the member without a re-login.
      if (e instanceof BackendServiceException backendFailure
          && NO_ROLE_CODE.equals(backendFailure.getProblemCode())) {
        session.setAttribute(APPROVAL_STATE_FLAG, STATE_NO_ROLE);
        session.setAttribute(APPROVAL_CHECKED_AT_FLAG, System.currentTimeMillis());
        log.info("Backend refused the role sync with NO_ROLE; routing to the account-status page.");
        return false;
      }
      // REQ-OBS-001: the BackendApiClient boundary already logged this once (5xx=ERROR, 4xx=WARN,
      // circuit-open=DEBUG). syncRoles re-runs on EVERY request until it succeeds
      // (SYNC_COMPLETE_FLAG
      // is only set on success), so re-logging a relayed backend failure at ERROR here turns one
      // backend outage into a per-request ERROR storm and undoes the #1203 circuit-open-at-DEBUG
      // design. Defer quietly; the principal gains ROLE_* on the next good request.
      log.debug(
          "Backend role sync deferred (backend unavailable) for user: {}",
          maskPrincipal(token.getName()),
          e);
      return false;
    } catch (Exception e) {
      log.error("Failed to sync backend roles for user: {}", maskPrincipal(token.getName()), e);
      return false;
    }
  }

  /**
   * The authority set {@code /api/v1/users/me} asserts for the caller, plus the two flags saying
   * whether the response may be treated as authoritative for each kind (ADR-0122). A {@code null}
   * collection means "the backend said nothing about this", which must never be read as "the
   * backend revoked everything" — so the corresponding removals are skipped for that request.
   *
   * @param asserted every authority name the backend currently grants — {@code ROLE_*} from the
   *     role catalog and the two membership flags, plus the flattened permission strings
   * @param rolesAuthoritative whether the response carried a role list, and its {@code ROLE_*} may
   *     therefore drive revocation
   * @param permissionsAuthoritative whether the response carried a permission list, and previously
   *     asserted permissions may therefore be revoked
   */
  private record BackendAuthorities(
      Set<String> asserted, boolean rolesAuthoritative, boolean permissionsAuthoritative) {

    /**
     * Derives the asserted authority set from a backend user DTO: each catalog role name mapped to
     * its {@code ROLE_UPPER_SNAKE_CASE} authority, the flattened permission strings verbatim (they
     * carry no prefix by design — {@code hasAuthority} checks them directly), and {@code
     * ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER} for the two org-unit membership flags.
     *
     * @param user the backend's view of the caller; never {@code null}
     * @return the asserted authorities plus their authoritativeness flags
     */
    private static BackendAuthorities of(@NotNull UserDto user) {
      Set<String> asserted = new LinkedHashSet<>();
      if (user.roles() != null) {
        user.roles().stream()
            .map(role -> Roles.authority(role.toUpperCase(Locale.ROOT).replace(" ", "_")))
            .forEach(asserted::add);
      }
      if (user.permissions() != null) {
        asserted.addAll(user.permissions());
      }
      if (Boolean.TRUE.equals(user.isLogistician())) {
        asserted.add(Roles.authority(Roles.LOGISTICIAN));
      }
      if (Boolean.TRUE.equals(user.isMissionManager())) {
        asserted.add(Roles.authority(Roles.MISSION_MANAGER));
      }
      return new BackendAuthorities(asserted, user.roles() != null, user.permissions() != null);
    }
  }

  /**
   * Reconciles the token's current authorities against what the backend asserts, applying the two
   * ownership rules documented on {@link #syncRoles}: the backend owns the whole {@code ROLE_*}
   * vocabulary, while a non-role authority may only be dropped when a previous sync is the one that
   * granted it. Order is preserved and duplicates collapse, so the result is stable across requests
   * and the {@code modified} comparison does not churn.
   *
   * @param current the authorities on the token right now
   * @param backend what the backend asserts, with its authoritativeness flags
   * @param previouslyAsserted the authority names the last successful sync asserted
   * @return the reconciled authority list
   */
  @NotNull
  private static List<GrantedAuthority> reconcile(
      @NotNull Collection<? extends GrantedAuthority> current,
      @NotNull BackendAuthorities backend,
      @NotNull Set<String> previouslyAsserted) {
    List<GrantedAuthority> reconciled = new ArrayList<>();
    Set<String> kept = new LinkedHashSet<>();

    for (GrantedAuthority authority : current) {
      String name = authority.getAuthority();
      boolean revoked;
      if (name.startsWith(Roles.ROLE_PREFIX)) {
        revoked = backend.rolesAuthoritative() && !backend.asserted().contains(name);
      } else {
        revoked =
            backend.permissionsAuthoritative()
                && previouslyAsserted.contains(name)
                && !backend.asserted().contains(name);
      }
      if (!revoked && kept.add(name)) {
        reconciled.add(authority);
      }
    }

    for (String name : backend.asserted()) {
      if (kept.add(name)) {
        reconciled.add(new SimpleGrantedAuthority(name));
      }
    }
    return reconciled;
  }

  /**
   * Reads back the authority names the last successful sync asserted. Tolerates anything else on
   * the attribute (absent, or a value shaped differently by an older release whose session survived
   * a deploy in Redis) by reporting an empty set, which only means "revoke nothing this time".
   *
   * @param session the current session
   * @return the previously asserted authority names; never {@code null}
   */
  @NotNull
  private static Set<String> previouslyAsserted(@NotNull HttpSession session) {
    if (!(session.getAttribute(SYNCED_AUTHORITIES_FLAG) instanceof Collection<?> stored)) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object entry : stored) {
      if (entry instanceof String name) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Projects an authority collection onto its bare names for order-insensitive comparison, so the
   * security context is only rebuilt (and written back to the session) when the set genuinely
   * changed.
   *
   * @param authorities the authorities to project
   * @return the authority names as a set
   */
  @NotNull
  private static Set<String> names(@NotNull Collection<? extends GrantedAuthority> authorities) {
    return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
  }
}
