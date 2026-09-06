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
 * Centralised access point for the Spring Security {@link SecurityContextHolder}.
 *
 * <p>This service is the single seam through which controllers, mappers and non-{@code UserService}
 * business code may consult the currently authenticated principal. Touching {@link
 * SecurityContextHolder} directly from anywhere else is forbidden by an ArchUnit rule (see {@code
 * ArchitectureTest}) — the rationale is laid out in CLAUDE.md: the same business method must not
 * behave differently depending on which thread invokes it (scheduling, async, message listeners),
 * and concentrating the read here makes the contract testable without a full Spring security
 * context.
 *
 * <p>The helper deliberately exposes a narrow surface — just enough to cover the role-hierarchy
 * checks the controllers used to inline ({@link #isLogisticianOrAbove()} etc.) plus a small set of
 * generic primitives ({@link #currentAuthentication()}, {@link #isAuthenticated()}, {@link
 * #hasReachableRole(String)}).
 */
@Service
@RequiredArgsConstructor
public class AuthHelperService {

  private final RoleHierarchy roleHierarchy;
  private final ApplicationContext applicationContext;

  /**
   * The authorities that mark a caller as a registered organisation member (or a role above it). A
   * caller that reaches none of these — i.e. an anonymous request OR an authenticated but role-less
   * {@code GUEST} account — is treated as a mission "outsider" by {@link #isMemberOrAbove()}. The
   * elevated roles are listed explicitly (not only {@code ROLE_KRT_MEMBER}) because the role
   * hierarchy promotes {@code ADMIN}/{@code OFFICER} to {@code LOGISTICIAN}/{@code MISSION_MANAGER}
   * but never down to {@code KRT_MEMBER}.
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
   * {@code true} if the current authentication can reach {@code role} via the configured {@link
   * RoleHierarchy}. Pass the role with the {@code ROLE_} prefix (e.g. {@code "ROLE_LOGISTICIAN"}).
   *
   * <p>"Reachable" means: either the principal has the role directly, or the role is implied by a
   * higher role in the hierarchy (e.g. {@code ROLE_ADMIN} reaches {@code ROLE_LOGISTICIAN} because
   * {@code SecurityConfig} declares {@code ROLE_ADMIN > ROLE_LOGISTICIAN}).
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
   * {@code true} when the current caller is a registered organisation member or holds an elevated
   * role ({@code KRT_MEMBER}/{@code MEMBER}/{@code LOGISTICIAN}/{@code MISSION_MANAGER}/{@code
   * OFFICER}/{@code ADMIN}), evaluated through the configured {@link RoleHierarchy}.
   *
   * <p>Kept as a distinct question from {@code isAuthenticated()} even though the two now agree on
   * nearly every caller. Since ADR-0159 an account that maps to no application role is refused with
   * {@code 403 NO_ROLE} before a handler runs, so the gap between "authenticated" and "member" has
   * shrunk to the PENDING/REJECTED registration and to whatever authority set a future integration
   * introduces. Membership is the honest predicate for the questions that ask it — the mission
   * description (REQ-SEC-041), the live-sync rooms — and collapsing it into authentication would
   * make each of those depend on a refusal happening earlier in the chain.
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
    // Through the seam, not through getName(). This method predates the ingest gateway's identity
    // swap and happened to keep working for it only because the acting member's name equals its
    // sub — an accident, not a contract. Reading getName() is the idiom that split every consumer
    // into fail-open and fail-closed when a second authentication type appeared (ADR-0129), and it
    // is the one that would put a callsign here for a username/password caller (REQ-OBS-004).
    return AuthenticatedSubject.idOf(currentAuthentication().orElse(null));
  }

  /**
   * Plan-compliant convenience accessor for the caller's squadron context — delegates to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#currentSquadronId()}
   * (MULTI_SQUADRON_PLAN.md section 4.1 expects this on {@code AuthHelperService}). The injection
   * is lazy via the {@link org.springframework.context.ApplicationContext} so that the bean wiring
   * does not introduce a circular {@code AuthHelperService -> OwnerScopeService -> ... ->
   * AuthHelperService} dependency at startup. Callers in hot paths should resolve the context once
   * per request rather than per filtered row.
   *
   * @return active squadron id for non-admins (their persistent home squadron) or for admins with
   *     an active switcher selection; {@code Optional.empty()} for admins in "all squadrons" mode
   *     and for unauthenticated callers.
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
   * Plan-compliant convenience accessor for the org-unit write-side check — delegates to {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#canEditOrgUnit(UUID)}. Unlike
   * {@link #canEditSquadron(UUID)}, the id may reference either a Staffel or a Spezialkommando;
   * used by the guest org-unit-labeling gate when a participant is tagged with org units of either
   * kind.
   *
   * @param orgUnitId the org-unit id (Staffel or Spezialkommando) to check write access for.
   * @return {@code true} iff the current caller may edit/label the given org unit.
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return scope().canEditOrgUnit(orgUnitId);
  }

  private de.greluc.krt.profit.basetool.backend.service.OwnerScopeService scope() {
    return applicationContext.getBean(
        de.greluc.krt.profit.basetool.backend.service.OwnerScopeService.class);
  }
}
