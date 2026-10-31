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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The purchasing module's access policy (ADR-0236): who trades as a delegate, who governs
 * purchasing, who manages a raising unit's side of a deal. Deals are not tenant scoped; the policy
 * answers with roles and the deal's own delegate.
 *
 * <p>Invoked from SpEL as {@code @purchasingAccessPolicy}.
 */
@Service(PurchasingAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchasingAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "purchasingAccessPolicy";

  /** The contextual role that stands for the lead or a Logistician of a unit. */
  static final String UNIT_MANAGER_ROLE = "LOGISTICIAN";

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final PurchasingSettingsService settingsService;
  private final PurchasingDelegateRepository delegateRepository;

  /**
   * Whether the caller is an admin.
   *
   * @return {@code true} for an admin
   */
  public boolean isAdmin() {
    return authHelperService.isAdmin();
  }

  /**
   * Returns the caller's user id.
   *
   * @return the id, or empty when unauthenticated
   */
  @NotNull
  public Optional<UUID> currentUserId() {
    return authHelperService.currentUserId();
  }

  /**
   * Whether the caller is a Bereichsleiter or Bereichskoordinator of a governing Bereich, belongs
   * to the Organisationsleitung or is an admin: the circle that governs purchasing. A
   * Bereichsoperator does not govern.
   *
   * @return {@code true} for the governing leadership
   */
  public boolean isGoverningLeadership() {
    if (authHelperService.isAdmin() || ownerScopeService.currentUserIsOlMember()) {
      return true;
    }
    for (UUID area : settingsService.governingAreaIds()) {
      if (ownerScopeService.currentUserHoldsRoleOnOrgUnit(area, MembershipRole.BEREICHSLEITER)
          || ownerScopeService.currentUserHoldsRoleOnOrgUnit(
              area, MembershipRole.BEREICHSKOORDINATOR)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the caller holds an active appointment as Handelsbeauftragter.
   *
   * @return {@code true} for an active delegate
   */
  public boolean isActiveDelegate() {
    return authHelperService.currentUserId().map(this::isActiveDelegate).orElse(false);
  }

  /**
   * Whether a member holds an active appointment: not revoked and not past its end date
   * (REQ-PUR-003).
   *
   * @param userId the member
   * @return {@code true} for an active delegate
   */
  public boolean isActiveDelegate(@NotNull UUID userId) {
    return delegateRepository
        .findFirstByUserIdAndRevokedAtIsNull(userId)
        .map(delegate -> delegate.isActiveAt(Instant.now()))
        .orElse(false);
  }

  /**
   * Whether the caller leads or is a Logistician of the unit, or is an admin.
   *
   * @param orgUnitId the unit
   * @return {@code true} when the caller manages the unit's side of a deal
   */
  public boolean canManageUnit(@NotNull UUID orgUnitId) {
    return ownerScopeService.hasRoleInOrgUnit(orgUnitId, UNIT_MANAGER_ROLE);
  }

  /**
   * Returns the units the caller manages explicitly; an admin manages every unit, which this set
   * does not list.
   *
   * @return the ids of the units the caller leads or is a Logistician of
   */
  @NotNull
  public Set<UUID> managedUnitIds() {
    Set<UUID> ids = new HashSet<>();
    authHelperService
        .currentAuthentication()
        .ifPresent(authentication -> {
          for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority instanceof OrgUnitContextualAuthority contextual
                && UNIT_MANAGER_ROLE.equals(contextual.roleName())) {
              ids.add(contextual.orgUnitId());
            }
          }
        });
    return ids;
  }

  /**
   * Whether the caller appoints and revokes delegates and sets their limits.
   *
   * @return {@code true} for the governing leadership
   */
  public boolean canAppointDelegates() {
    return isGoverningLeadership();
  }

  /**
   * Whether the caller maintains the partners.
   *
   * @return {@code true} for a delegate and the governing leadership
   */
  public boolean canMaintainPartners() {
    return isActiveDelegate() || isGoverningLeadership();
  }

  /**
   * Whether the caller reads the partner notes: the delegates and the governing leadership, never
   * the raising unit (REQ-PUR-021).
   *
   * @return {@code true} for a delegate or the governing leadership
   */
  public boolean canReadPartnerNotes() {
    return isActiveDelegate() || isGoverningLeadership();
  }

  /**
   * Whether the caller creates deals: only an active delegate (REQ-PUR-003, REQ-PUR-007).
   *
   * @return {@code true} for an active delegate
   */
  public boolean canCreateDeals() {
    return isActiveDelegate();
  }

  /**
   * Whether the caller reads a deal: its delegate, active or former, the governing leadership and
   * the lead or a Logistician of the raising unit (REQ-PUR-021).
   *
   * @param deal the deal
   * @return {@code true} when the caller may read it
   */
  public boolean canReadDeal(@NotNull PurchasingDeal deal) {
    return isDealDelegate(deal)
        || isGoverningLeadership()
        || isBankStaff()
        || canManageUnit(deal.getRaisingOrgUnitId());
  }

  /**
   * Whether the caller works in the bank: an employee or the management, who read the deals behind
   * the purpose-bound requests they decide (REQ-PUR-021).
   *
   * @return {@code true} for bank staff
   */
  public boolean isBankStaff() {
    return authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE))
        || authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
  }

  /**
   * Whether the caller reads the purchasing report: delegates, the governing leadership, bank staff
   * and the leads and Logisticians of a raising unit (REQ-PUR-023).
   *
   * @return {@code true} when the caller may read and export the report
   */
  public boolean canReadReport() {
    return isActiveDelegate()
        || isGoverningLeadership()
        || isBankStaff()
        || !managedUnitIds().isEmpty();
  }

  /**
   * Whether the caller trades the deal: the deal's own delegate, active or former, who records its
   * tranches, deliveries and settlement.
   *
   * @param deal the deal
   * @return {@code true} for the deal's delegate
   */
  public boolean canTradeDeal(@NotNull PurchasingDeal deal) {
    return isDealDelegate(deal);
  }

  /**
   * Whether the caller cancels the deal: its delegate or the governing leadership.
   *
   * @param deal the deal
   * @return {@code true} when the caller may cancel it
   */
  public boolean canCancelDeal(@NotNull PurchasingDeal deal) {
    return isDealDelegate(deal) || isGoverningLeadership();
  }

  /**
   * Whether the caller is the delegate of the deal, active or former: the one who records
   * deliveries and the settlement (REQ-PUR-003).
   *
   * @param deal the deal
   * @return {@code true} for the deal's delegate
   */
  public boolean isDealDelegate(@NotNull PurchasingDeal deal) {
    return authHelperService
        .currentUserId()
        .map(user -> user.equals(deal.getDelegateUserId()))
        .orElse(false);
  }

  /**
   * Whether the caller accepts the deliveries of a deal: the lead or a Logistician of the raising
   * unit, or an admin.
   *
   * @param deal the deal
   * @return {@code true} when the caller may decide its deliveries
   */
  public boolean canAcceptDeliveries(@NotNull PurchasingDeal deal) {
    return canManageUnit(deal.getRaisingOrgUnitId());
  }

  /**
   * Whether the caller pauses or resumes purchasing: the governing leadership for any scope, the
   * lead of a raising unit for that unit.
   *
   * @param orgUnitId the raising unit for a unit pause, or {@code null} for the whole organisation
   * @return {@code true} when the caller may pause and resume that scope
   */
  public boolean canPause(@Nullable UUID orgUnitId) {
    return isGoverningLeadership() || (orgUnitId != null && leadsUnit(orgUnitId));
  }

  /**
   * Whether the caller is the lead of the unit: the Staffelleiter or the SK lead.
   *
   * @param orgUnitId the unit
   * @return {@code true} for the unit's lead
   */
  public boolean leadsUnit(@NotNull UUID orgUnitId) {
    return ownerScopeService.hasRoleInOrgUnit(orgUnitId, "OFFICER")
        || ownerScopeService.hasRoleInOrgUnit(orgUnitId, "LEAD");
  }
}
