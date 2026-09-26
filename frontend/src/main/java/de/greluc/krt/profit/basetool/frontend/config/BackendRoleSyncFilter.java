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
 * Servlet filter that keeps the session's approval state and backend authorities current
 * (REQ-SEC-013).
 *
 * <ul>
 *   <li>Approval state: {@code ACTIVE} is terminal and cached; a {@code PENDING} or {@code
 *       REJECTED} verdict is re-read every {@link #APPROVAL_RECHECK_MILLIS}.
 *   <li>Roles and permissions: re-synced every {@link #ROLE_RESYNC_MILLIS} and immediately on the
 *       transition to {@code ACTIVE}, adding and removing authorities as described on {@link
 *       #syncRoles}.
 * </ul>
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
   * Session attribute holding the authority names the last successful sync asserted, so only
   * authorities this filter granted are ever revoked (ADR-0122).
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
   * Gate state for an approved account holding no application role (REQ-SEC-053). Derived from the
   * backend's {@code 403 NO_ROLE}, never sent as an {@code approvalStatus}.
   */
  static final String STATE_NO_ROLE = "NO_ROLE";

  /**
   * Checks whether the session's cached gate verdict is "approved, but holding no role"
   * (REQ-SEC-053).
   *
   * @param session the current session, or {@code null} when there is none
   * @return {@code true} iff the cached verdict is the role-less one
   */
  public static boolean isRoleLess(@Nullable HttpSession session) {
    return session != null && STATE_NO_ROLE.equals(session.getAttribute(APPROVAL_STATE_FLAG));
  }

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
   * Returns a stable, non-reversible 8-hex-char tag of the principal name for log correlation, so
   * the name itself is never logged.
   *
   * @param name OIDC principal name; may be {@code null} or empty
   * @return a tag like {@code "u-1a2b3c4d"}, or {@code "<anon>"} for null or empty input
   */
  @NotNull
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
        String approval = resolveApprovalState(session);
        if (STATE_PENDING.equals(approval)
            || STATE_REJECTED.equals(approval)
            || STATE_NO_ROLE.equals(approval)) {
          routeToAccountStatus(request, response, filterChain);
          return;
        }

        if (TermsAcceptanceGateFilter.consentKnownMissing(request)) {
          filterChain.doFilter(request, response);
          return;
        }

        if (isDue(session.getAttribute(ROLES_SYNCED_AT_FLAG), ROLE_RESYNC_MILLIS)) {
          log.debug(
              "Session exists, starting role sync for user: {}", maskPrincipal(token.getName()));
          if (syncRoles(token, session, request, response)) {
            session.setAttribute(ROLES_SYNCED_AT_FLAG, System.currentTimeMillis());
          } else if (STATE_NO_ROLE.equals(session.getAttribute(APPROVAL_STATE_FLAG))) {
            routeToAccountStatus(request, response, filterChain);
            return;
          }
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Resolves the caller's approval status, re-reading it when the cached verdict is non-terminal
   * and older than {@link #APPROVAL_RECHECK_MILLIS} (REQ-SEC-013). On a transition to {@code
   * ACTIVE} it also forces a role re-sync on this request.
   *
   * @param session the current session carrying the cached verdict
   * @return the approval status, or {@code null} when never read successfully (treated as not
   *     pending)
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
   * Drops the cached approval verdict so the next request re-reads it, preventing a redirect loop
   * when the waiting page sends an approved caller away.
   *
   * @param session the caller's session, or {@code null}, which is a no-op
   */
  public static void forgetApprovalVerdict(@Nullable HttpSession session) {
    if (session == null) {
      return;
    }
    session.removeAttribute(APPROVAL_STATE_FLAG);
    session.removeAttribute(APPROVAL_CHECKED_AT_FLAG);
  }

  /**
   * Checks whether a periodically refreshed session value must be re-read.
   *
   * @param stamp the epoch-millis stamp of the last successful read; anything not a {@link Number}
   *     counts as never read
   * @param intervalMillis the maximum age the value may reach
   * @return {@code true} when the value must be re-read, including when the stamp lies in the
   *     future
   */
  private static boolean isDue(@Nullable Object stamp, long intervalMillis) {
    if (!(stamp instanceof Number stampMillis)) {
      return true;
    }
    long age = System.currentTimeMillis() - stampMillis.longValue();
    return age >= intervalMillis || age < 0;
  }

  /**
   * Reads the caller's approval status from the backend; any failure yields {@code null}, treated
   * as not pending.
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
    String path = PublicPaths.relativePath(request);
    return path.equals(PENDING_APPROVAL_PATH)
        || path.startsWith(PENDING_APPROVAL_PATH + "/")
        || PublicPaths.isGateExempt(path);
  }

  /**
   * Redirects the caller to the account-status page, unless the request is {@linkplain
   * #isApprovalExempt exempt}, in which case it continues down the chain.
   *
   * @param request the current request
   * @param response the response the redirect is written to
   * @param filterChain the chain an exempt request continues down
   * @throws ServletException propagated from an exempt request's downstream chain
   * @throws IOException propagated from writing the redirect or the downstream chain
   */
  private static void routeToAccountStatus(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (isApprovalExempt(request)) {
      filterChain.doFilter(request, response);
    } else {
      response.sendRedirect(request.getContextPath() + PENDING_APPROVAL_PATH);
    }
  }

  /**
   * Checks whether the request targets an asset or public document, for which the filter does
   * nothing. Narrower than {@link #isApprovalExempt}: the OAuth callback is still processed.
   *
   * @param request the current request
   * @return {@code true} for paths the filter has nothing to do for
   */
  private static boolean isStaticAsset(HttpServletRequest request) {
    return PublicPaths.isAssetOrPublicDocument(PublicPaths.relativePath(request));
  }

  /**
   * Reads the caller's roles and permissions from {@code /api/v1/users/me} and reconciles the
   * token's authorities, rebuilding the principal and token when the set changed (ADR-0122).
   *
   * <ul>
   *   <li>{@code ROLE_*}: the backend is authoritative; roles it no longer reports are removed.
   *   <li>Other authorities: only those a previous sync asserted ({@link #SYNCED_AUTHORITIES_FLAG})
   *       may be removed, so login-owned authorities are never stripped.
   * </ul>
   *
   * @param token the current OAuth2 authentication to reconcile
   * @param session the session holding the previously asserted authorities
   * @param request the servlet request, for persisting the rebuilt security context
   * @param response the servlet response, for persisting the rebuilt security context
   * @return {@code true} when the backend read succeeded; {@code false} when it returned no user or
   *     threw, so the caller retries
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
          String nameAttributeKey = "sub";
          String currentName = oidcUser.getName();

          if (currentName != null) {
            if (currentName.equals(oidcUser.getPreferredUsername())) {
              nameAttributeKey = "preferred_username";
            } else if (currentName.equals(oidcUser.getEmail())) {
              nameAttributeKey = "email";
            } else {
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
      if (e instanceof BackendServiceException backendFailure
          && BackendServiceException.CODE_NO_ROLE.equals(backendFailure.getProblemCode())) {
        session.setAttribute(APPROVAL_STATE_FLAG, STATE_NO_ROLE);
        session.setAttribute(APPROVAL_CHECKED_AT_FLAG, System.currentTimeMillis());
        log.info("Backend refused the role sync with NO_ROLE; routing to the account-status page.");
        return false;
      }
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
   * The authorities {@code /api/v1/users/me} asserts, with flags saying whether roles and
   * permissions may drive revocation; a missing collection never means "revoke everything".
   *
   * @param asserted every authority name the backend grants
   * @param rolesAuthoritative whether the response carried a role list
   * @param permissionsAuthoritative whether the response carried a permission list
   */
  private record BackendAuthorities(
      Set<String> asserted, boolean rolesAuthoritative, boolean permissionsAuthoritative) {

    /**
     * Derives the asserted authorities from a backend user: {@code ROLE_*} for each catalog role,
     * the permission strings verbatim, and {@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER}
     * for the membership flags.
     *
     * @param user the backend's view of the caller; never {@code null}
     * @return the asserted authorities plus their authoritativeness flags
     */
    @NotNull
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
   * Reconciles the token's authorities against the backend's by the rules on {@link #syncRoles},
   * preserving order and collapsing duplicates.
   *
   * @param current the authorities on the token now
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
   * Reads the authority names the last successful sync asserted; an absent or unexpected value
   * yields an empty set.
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
   * Projects authorities onto their names for order-insensitive comparison.
   *
   * @param authorities the authorities to project
   * @return the authority names as a set
   */
  @NotNull
  private static Set<String> names(@NotNull Collection<? extends GrantedAuthority> authorities) {
    return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
  }
}
