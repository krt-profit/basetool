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

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delegating facade over the org-unit scope / authorization services (L3 split, #922). Resolves the
 * org-unit context of the current request and answers the "may the caller see / edit this
 * org-unit-scoped data?" questions that gate every {@code @PreAuthorize} on the org-unit-scoped
 * aggregates (mission, hangar, inventory, refinery, operation, job order).
 *
 * <p>This bean was decomposed into three focused collaborators, each holding a byte-for-byte
 * verbatim slice of the former god-class; this facade keeps every public method + signature and
 * forwards each one-line:
 *
 * <ul>
 *   <li>{@link RequestScopeResolver} — the request-scoped context core: the active-context header /
 *       persistent Staffel, the {@link ScopePredicate} scope vectors, the caller's membership rows,
 *       the cascading / own-level oversight reach and the promotion-feature flags, plus the
 *       per-request memoisation they share.
 *   <li>{@link AccessGateService} — the {@code can*} authorization gates evaluated from SpEL as
 *       {@code @ownerScopeService.canX(...)}.
 *   <li>{@link OrgUnitStampingService} — the create-time owner-stamping (SPEZIALKOMMANDO_PLAN.md
 *       §5.5.1 picker matrix) and the explicit owning-org-unit reassignment (REQ-ORG-018).
 * </ul>
 *
 * <p>Bean identity: this is the {@code ownerScopeService} bean (auto-named from the class), so the
 * existing {@code @PreAuthorize("@ownerScopeService.canX(...)")} SpEL strings and the {@code
 * SquadronScopeService} compatibility shim keep resolving unchanged. The class-level
 * {@code @Transactional(readOnly = true)} mirrors the historical setting — every delegated call is
 * read-only, and the sub-services join this facade's read-only transaction (propagation {@code
 * REQUIRED}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerScopeService {

  /**
   * Name of the HTTP request header through which the frontend relays the caller's active OrgUnit
   * selection. Re-exported from {@link RequestScopeResolver#ACTIVE_ORG_UNIT_HEADER} so the
   * historical {@code OwnerScopeService.ACTIVE_ORG_UNIT_HEADER} public constant keeps resolving for
   * existing callers.
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
  @org.jetbrains.annotations.Nullable
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
  public java.util.Set<UUID> currentMemberOrgUnitIds() {
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
  public java.util.Set<UUID> currentDirectMembershipOrgUnitIds() {
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
   * the org-unit blueprint availability overview (#364).
   *
   * @return {@code true} iff the caller is an admin, an officer, or holds at least one oversight
   *     seat.
   */
  public boolean canAccessBlueprintOverview() {
    return requestScopeResolver.canAccessBlueprintOverview();
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
   * Delegates to {@link RequestScopeResolver#currentOwnLevelOversightScope()}: the non-cascaded
   * own-level oversight scope backing the F2 bank booking-request gate.
   *
   * @return a never-null, non-cascaded scope vector of the caller's own-level oversight seats.
   */
  @NotNull
  public ScopePredicate currentOwnLevelOversightScope() {
    return requestScopeResolver.currentOwnLevelOversightScope();
  }

  /**
   * Delegates to {@link RequestScopeResolver#currentUserHasAreaOrOlOversight()}: whether the caller
   * holds Bereich- or OL-level oversight (reveals the cartel-wide special accounts, REQ-BANK-028).
   *
   * @return {@code true} iff the caller is an admin or holds a Bereich-/OL-level oversight seat.
   */
  public boolean currentUserHasAreaOrOlOversight() {
    return requestScopeResolver.currentUserHasAreaOrOlOversight();
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
   * Delegates to {@link RequestScopeResolver#currentUserIsMemberOfAreaCascade(UUID)}: whether the
   * caller is a member anywhere in the whole area cascade of the given Bereich — the
   * Bereichsleitung itself or any of its child Staffeln / Spezialkommandos. Used by the org-unit
   * bank seam to evaluate the {@code AREA_MEMBERS} view grant / approval-limit tier ("Mitglieder
   * des Bereichs", REQ-BANK-048) on a Bereichskonto.
   *
   * @param bereichId the owning Bereich org unit; never {@code null}
   * @return {@code true} iff the caller has any membership on the Bereich or one of its children
   */
  public boolean currentUserIsMemberOfAreaCascade(@NotNull UUID bereichId) {
    return requestScopeResolver.currentUserIsMemberOfAreaCascade(bereichId);
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
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the picker
   *     output is invalid for the target user (see the delegate).
   */
  public Squadron resolveSquadronForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
    return orgUnitStampingService.resolveSquadronForPickerOutput(targetUser, owningOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveOrgUnitForPickerOutput(User, UUID)}: the
   * SK-aware create-time owner-stamp resolution.
   *
   * @param targetUser the user whose memberships gate the picker output validation; never {@code
   *     null}.
   * @param owningOrgUnitId the picker-supplied org unit id; {@code null} triggers the auto-stamp
   *     path when the user has exactly one membership.
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException on an invalid
   *     picker output (see the delegate).
   */
  public OrgUnit resolveOrgUnitForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
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
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException for every
   *     non-ownerless rejection branch (see the delegate).
   */
  @Nullable
  public OrgUnit resolveOrgUnitForPickerOutputNullable(
      @NotNull User targetUser, UUID owningOrgUnitId) {
    return orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
        targetUser, owningOrgUnitId);
  }

  /**
   * Delegates to {@link OrgUnitStampingService#resolveReassignTargetOrgUnit(UUID)}: validates and
   * resolves an explicit owning-org-unit reassignment (REQ-ORG-018).
   *
   * @param targetOrgUnitId the picker-supplied target org-unit id, or {@code null} for ownerless.
   * @return the resolved managed {@link OrgUnit}, or {@code null} for an ownerless target.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when a non-null
   *     target id does not resolve to a known org unit.
   */
  @org.jetbrains.annotations.Nullable
  public OrgUnit resolveReassignTargetOrgUnit(
      @org.jetbrains.annotations.Nullable UUID targetOrgUnitId) {
    return orgUnitStampingService.resolveReassignTargetOrgUnit(targetOrgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeSquadron(UUID)}: whether the caller may read data
   * owned by {@code squadronId}.
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return accessGateService.canSeeSquadron(squadronId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeOrgUnit(UUID)}: the plan-aligned alias of {@link
   * #canSeeSquadron(UUID)}.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  public boolean canSeeOrgUnit(@NotNull UUID orgUnitId) {
    return accessGateService.canSeeOrgUnit(orgUnitId);
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
   * Delegates to {@link AccessGateService#canEditOrgUnit(UUID)}: the plan-aligned alias of {@link
   * #canEditSquadron(UUID)}.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to write; never {@code null}.
   * @return {@code true} iff the caller may write to the given org unit's data.
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return accessGateService.canEditOrgUnit(orgUnitId);
  }

  /**
   * Delegates to {@link AccessGateService#hasRoleInOrgUnit(UUID, String)}: the
   * SPEZIALKOMMANDO_PLAN.md §6.1 contextual-authority check for {@code @PreAuthorize} SpEL.
   *
   * @param orgUnitId the OrgUnit the caller wants to act on; never {@code null}.
   * @param roleName the role to check for; never {@code null}.
   * @return {@code true} iff the caller is an admin or holds the contextual authority.
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    return accessGateService.hasRoleInOrgUnit(orgUnitId, roleName);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeMission(UUID)}: whether the caller may read mission
   * {@code missionId} (cross-staffel public escape + M-2/M-3 hardenings).
   *
   * @param missionId mission to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the mission.
   */
  public boolean canSeeMission(@NotNull UUID missionId) {
    return accessGateService.canSeeMission(missionId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditMission(UUID)}: whether the caller may edit
   * mission {@code missionId} (strict owning-squadron check, no public escape).
   *
   * @param missionId mission to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the mission.
   */
  public boolean canEditMission(@NotNull UUID missionId) {
    return accessGateService.canEditMission(missionId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeJobOrder(UUID)}: whether the caller may read job
   * order {@code jobOrderId} (SK-public / squadron-private + profit gate).
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeJobOrder(@NotNull UUID jobOrderId) {
    return accessGateService.canSeeJobOrder(jobOrderId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeJobOrder(JobOrder)}: the managed-entity overload of
   * {@link #canSeeJobOrder(UUID)}.
   *
   * @param order the job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeJobOrder(@NotNull JobOrder order) {
    return accessGateService.canSeeJobOrder(order);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeJobOrderBlueprintOwners(UUID)}: the stricter
   * blueprint-coverage view gate (responsible-org-unit membership only, no SK-public escape).
   *
   * @param jobOrderId the job order whose blueprint-coverage view the caller wants to read; never
   *     {@code null}.
   * @return {@code true} iff the caller is a member of the order's responsible org unit (or an
   *     admin with matching scope).
   */
  public boolean canSeeJobOrderBlueprintOwners(@NotNull UUID jobOrderId) {
    return accessGateService.canSeeJobOrderBlueprintOwners(jobOrderId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditJobOrder(UUID)}: whether the caller may edit job
   * order {@code jobOrderId} (mirrors the read path for writes).
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order.
   */
  public boolean canEditJobOrder(@NotNull UUID jobOrderId) {
    return accessGateService.canEditJobOrder(jobOrderId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeJobOrderAsRequester(UUID)}: whether the caller may
   * read job order {@code jobOrderId} as its requester (a direct member of its requesting org
   * unit), the profit-gate-independent requester escape (REQ-ORDERS-023).
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order as its requester.
   */
  public boolean canSeeJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return accessGateService.canSeeJobOrderAsRequester(jobOrderId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditJobOrderAsRequester(UUID)}: whether the caller may
   * edit job order {@code jobOrderId} within the requester limits — a direct member of its
   * requesting org unit and the order still fully undelivered (REQ-ORDERS-023).
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order as its (still-undelivered) requester.
   */
  public boolean canEditJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return accessGateService.canEditJobOrderAsRequester(jobOrderId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeInventoryItem(UUID)}: whether the caller may read
   * inventory item {@code itemId} (owner escape → ownerless → strict scope).
   *
   * @param itemId inventory item to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the item.
   */
  public boolean canSeeInventoryItem(@NotNull UUID itemId) {
    return accessGateService.canSeeInventoryItem(itemId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditInventoryItem(UUID)}: whether the caller may edit
   * inventory item {@code itemId} (owner escape → ownerless → strict scope).
   *
   * @param itemId inventory item to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the item.
   */
  public boolean canEditInventoryItem(@NotNull UUID itemId) {
    return accessGateService.canEditInventoryItem(itemId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeRefineryOrder(UUID)}: whether the caller may read
   * refinery order {@code orderId}.
   *
   * @param orderId refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeRefineryOrder(@NotNull UUID orderId) {
    return accessGateService.canSeeRefineryOrder(orderId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeRefineryOrder(RefineryOrder)}: the managed-entity
   * overload of {@link #canSeeRefineryOrder(UUID)}.
   *
   * @param order the refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeRefineryOrder(@NotNull RefineryOrder order) {
    return accessGateService.canSeeRefineryOrder(order);
  }

  /**
   * Delegates to {@link AccessGateService#canEditRefineryOrder(UUID)}: whether the caller may edit
   * refinery order {@code orderId}.
   *
   * @param orderId refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order.
   */
  public boolean canEditRefineryOrder(@NotNull UUID orderId) {
    return accessGateService.canEditRefineryOrder(orderId);
  }

  /**
   * Delegates to {@link AccessGateService#canViewUserRefineryOrders(UUID)}: the coarse user-level
   * read pre-check for the per-user refinery list endpoint.
   *
   * @param targetUserId the user whose refinery orders the caller wants to read; never {@code
   *     null}.
   * @return {@code true} iff the caller may read the target user's in-scope refinery orders.
   */
  public boolean canViewUserRefineryOrders(@NotNull UUID targetUserId) {
    return accessGateService.canViewUserRefineryOrders(targetUserId);
  }

  /**
   * Delegates to {@link AccessGateService#canManageUserRefineryOrders(UUID)}: the coarse user-level
   * write pre-check for the create-on-behalf refinery endpoint.
   *
   * @param targetUserId the user the caller wants to create a refinery order for; never {@code
   *     null}.
   * @return {@code true} iff the caller may create a refinery order on that user's behalf.
   */
  public boolean canManageUserRefineryOrders(@NotNull UUID targetUserId) {
    return accessGateService.canManageUserRefineryOrders(targetUserId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeOperation(UUID)}: whether the caller may read
   * operation {@code operationId} (scope / ownerless-leadership / participant escape).
   *
   * @param operationId operation to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the operation.
   */
  public boolean canSeeOperation(@NotNull UUID operationId) {
    return accessGateService.canSeeOperation(operationId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditOperation(UUID)}: whether the caller may edit
   * operation {@code operationId} (strict owning-squadron / ownerless-leadership no-op).
   *
   * @param operationId operation to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the operation.
   */
  public boolean canEditOperation(@NotNull UUID operationId) {
    return accessGateService.canEditOperation(operationId);
  }

  /**
   * Delegates to {@link AccessGateService#canSeeShip(UUID)}: whether the caller may read ship
   * {@code shipId} (owner escape → ownerless → strict scope).
   *
   * @param shipId ship to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the ship.
   */
  public boolean canSeeShip(@NotNull UUID shipId) {
    return accessGateService.canSeeShip(shipId);
  }

  /**
   * Delegates to {@link AccessGateService#canEditShip(UUID)}: whether the caller may edit ship
   * {@code shipId} (owner escape → ownerless → strict scope).
   *
   * @param shipId ship to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the ship.
   */
  public boolean canEditShip(@NotNull UUID shipId) {
    return accessGateService.canEditShip(shipId);
  }

  /**
   * Delegates to {@link RequestScopeResolver#isPromotionFeatureEnabledForCurrentScope()}: whether
   * the per-squadron promotion feature flag is on for the caller's scope.
   *
   * @return {@code true} when the promotion menu may be exposed for the caller.
   */
  public boolean isPromotionFeatureEnabledForCurrentScope() {
    return requestScopeResolver.isPromotionFeatureEnabledForCurrentScope();
  }

  /**
   * Delegates to {@link RequestScopeResolver#hasPromotionReadAccess()}: whether the caller may read
   * any promotion data.
   *
   * @return {@code true} for admins and non-admins with an effective squadron; {@code false}
   *     otherwise.
   */
  public boolean hasPromotionReadAccess() {
    return requestScopeResolver.hasPromotionReadAccess();
  }

  /**
   * Delegates to {@link RequestScopeResolver#assertPromotionFeatureEnabled()}: throws when the
   * per-squadron promotion feature flag is off for the caller's scope.
   *
   * @throws org.springframework.security.access.AccessDeniedException if a non-admin caller's home
   *     squadron has the flag disabled.
   */
  public void assertPromotionFeatureEnabled() {
    requestScopeResolver.assertPromotionFeatureEnabled();
  }
}
