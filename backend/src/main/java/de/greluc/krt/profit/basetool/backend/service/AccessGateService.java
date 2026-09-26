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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code can*} authorization gates behind every {@code @PreAuthorize} on the org-unit-scoped
 * aggregates, evaluated against the same scope as {@link RequestScopeResolver} so per-row checks
 * match the scoped lists.
 *
 * <p>Invoked from SpEL through the {@link OwnerScopeService} facade. Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessGateService {

  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;
  private final MissionRepository missionRepository;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final OperationRepository operationRepository;
  private final ShipRepository shipRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

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
   * Alias for {@link #canSeeSquadron(UUID)} with an org-unit name.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may see the org unit's data
   */
  public boolean canSeeOrgUnit(@NotNull UUID orgUnitId) {
    return canSeeSquadron(orgUnitId);
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
   * Alias for {@link #canEditSquadron(UUID)} with an org-unit name.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to write; never {@code null}
   * @return {@code true} iff the caller may write the org unit's data
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return canEditSquadron(orgUnitId);
  }

  /**
   * Checks whether the caller holds the contextual authority {@code (roleName, orgUnitId)} ({@link
   * de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority}); admins always pass.
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
   * Checks whether the caller may read mission {@code missionId}: non-internal missions are visible
   * organisation-wide, internal ones only within the owning org unit. Access is denied when any
   * ancestor mission is internal and foreign; unknown ids return {@code false}.
   *
   * @param missionId mission to inspect; never {@code null}
   * @return {@code true} iff the caller may read the mission
   */
  public boolean canSeeMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(
            m -> {
              for (Mission ancestor = m; ancestor != null; ancestor = ancestor.getParent()) {
                if (!canSeeMissionRow(ancestor)) {
                  return false;
                }
              }
              return true;
            })
        .orElse(false);
  }

  /**
   * Per-row visibility check for {@link #canSeeMission(UUID)} and its parent-chain walk.
   *
   * <ul>
   *   <li>Ownerless mission: visible to everyone when non-internal, to members-or-above ({@link
   *       AuthHelperService#isMemberOrAbove()}) when internal.
   *   <li>Org-owned mission: visible when the caller may see the owning org unit or the mission is
   *       non-internal (REQ-ORG-009).
   * </ul>
   */
  private boolean canSeeMissionRow(Mission m) {
    if (m.getOwningOrgUnit() == null) {
      if (!Boolean.TRUE.equals(m.getIsInternal())) {
        return true;
      }
      return authHelper.isMemberOrAbove();
    }
    if (canSeeSquadron(m.getOwningOrgUnit().getId())) {
      return true;
    }
    return !Boolean.TRUE.equals(m.getIsInternal());
  }

  /**
   * Checks whether the caller may edit mission {@code missionId}: strict owning-org-unit check
   * without the public escape. An ownerless mission passes, leaving the decision to {@code
   * MissionSecurityService.canManageMission}; unknown ids return {@code false}.
   *
   * @param missionId mission to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the mission
   */
  public boolean canEditMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(m -> m.getOwningOrgUnit() == null || canEditSquadron(m.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may read job order {@code jobOrderId}.
   *
   * <ul>
   *   <li>Spezialkommando-responsible: visible to every profit-eligible caller ({@link
   *       RequestScopeResolver#canViewJobOrders()}).
   *   <li>Squadron-responsible: visible only to members of that squadron and admins.
   * </ul>
   *
   * <p>A caller who is not profit-eligible sees no order; a {@code null} responsible org unit is
   * visible; unknown ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canSeeJobOrderRow).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeJobOrder(UUID)} for callers that already hold the {@link
   * JobOrder}.
   *
   * @param order the job order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeJobOrder(@NotNull JobOrder order) {
    return canSeeJobOrderRow(order);
  }

  /**
   * Per-row read check for {@link #canSeeJobOrder(UUID)}: applies the profit gate ({@link
   * RequestScopeResolver#canViewJobOrders()}), then treats SK-responsible orders as public and
   * squadron-responsible ones via {@link #canSeeSquadron(UUID)}.
   *
   * @param o the job order whose responsible org unit gates visibility
   * @return {@code true} iff the caller may read the row
   */
  private boolean canSeeJobOrderRow(JobOrder o) {
    if (!requestScopeResolver.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = o.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return canSeeSquadron(responsible.getId());
  }

  /**
   * Checks whether the caller may see the blueprint-coverage view of a job order: only members of
   * its responsible org unit via {@link #canSeeSquadron(UUID)}, stricter than {@link
   * #canSeeJobOrder(UUID)}. A {@code null} responsible org unit or unknown id returns {@code
   * false}.
   *
   * @param jobOrderId the job order whose blueprint coverage the caller wants to read; never {@code
   *     null}
   * @return {@code true} iff the caller may see the responsible org unit
   */
  public boolean canSeeJobOrderBlueprintOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may see the owner and location of inventory linked to a job order
   * (REQ-ORDERS-029, ADR-0107): only members of its responsible org unit, stricter than {@link
   * #canSeeJobOrder(UUID)}. A {@code null} responsible org unit or unknown id returns {@code
   * false}.
   *
   * @param jobOrderId the job order whose linked-inventory owners the caller wants to read; never
   *     {@code null}
   * @return {@code true} iff the caller may see the responsible org unit
   */
  public boolean canSeeJobOrderInventoryOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit job order {@code jobOrderId}: SK-responsible orders are open
   * to the endpoint's role gate, squadron-responsible ones follow {@link #canEditSquadron(UUID)}. A
   * caller who is not profit-eligible edits no order; unknown ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canEditJobOrderRow).orElse(false);
  }

  /**
   * Checks the complete write rule of the job-order endpoints: logistician-or-above and {@link
   * #canEditJobOrder(UUID)}.
   *
   * @param jobOrderId the order to test
   * @return whether the current caller may edit it
   */
  public boolean mayEditJobOrder(@NotNull UUID jobOrderId) {
    return authHelper.isLogisticianOrAbove() && canEditJobOrder(jobOrderId);
  }

  /**
   * Per-row write check for {@link #canEditJobOrder(UUID)}: applies the profit gate, then opens
   * SK-responsible orders and checks squadron-responsible ones via {@link #canEditSquadron(UUID)}.
   *
   * @param o the job order whose responsible org unit gates write access
   * @return {@code true} iff the caller may edit the row
   */
  private boolean canEditJobOrderRow(JobOrder o) {
    if (!requestScopeResolver.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = o.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return canEditSquadron(responsible.getId());
  }

  /**
   * Checks whether the caller may read a job order as a direct member of its requesting org unit,
   * regardless of the profit gate (REQ-ORDERS-023). Unknown ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}
   * @return {@code true} iff the caller is a direct member of the requesting org unit
   */
  public boolean canSeeJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::isOrderRequesterRow).orElse(false);
  }

  /**
   * Checks whether the caller may edit a job order as its requester: a direct member of the
   * requesting org unit while the order has no material or item handover yet (REQ-ORDERS-023).
   * Unknown ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the still-undelivered order as its requester
   */
  public boolean canEditJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(o -> isOrderRequesterRow(o) && !orderHasAnyDelivery(o.getId()))
        .orElse(false);
  }

  /**
   * Checks whether the order has a requesting org unit of which the caller is a direct member
   * ({@link RequestScopeResolver#currentUserIsMemberOfOrgUnit(UUID)}), independent of the profit
   * gate.
   *
   * @param o the job order whose requesting org unit gates the escape
   * @return {@code true} iff the caller directly belongs to the requesting org unit
   */
  private boolean isOrderRequesterRow(@NotNull JobOrder o) {
    OrgUnit requesting = o.getRequestingOrgUnit();
    return requesting != null
        && requestScopeResolver.currentUserIsMemberOfOrgUnit(requesting.getId());
  }

  /**
   * Checks whether the order has at least one material ({@link
   * de.greluc.krt.profit.basetool.backend.model.JobOrderHandover}) or item ({@link
   * de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover}) handover.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff at least one handover exists
   */
  private boolean orderHasAnyDelivery(@NotNull UUID jobOrderId) {
    return jobOrderHandoverRepository.existsByJobOrderId(jobOrderId)
        || jobOrderItemHandoverRepository.existsByJobOrderId(jobOrderId);
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
   * Shared owner-escape check for the personal aggregates (REQ-ORG-011), in order.
   *
   * <ul>
   *   <li>the per-user owner may always access the row ({@link #isCurrentUserOwner(User)});
   *   <li>an ownerless row defers to {@link #canAccessOwnerlessPersonalRow(User)};
   *   <li>otherwise {@link #canSeeSquadron(UUID)} or, with {@code edit}, {@link
   *       #canEditSquadron(UUID)}.
   * </ul>
   *
   * @param row the resolved row; empty denies access
   * @param owner extracts the row's per-user owner
   * @param orgUnit extracts the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @param <T> the row's entity type
   * @return {@code true} iff the caller may access the row
   */
  private <T> boolean permitsRow(
      @NotNull Optional<T> row,
      Function<T, User> owner,
      Function<T, OrgUnit> orgUnit,
      boolean edit) {
    return row.map(
            r -> {
              User rowOwner = owner.apply(r);
              OrgUnit rowOrgUnit = orgUnit.apply(r);
              return isCurrentUserOwner(rowOwner)
                  || (rowOrgUnit == null
                      ? canAccessOwnerlessPersonalRow(rowOwner)
                      : (edit
                          ? canEditSquadron(rowOrgUnit.getId())
                          : canSeeSquadron(rowOrgUnit.getId())));
            })
        .orElse(false);
  }

  /**
   * Checks whether the caller may read inventory item {@code itemId} directly, applying the owner
   * escape (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)}. Unknown ids
   * return {@code false}.
   *
   * @param itemId inventory item to inspect; never {@code null}
   * @return {@code true} iff the caller may read the item
   */
  public boolean canSeeInventoryItem(@NotNull UUID itemId) {
    return permitsRow(
        inventoryItemRepository.findById(itemId),
        InventoryItem::getUser,
        InventoryItem::getOwningOrgUnit,
        false);
  }

  /**
   * Checks whether the caller may edit inventory item {@code itemId} directly, applying the owner
   * escape (REQ-ORG-011), then the ownerless rule, then {@link #canEditSquadron(UUID)}. Unknown ids
   * return {@code false}.
   *
   * @param itemId inventory item to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the item
   */
  public boolean canEditInventoryItem(@NotNull UUID itemId) {
    return permitsRow(
        inventoryItemRepository.findById(itemId),
        InventoryItem::getUser,
        InventoryItem::getOwningOrgUnit,
        true);
  }

  /**
   * Checks whether the caller may read refinery order {@code orderId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeRefineryOrder(@NotNull UUID orderId) {
    return refineryOrderRepository.findById(orderId).map(this::canSeeRefineryOrder).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeRefineryOrder(UUID)} for callers that already hold the {@link
   * RefineryOrder}.
   *
   * @param order the refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeRefineryOrder(@NotNull RefineryOrder order) {
    return permitsRow(
        Optional.of(order), RefineryOrder::getOwner, RefineryOrder::getOwningOrgUnit, false);
  }

  /**
   * Checks whether the caller may edit refinery order {@code orderId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canEditSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param orderId refinery order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditRefineryOrder(@NotNull UUID orderId) {
    return permitsRow(
        refineryOrderRepository.findById(orderId),
        RefineryOrder::getOwner,
        RefineryOrder::getOwningOrgUnit,
        true);
  }

  /**
   * Coarse pre-check for reading a user's refinery orders: admin, the user themselves, or a caller
   * whose {@link #canSeeSquadron(UUID)} covers any of the user's memberships. Per-row scoping is
   * left to the scoped list query.
   *
   * @param targetUserId the user whose refinery orders the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may read the user's in-scope refinery orders
   */
  public boolean canViewUserRefineryOrders(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canSeeSquadron);
  }

  /**
   * Coarse pre-check for creating a refinery order on a user's behalf, like {@link
   * #canViewUserRefineryOrders(UUID)} but with {@link #canEditSquadron(UUID)}. The per-row bound is
   * {@link OrgUnitStampingService#resolveStampedOrgUnit(java.util.Set, UUID)}.
   *
   * @param targetUserId the user the caller wants to create a refinery order for; never {@code
   *     null}
   * @return {@code true} iff the caller may create a refinery order on that user's behalf
   */
  public boolean canManageUserRefineryOrders(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canEditSquadron);
  }

  /**
   * Coarse pre-check for creating inventory in another member's name: admin, self, or a shared
   * editable org unit, like {@link #canManageUserRefineryOrders(UUID)} (REQ-SEC-005). The per-row
   * bound is the stamp validation.
   *
   * @param targetUserId the member whose inventory would receive the row; never {@code null}
   * @return {@code true} iff the caller may create inventory rows in that member's name
   */
  public boolean canManageUserInventory(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canEditSquadron);
  }

  /**
   * Shared on-behalf check: admin, then self, then whether {@code unitScope} accepts any of the
   * target user's memberships. It does not bound individual rows; callers must scope per row.
   *
   * @param targetUserId the user being acted upon; never {@code null}
   * @param unitScope the per-unit scope check to apply; never {@code null}
   * @return {@code true} iff the caller shares at least one in-scope org unit with the target
   */
  private boolean canActOnTargetUserScoped(
      @NotNull UUID targetUserId, @NotNull Predicate<UUID> unitScope) {
    if (authHelper.isAdmin()) {
      return true;
    }
    if (authHelper.currentUserId().map(targetUserId::equals).orElse(false)) {
      return true;
    }
    return orgUnitMembershipRepository.findAllByIdUserId(targetUserId).stream()
        .map(m -> m.getId().getOrgUnitId())
        .anyMatch(unitScope);
  }

  /**
   * Checks whether the caller may read operation {@code operationId}: when the owning-org-unit
   * check passes, when it is ownerless and the caller is member-or-above (REQ-ORG-009), or when the
   * caller participated in one of its linked missions. Unknown ids return {@code false}.
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller may read the operation
   */
  public boolean canSeeOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o -> {
              boolean scopeVisible =
                  o.getOwningOrgUnit() == null
                      ? authHelper.isMemberOrAbove()
                      : canSeeSquadron(o.getOwningOrgUnit().getId());
              return scopeVisible || participatedInOperation(operationId);
            })
        .orElse(false);
  }

  /**
   * {@link #canSeeOperation(UUID)} without the participant escape, for the finance endpoints:
   * mission participation is self-issuable and so does not unlock a foreign ledger.
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller reaches the operation without the participant escape
   */
  public boolean canSeeOperationLedger(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o ->
                o.getOwningOrgUnit() == null
                    ? authHelper.isMemberOrAbove()
                    : canSeeSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller participated in one of the operation's linked missions.
   *
   * @param operationId the operation to test; never {@code null}
   * @return {@code true} iff the caller is a participant of one of its missions
   */
  private boolean participatedInOperation(@NotNull UUID operationId) {
    return authHelper
        .currentUserId()
        .map(uid -> operationRepository.existsParticipantUserInOperation(operationId, uid))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit operation {@code operationId}: strict owning-org-unit check;
   * an ownerless operation passes and is restricted by the controller's role gate (REQ-ORG-009).
   * Unknown ids return {@code false}.
   *
   * @param operationId operation to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the operation
   */
  public boolean canEditOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(o -> o.getOwningOrgUnit() == null || canEditSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may read ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}
   * @return {@code true} iff the caller may read the ship
   */
  public boolean canSeeShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, false);
  }

  /**
   * Checks whether the caller may edit ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canEditSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the ship
   */
  public boolean canEditShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, true);
  }
}
