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

import de.greluc.krt.profit.basetool.backend.identity.api.SquadronVisibility;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade over the org-unit scope and authorization services: resolves the org-unit context of the
 * current request and answers the see/edit gates used by {@code @PreAuthorize} on the
 * org-unit-scoped aggregates. Every method delegates to one of:
 *
 * <ul>
 *   <li>{@link RequestScopeResolver} — the request-scoped context, {@link ScopePredicate} scope
 *       vectors, memberships and oversight reach.
 *   <li>{@link AccessGateService} — the {@code can*} authorization gates.
 *   <li>{@link OrgUnitStampingService} — create-time owner stamping and owning-org-unit
 *       reassignment (REQ-ORG-018).
 * </ul>
 *
 * <p>Registered as the {@code ownerScopeService} bean that the SpEL expressions reference;
 * read-only transactional.
 */
@Service("ownerScopeService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerScopeService implements SquadronVisibility {

  /**
   * Name of the HTTP request header relaying the caller's active OrgUnit selection; same value as
   * {@link RequestScopeResolver#ACTIVE_ORG_UNIT_HEADER}.
   */
  public static final String ACTIVE_ORG_UNIT_HEADER = RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER;

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final OrgUnitStampingService orgUnitStampingService;

  /**
   * Delegates to {@link RequestScopeResolver#currentSquadronId()}: the org-unit context filtering
   * the current request (admin header pin / non-admin persistent Staffel), or empty when no filter
   * applies.
   *
   * @return the active org-unit id, or empty when no filter applies.
   */
  @NotNull
  public Optional<UUID> currentSquadronId() {
    return requestScopeResolver.currentSquadronId();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentOrgUnitId()}: the plan-aligned alias of {@link
   * #currentSquadronId()}.
   *
   * @return the active org-unit id, or empty when no filter applies.
   */
  @NotNull
  public Optional<UUID> currentOrgUnitId() {
    return requestScopeResolver.currentOrgUnitId();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentScopePredicate()}: the full effective scope
   * vector for the current request (admin-all / admin-pin / non-admin membership union /
   * anonymous).
   *
   * @return a never-null scope vector describing what the current request should see.
   */
  @NotNull
  public ScopePredicate currentScopePredicate() {
    return requestScopeResolver.currentScopePredicate();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUnitOverviewScope()}: the hangar unit-overview
   * scope, which widens a non-pinned OL member to the admin-all read (REQ-HANGAR-003 / ADR-0048).
   *
   * @return the unit-overview scope vector.
   */
  @NotNull
  public ScopePredicate currentUnitOverviewScope() {
    return requestScopeResolver.currentUnitOverviewScope();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserListScopeSquadronIds()}: the SQUADRON-scope
   * set for the admin user-list / search / promotion queries, or {@code null} for the unfiltered
   * all-scope.
   *
   * @return the squadron id set the user-list queries filter on, or {@code null} for the unfiltered
   *     admin/leadership all-scope.
   */
  @Nullable
  @Override
  public Set<UUID> currentUserListScopeSquadronIds() {
    return requestScopeResolver.currentUserListScopeSquadronIds();
  }

  /**
   * Delegates to {@link RequestScopeResolver#hasAmbiguousStaffelContext()}: whether a
   * single-Staffel auto-stamp would be ambiguous for the current multi-Staffel, unpinned caller.
   *
   * @return {@code true} iff a single-Staffel auto-stamp would be ambiguous for the current caller.
   */
  public boolean hasAmbiguousStaffelContext() {
    return requestScopeResolver.hasAmbiguousStaffelContext();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentMemberOrgUnitIds()}: the union of OrgUnit ids
   * the caller is a member of or has cascading leadership reach over.
   *
   * @return the caller's effective membership/cascade org-unit ids, never {@code null}.
   */
  @NotNull
  public Set<UUID> currentMemberOrgUnitIds() {
    return requestScopeResolver.currentMemberOrgUnitIds();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentDirectMembershipOrgUnitIds()}: the caller's
   * direct-membership org-unit ids (no leadership cascade), the "own placed orders" key for the
   * requester-side job-order list (REQ-ORDERS-023).
   *
   * @return the caller's direct-membership org-unit ids, never {@code null}.
   */
  @NotNull
  public Set<UUID> currentDirectMembershipOrgUnitIds() {
    return requestScopeResolver.currentDirectMembershipOrgUnitIds();
  }

  /**
   * Delegates to {@link RequestScopeResolver#canViewJobOrders()}: the viewer-side
   * profit-eligibility gate for the Job-Order area.
   *
   * @return {@code true} iff the caller may view job orders.
   */
  public boolean canViewJobOrders() {
    return requestScopeResolver.canViewJobOrders();
  }

  /**
   * Delegates to {@link RequestScopeResolver#canViewOwnJobOrders()}: whether the caller may view
   * the orders their own org unit requested (the requester-side "Meine Auftr&auml;ge" capability,
   * REQ-ORDERS-023), independent of the profit-eligibility gate.
   *
   * @return {@code true} iff the caller may view the orders their own org unit placed.
   */
  public boolean canViewOwnJobOrders() {
    return requestScopeResolver.canViewOwnJobOrders();
  }

  /**
   * Delegates to {@link RequestScopeResolver#canAccessBlueprintOverview()}: the leadership gate for
   * the org-unit blueprint availability overview.
   *
   * @return {@code true} iff the caller is an admin, an officer, or holds at least one oversight
   *     seat.
   */
  public boolean canAccessBlueprintOverview() {
    return requestScopeResolver.canAccessBlueprintOverview();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentOfficerOrgUnitIds()}: the org units in which
   * the caller holds officer reach through a leadership seat.
   *
   * @return the caller's officer org-unit ids; never {@code null}, possibly empty
   */
  @NotNull
  public Set<UUID> currentOfficerOrgUnitIds() {
    return requestScopeResolver.currentOfficerOrgUnitIds();
  }

  /**
   * Delegates to {@link AccessGateService#canActAsOfficerIn(UUID)}: whether the caller holds
   * officer reach in the target's owning unit.
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller is an admin or holds officer reach in {@code orgUnitId}
   */
  public boolean canActAsOfficerIn(@NotNull UUID orgUnitId) {
    return accessGateService.canActAsOfficerIn(orgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#canActAsOfficerInOwner(UUID)}: officer reach in the
   * target's owning unit, where a target without a unit is reserved to admins.
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is an admin or holds officer reach in {@code orgUnitId}
   */
  public boolean canActAsOfficerInOwner(@Nullable UUID orgUnitId) {
    return accessGateService.canActAsOfficerInOwner(orgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#canActAsLogisticianIn(UUID)}: whether the caller is
   * Logistician of the target's owning unit inside the active scope.
   *
   * @param orgUnitId the target's owning org unit; never {@code null}
   * @return {@code true} iff the caller is Logistician of {@code orgUnitId} in the active scope
   */
  public boolean canActAsLogisticianIn(@NotNull UUID orgUnitId) {
    return accessGateService.canActAsLogisticianIn(orgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#canActAsLogisticianInOwner(UUID)}: Logistician reach in
   * the target's owning unit, where a target without a unit is reserved to admins.
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is an admin or Logistician of {@code orgUnitId}
   */
  public boolean canActAsLogisticianInOwner(@Nullable UUID orgUnitId) {
    return accessGateService.canActAsLogisticianInOwner(orgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#currentLogisticianOrgUnitIds()}: the org units in which
   * the caller holds a Logistician grant inside the active scope.
   *
   * @return the caller's Logistician org-unit ids; never {@code null}, empty for an admin
   */
  @NotNull
  public Set<UUID> currentLogisticianOrgUnitIds() {
    return accessGateService.currentLogisticianOrgUnitIds();
  }

  /**
   * Refuses the caller unless they act as Logistician in the unit that owns the target.
   *
   * @param orgUnit the target's owning org unit, or {@code null} when it has none (admin only)
   * @throws AccessDeniedException when the caller is no Logistician of {@code orgUnit}
   */
  public void requireLogisticianInOwner(@Nullable OrgUnit orgUnit) {
    if (!canActAsLogisticianInOwner(orgUnit == null ? null : orgUnit.getId())) {
      throw new AccessDeniedException("Access denied: not a Logistician of the target's org unit");
    }
  }

  /**
   * Delegates to {@link AccessGateService#currentLogisticianScope()}: the scope vector of the data
   * the caller handles as Logistician, for list queries.
   *
   * @return a never-null scope vector limited to the caller's Logistician units
   */
  @NotNull
  public ScopePredicate currentLogisticianScope() {
    return accessGateService.currentLogisticianScope();
  }

  /**
   * Delegates to {@link AccessGateService#canActAsLogisticianInActiveContext()}: whether the caller
   * handles a unit as Logistician in the pinned unit, or in any unit when nothing is pinned.
   *
   * @return {@code true} iff the caller is an admin or holds a Logistician grant in the context
   */
  public boolean canActAsLogisticianInActiveContext() {
    return accessGateService.canActAsLogisticianInActiveContext();
  }

  /**
   * Delegates to {@link RequestScopeResolver#canActAsOfficerInActiveContext()}: whether the caller
   * holds officer reach in the pinned unit, or in any unit when nothing is pinned.
   *
   * @return {@code true} iff the caller is an admin or holds reach in the active context
   */
  public boolean canActAsOfficerInActiveContext() {
    return requestScopeResolver.canActAsOfficerInActiveContext();
  }

  /**
   * Delegates to {@link AccessGateService#canManageMissionsOf(UUID)}: a Mission Manager grant in
   * the target's owning unit, where a target without a unit needs a grant in an
   * Organisationsleitung or Bereich unit.
   *
   * @param orgUnitId the target's owning org unit, or {@code null} when it has none
   * @return {@code true} iff the caller is an admin or holds the matching grant
   */
  public boolean canManageMissionsOf(@Nullable UUID orgUnitId) {
    return accessGateService.canManageMissionsOf(orgUnitId);
  }

  /**
   * Delegates to {@link RequestScopeResolver#canActAsMissionManagerInActiveContext()}: whether the
   * caller holds a Mission Manager grant in the pinned unit, or in any unit when nothing is pinned.
   *
   * @return {@code true} iff the caller is an admin or holds a grant in the active context
   */
  public boolean canActAsMissionManagerInActiveContext() {
    return requestScopeResolver.canActAsMissionManagerInActiveContext();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentOversightScope()}: the cascading (view)
   * oversight scope backing the blueprint overview and the F1 bank balance view.
   *
   * @return a never-null cascading scope vector of the org units whose data the caller may oversee.
   */
  @NotNull
  public ScopePredicate currentOversightScope() {
    return requestScopeResolver.currentOversightScope();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentOversightScopeIgnoringPin()}: the cascading
   * oversight scope over the caller's whole reach, ignoring the active-org-unit pin.
   *
   * @return a never-null, pin-free cascading oversight scope.
   */
  @NotNull
  public ScopePredicate currentOversightScopeIgnoringPin() {
    return requestScopeResolver.currentOversightScopeIgnoringPin();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserIsOlMember()}: whether the caller holds an
   * {@code OL_MEMBER} seat.
   *
   * @return {@code true} iff the caller has at least one {@code OL_MEMBER} membership.
   */
  public boolean currentUserIsOlMember() {
    return requestScopeResolver.currentUserIsOlMember();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserIsBereichsleiter()}: whether the caller
   * holds a {@code BEREICHSLEITER} seat.
   *
   * @return {@code true} iff the caller has at least one {@code BEREICHSLEITER} membership.
   */
  public boolean currentUserIsBereichsleiter() {
    return requestScopeResolver.currentUserIsBereichsleiter();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserHoldsRoleOnOrgUnit(UUID, MembershipRole)}:
   * whether the caller holds exactly {@code role} on {@code orgUnitId}.
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @param role the membership role to match; never {@code null}
   * @return {@code true} iff the caller has a membership on that unit carrying that role
   */
  public boolean currentUserHoldsRoleOnOrgUnit(
      @NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    return requestScopeResolver.currentUserHoldsRoleOnOrgUnit(orgUnitId, role);
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserIsMemberOfOrgUnit(UUID)}: whether the
   * caller is a member of {@code orgUnitId} at all (any role).
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @return {@code true} iff the caller has any membership on that unit
   */
  public boolean currentUserIsMemberOfOrgUnit(@NotNull UUID orgUnitId) {
    return requestScopeResolver.currentUserIsMemberOfOrgUnit(orgUnitId);
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentSquadron()}: the {@link Squadron} entity for
   * the current effective context, loaded from the DB.
   *
   * @return the {@link Squadron} for the current effective context, or empty when none applies.
   */
  @NotNull
  public Optional<Squadron> currentSquadron() {
    return requestScopeResolver.currentSquadron();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentOrgUnit()}: the {@link OrgUnit} matching the
   * current active context.
   *
   * @return the current effective {@link OrgUnit}, or empty when none applies.
   */
  @NotNull
  public Optional<OrgUnit> currentOrgUnit() {
    return requestScopeResolver.currentOrgUnit();
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveSquadronForPickerOutput(User, UUID)}: the
   * strict (Staffel-only) create-time owner-stamp resolution.
   *
   * @param targetUser the user the new aggregate belongs to; never {@code null}.
   * @param owningOrgUnitId the picker output from the form, or {@code null} when the picker was not
   *     used.
   * @return the Squadron whose stock / aggregate list this row should join; never {@code null}.
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the picker output
   *     is invalid for the target user.
   */
  public Squadron resolveSquadronForPickerOutput(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    return orgUnitStampingService.resolveSquadronForPickerOutput(targetUser, owningOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOrgUnitForPickerOutput(User, UUID)}: the
   * create-time owner-stamp resolution for every org-unit kind.
   *
   * @param targetUser the user whose memberships gate the picker output validation; never {@code
   *     null}.
   * @param owningOrgUnitId the picker-supplied org unit id; {@code null} triggers the auto-stamp
   *     path when the user has exactly one membership.
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException on an invalid picker
   *     output.
   */
  public OrgUnit resolveOrgUnitForPickerOutput(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    return orgUnitStampingService.resolveOrgUnitForPickerOutput(targetUser, owningOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOrgUnitForPickerOutputNullable(User, UUID)}:
   * the nullable-owner variant for the ownerless-personal-aggregate roots (ship, refinery order,
   * inventory item).
   *
   * @param targetUser the user whose memberships gate the picker output; never {@code null}.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used.
   * @return the resolved {@link OrgUnit}, or {@code null} for the ownerless-personal-aggregate
   *     case.
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException for every rejection
   *     other than the ownerless case.
   */
  @Nullable
  public OrgUnit resolveOrgUnitForPickerOutputNullable(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    return orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
        targetUser, owningOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOrgUnitForClientCreate(User)}: the owning org
   * unit of an aggregate a connected application creates (REQ-XCH-017).
   *
   * @param targetUser the member the aggregate is created for
   * @return the member's only org unit, or {@code null} for none or several
   */
  @Nullable
  public OrgUnit resolveOrgUnitForClientCreate(@NotNull User targetUser) {
    return orgUnitStampingService.resolveOrgUnitForClientCreate(targetUser);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOrgUnitForImport(User)}: the owning org unit
   * of the ships a hangar import creates (REQ-HANGAR-014).
   *
   * @param targetUser the member the ships are imported for
   * @return the only membership or the honoured pin, or {@code null} for none
   */
  @Nullable
  public OrgUnit resolveOrgUnitForImport(@NotNull User targetUser) {
    return orgUnitStampingService.resolveOrgUnitForImport(targetUser);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOwnerMembershipTarget(User, UUID)}: the
   * target of a later org-unit change of an owner's aggregate (REQ-HANGAR-009).
   *
   * @param owner the aggregate's owner
   * @param targetOrgUnitId the chosen org unit, or {@code null} for none
   * @return the chosen org unit, or {@code null} for none
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the target is not
   *     a direct membership of the owner
   */
  @Nullable
  public OrgUnit resolveOwnerMembershipTarget(@NotNull User owner, @Nullable UUID targetOrgUnitId) {
    return orgUnitStampingService.resolveOwnerMembershipTarget(owner, targetOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveReassignTargetOrgUnit(UUID)}: validates and
   * resolves an explicit owning-org-unit reassignment (REQ-ORG-018).
   *
   * @param targetOrgUnitId the picker-supplied target org-unit id, or {@code null} for ownerless.
   * @return the resolved managed {@link OrgUnit}, or {@code null} for an ownerless target.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when a non-null target
   *     id does not resolve to a known org unit.
   */
  @Nullable
  public OrgUnit resolveReassignTargetOrgUnit(@Nullable UUID targetOrgUnitId) {
    return orgUnitStampingService.resolveReassignTargetOrgUnit(targetOrgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeSquadron(UUID)}: whether the caller may read data
   * owned by {@code squadronId}.
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  @Override
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return accessGateService.canSeeSquadron(squadronId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditSquadron(UUID)}: whether the caller may write data
   * owned by {@code squadronId}.
   *
   * @param squadronId the squadron whose data the caller wants to write; never {@code null}.
   * @return {@code true} iff the caller may write to the given squadron's data.
   */
  public boolean canEditSquadron(@NotNull UUID squadronId) {
    return accessGateService.canEditSquadron(squadronId);
  }

  /**
   * Delegates to {@link AccessGateService#hasRoleInOrgUnit(UUID, String)}: the contextual-authority
   * check for {@code @PreAuthorize} SpEL.
   *
   * @param orgUnitId the OrgUnit the caller wants to act on; never {@code null}.
   * @param roleName the role to check for; never {@code null}.
   * @return {@code true} iff the caller is an admin or holds the contextual authority.
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    return accessGateService.hasRoleInOrgUnit(orgUnitId, roleName);
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentGrantedOrgUnitIds(String)}: the org units in
   * which the caller holds the contextual role, cascade included.
   *
   * @param roleName the contextual role, e.g. {@code "LOGISTICIAN"}; never {@code null}.
   * @return the org-unit ids of the caller's grants; never {@code null}, possibly empty.
   */
  @NotNull
  public Set<UUID> currentGrantedOrgUnitIds(@NotNull String roleName) {
    return requestScopeResolver.currentGrantedOrgUnitIds(roleName);
  }

  /**
   * Delegates to {@link AccessGateService#permitsOwnedRow}: the scope kernel's per-row gate over a
   * row's per-user owner and owning org unit, for the module access policies (plan §5.4).
   *
   * @param owner the row's per-user owner, or {@code null}
   * @param orgUnit the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @return {@code true} iff the caller may access the row
   */
  public boolean permitsOwnedRow(@Nullable User owner, @Nullable OrgUnit orgUnit, boolean edit) {
    return accessGateService.permitsOwnedRow(owner, orgUnit, edit);
  }
}
