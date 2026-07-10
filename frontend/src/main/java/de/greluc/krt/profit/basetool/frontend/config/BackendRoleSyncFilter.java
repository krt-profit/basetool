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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/** Servlet filter handling Backend Role Sync. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackendRoleSyncFilter extends OncePerRequestFilter {

  private final BackendApiClient backendApiClient;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();
  private static final String SYNC_COMPLETE_FLAG = "BACKEND_ROLES_SYNCED";
  private static final String APPROVAL_STATE_FLAG = "BACKEND_APPROVAL_STATE";

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

    if (auth != null && auth.isAuthenticated() && auth instanceof OAuth2AuthenticationToken token) {
      HttpSession session = request.getSession(false);
      if (session != null) {
        // Epic #720, Track 1: a PENDING/REJECTED Discord registration is routed to the
        // waiting-for-approval page instead of the (role-less) guest surface — never a 403 storm.
        // Resolved once per session; the backend is the source of truth (the backend also withholds
        // every authority from a pending account, so this redirect is UX, not the access control).
        String approval = (String) session.getAttribute(APPROVAL_STATE_FLAG);
        if (approval == null) {
          approval = fetchApprovalStatus();
          if (approval != null) {
            session.setAttribute(APPROVAL_STATE_FLAG, approval);
          }
        }
        if ("PENDING".equals(approval) || "REJECTED".equals(approval)) {
          if (isApprovalExempt(request)) {
            filterChain.doFilter(request, response);
          } else {
            response.sendRedirect(request.getContextPath() + "/pending-approval");
          }
          return;
        }

        if (session.getAttribute(SYNC_COMPLETE_FLAG) == null) {
          log.debug(
              "Session exists, starting role sync for user: {}", maskPrincipal(token.getName()));
          // Only mark the session synced when the backend role read genuinely succeeded. A
          // Resilience4j fallback (null) or a thrown error must NOT set the flag — otherwise a
          // single backend hiccup on the first request of a session would permanently leave the
          // OidcUser principal without its ROLE_* authorities until the user re-logs in
          // (REQ-SEC-013).
          if (syncRoles(token, request, response)) {
            session.setAttribute(SYNC_COMPLETE_FLAG, true);
          }
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Reads the caller's approval status from the backend once per session. Returns {@code null} on
   * any failure — treated as "not pending" so a backend outage never traps an approved user on the
   * waiting page (the backend still withholds every authority from a genuinely pending account, so
   * this is UX, not the access boundary).
   *
   * @return the approval status ({@code PENDING}/{@code ACTIVE}/{@code REJECTED}), or {@code null}
   *     when it could not be read
   */
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
   * Whether the request must NOT be redirected to the waiting page — the page itself (else it
   * loops), logout, the OAuth endpoints, the error page and static assets.
   *
   * @param request the current request
   * @return {@code true} when the request is exempt from the pending-approval redirect
   */
  private static boolean isApprovalExempt(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals("/pending-approval")
        || path.startsWith("/logout")
        || path.startsWith("/oauth2")
        || path.startsWith("/login")
        || path.startsWith("/error")
        || path.startsWith("/actuator")
        || path.startsWith("/css/")
        || path.startsWith("/js/")
        || path.startsWith("/images/")
        || path.startsWith("/logos/")
        || path.startsWith("/fonts/")
        || path.equals("/favicon.ico")
        || path.endsWith(".map");
  }

  /**
   * Reads the caller's backend roles/permissions via {@code /api/v1/users/me} and, when new
   * authorities are found, rebuilds the {@link OidcUser} principal plus the {@link
   * OAuth2AuthenticationToken} so the principal object carries the same {@code ROLE_*} the token
   * already does.
   *
   * @param token the current OAuth2 authentication to enrich
   * @param request the servlet request, used to persist the rebuilt security context
   * @param response the servlet response, used to persist the rebuilt security context
   * @return {@code true} when the backend read succeeded (a non-null user came back, whether or not
   *     anything was added); {@code false} when the call returned no user or threw, which signals
   *     the caller to leave the session unsynced and retry on the next request
   */
  private boolean syncRoles(
      OAuth2AuthenticationToken token, HttpServletRequest request, HttpServletResponse response) {
    try {
      log.debug("Syncing backend roles for user: {}", maskPrincipal(token.getName()));
      UserDto user = backendApiClient.get("/api/v1/users/me", UserDto.class);

      if (user == null) {
        // Resilience4j fallback returns null when the backend is unavailable (e.g. mid-deploy).
        // Returning false leaves SYNC_COMPLETE_FLAG unset so the next request retries instead of
        // poisoning the session with a principal that never received its ROLE_* (REQ-SEC-013).
        log.warn(
            "Backend role sync skipped: /api/v1/users/me returned no user for {}; leaving the"
                + " session unsynced so the next request retries.",
            maskPrincipal(token.getName()));
        return false;
      }

      log.debug(
          "Roles received from backend: {} role(s)",
          user.roles() == null ? 0 : user.roles().size());
      List<GrantedAuthority> updatedAuthorities = new ArrayList<>(token.getAuthorities());
      boolean modified = false;

      // Sync roles from backend database
      if (user.roles() != null) {
        for (String roleName : user.roles()) {
          String formattedRole = Roles.authority(roleName.toUpperCase().replace(" ", "_"));
          if (updatedAuthorities.stream().noneMatch(a -> a.getAuthority().equals(formattedRole))) {
            log.debug(
                "Adding {} from backend to user: {}",
                formattedRole,
                maskPrincipal(token.getName()));
            updatedAuthorities.add(new SimpleGrantedAuthority(formattedRole));
            modified = true;
          }
        }
      }

      // Sync permissions from backend database
      if (user.permissions() != null) {
        for (String permission : user.permissions()) {
          if (updatedAuthorities.stream().noneMatch(a -> a.getAuthority().equals(permission))) {
            log.debug(
                "Adding permission {} from backend to user: {}",
                permission,
                maskPrincipal(token.getName()));
            updatedAuthorities.add(new SimpleGrantedAuthority(permission));
            modified = true;
          }
        }
      }

      // Sync special flags
      if (Boolean.TRUE.equals(user.isLogistician())
          && updatedAuthorities.stream()
              .noneMatch(a -> a.getAuthority().equals(Roles.authority(Roles.LOGISTICIAN)))) {
        log.info(
            "Adding ROLE_LOGISTICIAN from backend to user: {}", maskPrincipal(token.getName()));
        updatedAuthorities.add(new SimpleGrantedAuthority(Roles.authority(Roles.LOGISTICIAN)));
        modified = true;
      }

      if (Boolean.TRUE.equals(user.isMissionManager())
          && updatedAuthorities.stream()
              .noneMatch(a -> a.getAuthority().equals(Roles.authority(Roles.MISSION_MANAGER)))) {
        log.info(
            "Adding ROLE_MISSION_MANAGER from backend to user: {}", maskPrincipal(token.getName()));
        updatedAuthorities.add(new SimpleGrantedAuthority(Roles.authority(Roles.MISSION_MANAGER)));
        modified = true;
      }

      if (modified) {
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
        log.debug("No new roles to add for user: {}", maskPrincipal(token.getName()));
      }

      return true;
    } catch (BackendServiceException | ReauthenticationRequiredException e) {
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
}
