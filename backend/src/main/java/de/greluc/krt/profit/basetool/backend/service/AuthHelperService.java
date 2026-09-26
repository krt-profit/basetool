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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * The single access point to the {@link SecurityContextHolder} for the current principal and its
 * role checks; direct access elsewhere is forbidden by {@code ArchitectureTest}.
 */
@Service
@RequiredArgsConstructor
public class AuthHelperService {

  private final RoleHierarchy roleHierarchy;
  private final ApplicationContext applicationContext;

  /**
   * The authorities that mark a caller as an organisation member or above, used by {@link
   * #isMemberOrAbove()}; elevated roles are listed because the hierarchy never implies {@code
   * KRT_MEMBER}.
   */
  private static final Set<String> MEMBER_OR_ABOVE_ROLES =
      Set.of(
          Roles.authority(Roles.ADMIN),
          Roles.authority(Roles.OFFICER),
          Roles.authority(Roles.MISSION_MANAGER),
          Roles.authority(Roles.LOGISTICIAN),
          Roles.authority(Roles.KRT_MEMBER));

  /**
   * Returns the current {@link Authentication}, or empty if no security context is bound, the
   * context contains no authentication, or the authentication is an {@link
   * AnonymousAuthenticationToken}. Callers that explicitly need to inspect the anonymous principal
   * should use {@link #rawAuthentication()}.
   */
  @NotNull
  public Optional<Authentication> currentAuthentication() {
    Authentication auth = rawAuthentication();
    if (auth == null || auth instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }
    return Optional.of(auth);
  }

  /**
   * Returns the current authentication including anonymous tokens. Useful for filter/mapper code
   * that wants to distinguish "no authentication at all" from "anonymous principal".
   */
  @Nullable
  public Authentication rawAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  /** {@code true} if the current request carries an authenticated, non-anonymous principal. */
  public boolean isAuthenticated() {
    Authentication auth = rawAuthentication();
    return auth != null
        && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken);
  }

  /**
   * Returns whether the current authentication holds {@code role} directly or through the {@link
   * RoleHierarchy}; pass the role with the {@code ROLE_} prefix (e.g. {@code "ROLE_LOGISTICIAN"}).
   */
  public boolean hasReachableRole(@NotNull String role) {
    Authentication auth = rawAuthentication();
    if (auth == null) {
      return false;
    }
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities());
    return reachable.stream().anyMatch(a -> role.equals(a.getAuthority()));
  }

  /**
   * Shortcut for {@link #hasReachableRole(String) hasReachableRole("ROLE_LOGISTICIAN")}. Because
   * the role hierarchy declares {@code ROLE_ADMIN > ROLE_LOGISTICIAN} and {@code ROLE_OFFICER >
   * ROLE_LOGISTICIAN}, this returns {@code true} for any of the three elevated roles.
   */
  public boolean isLogisticianOrAbove() {
    return hasReachableRole(Roles.authority(Roles.LOGISTICIAN));
  }

  /**
   * Shortcut for {@link #hasReachableRole(String) hasReachableRole("ROLE_ADMIN")}. Returns {@code
   * true} only for principals that carry the admin role directly - the role hierarchy never grants
   * {@code ROLE_ADMIN} downward, so this is a clean "is admin" check.
   */
  public boolean isAdmin() {
    return hasReachableRole(Roles.authority(Roles.ADMIN));
  }

  /**
   * Programmatic twin of {@link Roles#ADMIN_OR_OFFICER}, evaluated through the {@link
   * RoleHierarchy}.
   *
   * @return {@code true} for an admin or an officer, {@code false} otherwise, including anonymous
   *     and unauthenticated callers
   */
  public boolean isAdminOrOfficer() {
    return isAdmin() || hasReachableRole(Roles.authority(Roles.OFFICER));
  }

  /**
   * Returns whether the caller is an organisation member or holds an elevated role, evaluated
   * through the {@link RoleHierarchy}.
   *
   * <p>Distinct from authentication: pending or rejected registrations and the ingest gateway are
   * authenticated but not members.
   *
   * @return {@code true} iff the caller reaches one of {@link #MEMBER_OR_ABOVE_ROLES}
   */
  public boolean isMemberOrAbove() {
    Authentication auth = rawAuthentication();
    if (auth == null) {
      return false;
    }
    return roleHierarchy.getReachableGrantedAuthorities(auth.getAuthorities()).stream()
        .anyMatch(a -> MEMBER_OR_ABOVE_ROLES.contains(a.getAuthority()));
  }

  /**
   * Returns the UUID of the currently authenticated user, read through {@code AuthenticatedSubject}
   * so a bearer token and the token-less identity an ingest-gateway call installs (ADR-0129) answer
   * alike. Empty when the request is unauthenticated, anonymous, or the subject is not a UUID
   * (defence-in-depth: a malformed subject should not crash the caller).
   */
  @NotNull
  public Optional<UUID> currentUserId() {
    return AuthenticatedSubject.idOf(currentAuthentication().orElse(null));
  }

  /**
   * Returns the caller's squadron context, delegating lazily to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#currentSquadronId()} to avoid a
   * bean cycle.
   *
   * @return the home squadron for non-admins or the switcher selection for admins; empty for admins
   *     in "all squadrons" mode and for unauthenticated callers
   */
  @NotNull
  public Optional<UUID> currentSquadronId() {
    return scope().currentSquadronId();
  }

  /**
   * Plan-compliant convenience accessor for the squadron read-side check — delegates to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#canSeeSquadron(UUID)}. See the
   * delegate for the exact rule (admin without selection always passes; everyone else is compared
   * against the active squadron).
   */
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return scope().canSeeSquadron(squadronId);
  }

  /**
   * Plan-compliant convenience accessor for the squadron write-side check — delegates to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#canEditSquadron(UUID)}.
   */
  public boolean canEditSquadron(@NotNull UUID squadronId) {
    return scope().canEditSquadron(squadronId);
  }

  /**
   * Returns whether the caller may edit the given Staffel or Spezialkommando, delegating to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#canEditOrgUnit(UUID)}.
   *
   * @param orgUnitId the org-unit id (Staffel or Spezialkommando)
   * @return {@code true} iff the caller may edit or label the org unit
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return scope().canEditOrgUnit(orgUnitId);
  }

  private OwnerScopeService scope() {
    return applicationContext.getBean(OwnerScopeService.class);
  }
}
