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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scope kernel's org-unit gates and its per-row check, which the module access policies build
 * on, evaluated against the same scope as {@link RequestScopeResolver} so per-row checks match the
 * scoped lists.
 *
 * <p>Invoked from SpEL through the {@link OwnerScopeService} facade. Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessGateService {

  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;

  /**
   * Checks whether the caller may see data owned by the org unit {@code squadronId} (Staffel or
   * Spezialkommando), via {@link RequestScopeResolver#currentScopePredicate()}.
   *
   * <ul>
   *   <li>Admin without a pin: every org unit.
   *   <li>Pinned caller: only the pinned org unit.
   *   <li>Non-admin without a pin: every org unit they are a member of.
   * </ul>
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may see the org unit's data
   */
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return requestScopeResolver.currentScopePredicate().permits(squadronId);
  }

  /**
   * Checks whether the caller may write data owned by {@code squadronId}; currently the same rule
   * as {@link #canSeeSquadron(UUID)}.
   *
   * @param squadronId the org unit whose data the caller wants to write; never {@code null}
   * @return {@code true} iff the caller may write the org unit's data
   */
  public boolean canEditSquadron(@NotNull UUID squadronId) {
    return canSeeSquadron(squadronId);
  }

  /**
   * Checks whether the caller holds the contextual authority {@code (roleName, orgUnitId)} ({@link
   * de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority}); admins always
   * pass.
   *
   * @param orgUnitId the org unit the caller wants to act on; never {@code null}
   * @param roleName the role to check (e.g. {@code "LOGISTICIAN"}); never {@code null}
   * @return {@code true} iff the caller is an admin or holds the contextual authority
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    if (authHelper.isAdmin()) {
      return true;
    }
    Optional<Authentication> authentication = authHelper.currentAuthentication();
    if (authentication.isEmpty()
        || !authentication.get().isAuthenticated()
        || authentication.get().getAuthorities() == null) {
      return false;
    }
    OrgUnitContextualAuthority target = new OrgUnitContextualAuthority(roleName, orgUnitId);
    for (GrantedAuthority a : authentication.get().getAuthorities()) {
      if (a instanceof OrgUnitContextualAuthority ctx && ctx.equals(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks whether the caller acts as Logistician in {@code orgUnitId}, the unit that owns the
   * target: the contextual {@code LOGISTICIAN} grant of that unit, inside the active scope (a pin
   * to another unit refuses it); admins pass within their own scope (REQ-SEC-005).
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller is Logistician of {@code orgUnitId} in the active scope
   */
  public boolean canActAsLogisticianIn(@NotNull UUID orgUnitId) {
    return hasRoleInOrgUnit(orgUnitId, Roles.LOGISTICIAN) && canEditSquadron(orgUnitId);
  }

  /**
   * {@link #canActAsLogisticianIn(UUID)} for a target that may have no owning unit: such a target
   * is reserved to admins.
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is an admin or Logistician of {@code orgUnitId}
   */
  public boolean canActAsLogisticianInOwner(@Nullable UUID orgUnitId) {
    return orgUnitId == null ? authHelper.isAdmin() : canActAsLogisticianIn(orgUnitId);
  }

  /**
   * Returns the org units whose data the caller handles as Logistician: the units of the caller's
   * contextual {@code LOGISTICIAN} grants inside the active scope. An admin holds no grants, so the
   * set is empty for them; callers branch on {@link AuthHelperService#isAdmin()} first.
   *
   * @return the Logistician org-unit ids; never {@code null}, possibly empty
   */
  @NotNull
  public Set<UUID> currentLogisticianOrgUnitIds() {
    ScopePredicate scope = requestScopeResolver.currentScopePredicate();
    Set<UUID> ids = new LinkedHashSet<>();
    authHelper
        .currentAuthentication()
        .ifPresent(authentication -> {
          for (GrantedAuthority a : authentication.getAuthorities()) {
            if (a instanceof OrgUnitContextualAuthority ctx
                && Roles.LOGISTICIAN.equals(ctx.roleName())
                && scope.permits(ctx.orgUnitId())) {
              ids.add(ctx.orgUnitId());
            }
          }
        });
    return ids;
  }

  /**
   * Returns the scope vector of the data the caller handles as Logistician, for list queries: the
   * admin's own scope for an admin, otherwise exactly the {@link #currentLogisticianOrgUnitIds()}
   * (empty set: nothing).
   *
   * @return a never-null scope vector limited to the caller's Logistician units
   */
  @NotNull
  public ScopePredicate currentLogisticianScope() {
    return authHelper.isAdmin()
        ? requestScopeResolver.currentScopePredicate()
        : new ScopePredicate(false, null, currentLogisticianOrgUnitIds());
  }

  /**
   * Checks whether the caller holds officer reach in {@code orgUnitId}, the unit that owns the
   * target (REQ-SEC-015, ADR-0300); admins always pass.
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller is an admin or {@code orgUnitId} is one of {@link
   *     RequestScopeResolver#currentOfficerOrgUnitIds()}
   */
  public boolean canActAsOfficerIn(@NotNull UUID orgUnitId) {
    if (authHelper.isAdmin()) {
      return true;
    }
    return requestScopeResolver.currentOfficerOrgUnitIds().contains(orgUnitId);
  }

  /**
   * Checks whether the caller holds officer reach in at least one org unit; the coarse method gate
   * in front of a write whose owning unit is checked afterwards with {@link
   * #canActAsOfficerIn(UUID)}. Admins always pass.
   *
   * @return {@code true} iff the caller is an admin or holds officer reach in any unit
   */
  public boolean canActAsOfficerAnywhere() {
    return authHelper.isAdmin() || !requestScopeResolver.currentOfficerOrgUnitIds().isEmpty();
  }

  /**
   * {@link #canActAsOfficerIn(UUID)} for a target that may have no owning unit: such a target is
   * reserved to admins.
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is an admin or holds officer reach in {@code orgUnitId}
   */
  public boolean canActAsOfficerInOwner(@Nullable UUID orgUnitId) {
    return orgUnitId == null ? authHelper.isAdmin() : canActAsOfficerIn(orgUnitId);
  }

  /**
   * Access check for a personal row without an owning org unit: allowed for an admin without an
   * active pin and for the row's own owner.
   *
   * @param owner the row's per-user owner; {@code null} denies all non-admin access
   * @return {@code true} iff the caller may see or edit the row
   */
  private boolean canAccessOwnerlessPersonalRow(@Nullable User owner) {
    if (authHelper.isAdmin() && requestScopeResolver.readActiveSquadronFromHeader().isEmpty()) {
      return true;
    }
    return isCurrentUserOwner(owner);
  }

  /**
   * Checks whether the caller is the row's per-user owner, comparing {@link
   * AuthHelperService#currentUserId()} with {@code owner.getId()}.
   *
   * @param owner the row's per-user owner; {@code null} never matches
   * @return {@code true} iff the caller is that owner
   */
  private boolean isCurrentUserOwner(@Nullable User owner) {
    return owner != null
        && owner.getId() != null
        && authHelper.currentUserId().map(uid -> uid.equals(owner.getId())).orElse(false);
  }

  /**
   * Checks whether the caller may access a row with the given per-user owner and owning org unit:
   * the owner escape (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)} or,
   * with {@code edit}, {@link #canEditSquadron(UUID)}.
   *
   * @param owner the row's per-user owner, or {@code null}
   * @param orgUnit the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @return {@code true} iff the caller may access the row
   */
  public boolean permitsOwnedRow(@Nullable User owner, @Nullable OrgUnit orgUnit, boolean edit) {
    return isCurrentUserOwner(owner)
        || (orgUnit == null
            ? canAccessOwnerlessPersonalRow(owner)
            : (edit ? canEditSquadron(orgUnit.getId()) : canSeeSquadron(orgUnit.getId())));
  }
}
