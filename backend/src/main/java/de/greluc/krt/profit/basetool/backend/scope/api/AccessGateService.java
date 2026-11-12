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
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scope kernel's org-unit gates and its per-row check, which the module access policies build
 * on, evaluated against the same scope as {@link RequestScopeResolver} so per-row checks match the
 * scoped lists.
 *
 * <p>Referenced from SpEL as the {@code accessGateService} bean. Read-only transactional.
 */
@Service("accessGateService")
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
   * {@link #canSeeSquadron(UUID)} widened by the caller's grant of {@code roleName}: true also when
   * a Logistician or Mission Manager grant covers {@code squadronId}, cascade included, inside the
   * active context (decision 18, REQ-SEC-005). The grant widens the aggregate checks of the
   * surfaces the role governs; it is no substitute for the membership scope elsewhere.
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}
   * @param roleName the contextual role, {@link Roles#LOGISTICIAN} or {@link Roles#MISSION_MANAGER}
   * @return {@code true} iff the caller sees the unit by membership or by a covering grant
   */
  public boolean canSeeSquadronAsGrantee(@NotNull UUID squadronId, @NotNull String roleName) {
    return canSeeSquadron(squadronId) || holdsCoveringGrant(squadronId, roleName);
  }

  /**
   * Whether a non-admin caller's grant of {@code roleName} covers {@code orgUnitId} inside the
   * active context.
   *
   * @param orgUnitId the unit to cover; never {@code null}
   * @param roleName the contextual role; never {@code null}
   * @return {@code true} iff the unit is in the caller's granted set for the active context
   */
  private boolean holdsCoveringGrant(@NotNull UUID orgUnitId, @NotNull String roleName) {
    return !authHelper.isAdmin()
        && requestScopeResolver
            .currentGrantedOrgUnitIdsInActiveContext(roleName)
            .contains(orgUnitId);
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
   * Checks whether the caller holds the contextual role {@code roleName} in {@code orgUnitId}, the
   * unit that owns the target, inside the active context (REQ-ORG-032): a grant in that unit or in
   * the Bereich or Organisationsleitung above it ({@link
   * RequestScopeResolver#currentGrantedOrgUnitIdsInActiveContext(String)}), so a pin on another
   * unit refuses it. An admin passes within their own scope: every unit unpinned, the pinned unit
   * pinned.
   *
   * @param orgUnitId the org unit the caller wants to act on; never {@code null}
   * @param roleName the role to check (e.g. {@code "LOGISTICIAN"}); never {@code null}
   * @return {@code true} iff the caller holds the role in {@code orgUnitId} in the active context
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    return authHelper.isAdmin()
        ? canEditSquadron(orgUnitId)
        : requestScopeResolver
            .currentGrantedOrgUnitIdsInActiveContext(roleName)
            .contains(orgUnitId);
  }

  /**
   * Checks whether the caller acts as Logistician in {@code orgUnitId}, the unit that owns the
   * target: {@link #hasRoleInOrgUnit(UUID, String)} for {@link Roles#LOGISTICIAN} (REQ-SEC-005).
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller is Logistician of {@code orgUnitId} in the active context
   */
  public boolean canActAsLogisticianIn(@NotNull UUID orgUnitId) {
    return hasRoleInOrgUnit(orgUnitId, Roles.LOGISTICIAN);
  }

  /**
   * {@link #canActAsLogisticianIn(UUID)} for a target that may have no owning unit: such a target
   * is reserved to an admin without a pin (REQ-ORG-032).
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is Logistician of {@code orgUnitId} in the active context,
   *     or the target has no unit and the caller is an admin without a pin
   */
  public boolean canActAsLogisticianInOwner(@Nullable UUID orgUnitId) {
    return orgUnitId == null ? isAdminWithoutPin() : canActAsLogisticianIn(orgUnitId);
  }

  /**
   * Returns the org units whose data the caller handles as Logistician: the units their grants
   * cover, cascade included, inside the active context. An admin holds no grants, so the set is
   * empty for them; callers branch on {@link AuthHelperService#isAdmin()} first.
   *
   * @return the Logistician org-unit ids; never {@code null}, possibly empty
   */
  @NotNull
  public Set<UUID> currentLogisticianOrgUnitIds() {
    return requestScopeResolver.currentGrantedOrgUnitIdsInActiveContext(Roles.LOGISTICIAN);
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
    return currentGrantScope(Roles.LOGISTICIAN);
  }

  /**
   * Returns the scope vector of the data the caller handles through {@code roleName}, for list
   * queries: the admin's own scope for an admin (every unit unpinned, the pinned unit pinned),
   * otherwise exactly the units the caller's grants of that role cover, cascade included, inside
   * the active context (empty set: nothing).
   *
   * @param roleName the contextual role whose grants count, e.g. {@link Roles#MISSION_MANAGER}
   * @return a never-null scope vector limited to the caller's units of that role
   */
  @NotNull
  public ScopePredicate currentGrantScope(@NotNull String roleName) {
    return authHelper.isAdmin()
        ? requestScopeResolver.currentScopePredicate()
        : new ScopePredicate(
            false, null, requestScopeResolver.currentGrantedOrgUnitIdsInActiveContext(roleName));
  }

  /**
   * Whether the caller handles at least one unit as Logistician inside the active scope: the pinned
   * unit when one is pinned, any unit otherwise. Admins always pass. Drives visibility only, never
   * a write.
   *
   * @return {@code true} iff {@link #currentLogisticianScope()} reaches at least one unit
   */
  public boolean canActAsLogisticianInActiveContext() {
    ScopePredicate scope = currentLogisticianScope();
    return scope.adminAllScope()
        || scope.activeOrgUnitId() != null
        || !scope.memberOrgUnitIds().isEmpty();
  }

  /**
   * Checks whether the caller holds officer reach in {@code orgUnitId}, the unit that owns the
   * target, inside the active context (REQ-SEC-015, ADR-0300, REQ-ORG-032): a pin on another unit
   * refuses it. An admin passes within their own scope: every unit unpinned, the pinned unit
   * pinned.
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller holds officer reach in {@code orgUnitId} in the active
   *     context
   */
  public boolean canActAsOfficerIn(@NotNull UUID orgUnitId) {
    return authHelper.isAdmin()
        ? canEditSquadron(orgUnitId)
        : requestScopeResolver
            .narrowToPin(requestScopeResolver.currentOfficerOrgUnitIds())
            .contains(orgUnitId);
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
   * reserved to an admin without a pin (REQ-ORG-032).
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller holds officer reach in {@code orgUnitId} in the active
   *     context, or the target has no unit and the caller is an admin without a pin
   */
  public boolean canActAsOfficerInOwner(@Nullable UUID orgUnitId) {
    return orgUnitId == null ? isAdminWithoutPin() : canActAsOfficerIn(orgUnitId);
  }

  /**
   * Checks whether the caller may manage the missions and operations of {@code orgUnitId}, inside
   * the active context: a Mission Manager grant in that unit ({@link #hasRoleInOrgUnit(UUID,
   * String)}). A target without a unit is managed by a grant in an Organisationsleitung or Bereich
   * unit only, and by an admin without a pin (REQ-SEC-065, REQ-ORG-032).
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller holds the matching Mission Manager grant in the active
   *     context
   */
  public boolean canManageMissionsOf(@Nullable UUID orgUnitId) {
    if (orgUnitId != null) {
      return hasRoleInOrgUnit(orgUnitId, Roles.MISSION_MANAGER);
    }
    return authHelper.isAdmin()
        ? requestScopeResolver.currentPin().isEmpty()
        : requestScopeResolver.holdsLeadershipMissionManagerGrantInActiveContext();
  }

  /**
   * Access check for a personal row without an owning org unit: allowed for an admin without an
   * active pin and for the row's own owner.
   *
   * @param ownerId the row's per-user owner; {@code null} denies all non-admin access
   * @return {@code true} iff the caller may see or edit the row
   */
  private boolean canAccessOwnerlessPersonalRow(@Nullable UUID ownerId) {
    return isAdminWithoutPin() || isCurrentUserOwner(ownerId);
  }

  /**
   * Whether the caller is an admin whose request carries no honoured pin (REQ-ORG-032): the only
   * caller an officer or Logistician gate opens a target without an owning unit to (a Mission
   * Manager gate also admits a leadership grant, {@link #canManageMissionsOf(UUID)}), and one who
   * passes every unit gate, so a gate may answer before loading its target.
   *
   * @return {@code true} iff the caller is an admin without a pin
   */
  public boolean isAdminWithoutPin() {
    return authHelper.isAdmin() && requestScopeResolver.currentPin().isEmpty();
  }

  /**
   * Checks whether the caller is the row's per-user owner, comparing {@link
   * AuthHelperService#currentUserId()} with the actual owner identity.
   *
   * @param ownerId the row's per-user owner; {@code null} never matches
   * @return {@code true} iff the caller is that owner
   */
  private boolean isCurrentUserOwner(@Nullable UUID ownerId) {
    return ownerId != null && authHelper.currentUserId().map(ownerId::equals).orElse(false);
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
    return permitsOwnedIdentity(
        owner == null ? null : owner.getId(), orgUnit == null ? null : orgUnit.getId(), edit);
  }

  /**
   * {@link #permitsOwnedRow(User, OrgUnit, boolean)} widened by a grant of {@code roleName} that
   * covers the row's owning unit, cascade included (decision 18).
   *
   * @param owner the row's per-user owner, or {@code null}
   * @param orgUnit the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @param roleName the contextual role of the surface, {@link Roles#LOGISTICIAN} or {@link
   *     Roles#MISSION_MANAGER}
   * @return {@code true} iff the caller may access the row
   */
  public boolean permitsOwnedRow(
      @Nullable User owner, @Nullable OrgUnit orgUnit, boolean edit, @NotNull String roleName) {
    return permitsOwnedIdentity(
        owner == null ? null : owner.getId(),
        orgUnit == null ? null : orgUnit.getId(),
        edit,
        roleName);
  }

  /**
   * Checks the owner escape and ordinary org-unit scope using current scalar row identities.
   *
   * @param ownerId the current per-user owner, or null
   * @param orgUnitId the current owning unit, or null for ownerless stock
   * @param edit whether the caller needs write access
   * @return whether the actual identity is permitted in the current scope
   */
  public boolean permitsOwnedIdentity(
      @Nullable UUID ownerId, @Nullable UUID orgUnitId, boolean edit) {
    return isCurrentUserOwner(ownerId)
        || (orgUnitId == null
            ? canAccessOwnerlessPersonalRow(ownerId)
            : (edit ? canEditSquadron(orgUnitId) : canSeeSquadron(orgUnitId)));
  }

  /**
   * Widens the current scalar identity scope by its contextual role grant.
   *
   * @param ownerId the current per-user owner, or null
   * @param orgUnitId the current owning unit, or null for ownerless stock
   * @param edit whether the caller needs write access
   * @param roleName the contextual role of the owning surface
   * @return whether the current owner scope or role grant permits access
   */
  public boolean permitsOwnedIdentity(
      @Nullable UUID ownerId, @Nullable UUID orgUnitId, boolean edit, @NotNull String roleName) {
    return permitsOwnedIdentity(ownerId, orgUnitId, edit)
        || (orgUnitId != null && holdsCoveringGrant(orgUnitId, roleName));
  }
}
