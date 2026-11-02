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

package de.greluc.krt.profit.basetool.frontend.kernel.model;

/**
 * Wire-shape mirror of the backend's {@code MeController.CapabilitiesResponse}: the caller's UI
 * capability flags, read once per request through the layout read.
 *
 * @param canSeeBlueprintOverview whether the caller may open the blueprint availability overview
 * @param canViewJobOrders whether the caller may enter the Job-Order area
 * @param canViewOwnJobOrders whether the caller may view their own org unit's orders
 *     (REQ-ORDERS-023)
 * @param canViewAssignedJobOrders whether the caller holds an assignee entry on at least one order
 *     (REQ-ORDERS-040)
 * @param isLogisticianOrAbove whether the caller holds a Logistician grant in the active context:
 *     in the pinned unit, or in any unit when none is pinned
 * @param isMissionManagerOrAbove whether the caller holds a Mission Manager grant in the active
 *     context: in the pinned unit, or in any unit when none is pinned
 * @param canMarkStolen whether stock may be booked in as, marked or unmarked „gestohlen"
 *     (REQ-INV-053); a server switch, the same for every caller
 * @param hasOfficerReach whether the caller holds officer reach in the active context
 * @param canOpenLeitung whether the Leitung page is offered in the active context
 * @param canSeeHangarOwnerDetails whether hangar owner details are offered in the active context
 * @param canManageJobOrders whether the officer job-order writes are offered in the active context
 * @param canManageMissionFinance whether mission finance and owner change are offered in the active
 *     context
 * @param canActAsOfficerAnywhere whether the caller holds officer reach in at least one unit,
 *     whatever unit is pinned
 * @param canViewSupplyIncoming whether „Eingehende Spenden" is offered: the supply surface is on
 *     and the caller manages an active unit that accepts donations
 * @param canViewSupplyStats whether the donation statistics are offered: the supply surface is on
 *     and the caller belongs to the governing leadership or manages a unit
 * @param canViewPurchasingPartners whether the purchasing partners are offered: the purchasing
 *     surface is on and the caller is a trade delegate or the governing leadership
 * @param canViewPurchasingDelegates whether the trade delegates are offered: the purchasing surface
 *     is on and the caller is the governing leadership
 * @param canViewPurchasingReport whether the purchasing report is offered: the purchasing surface
 *     is on and the caller may read it
 * @param canViewBankStaff whether the caller holds {@code BANK_EMPLOYEE} or above
 * @param canManageBank whether the caller holds {@code BANK_MANAGEMENT}
 * @param isAdmin whether the caller holds {@code ADMIN}
 */
@DtoMirror
public record CapabilitiesResponse(
    boolean canSeeBlueprintOverview,
    boolean canViewJobOrders,
    boolean canViewOwnJobOrders,
    boolean canViewAssignedJobOrders,
    boolean isLogisticianOrAbove,
    boolean isMissionManagerOrAbove,
    boolean canMarkStolen,
    boolean hasOfficerReach,
    boolean canOpenLeitung,
    boolean canSeeHangarOwnerDetails,
    boolean canManageJobOrders,
    boolean canManageMissionFinance,
    boolean canActAsOfficerAnywhere,
    boolean canViewSupplyIncoming,
    boolean canViewSupplyStats,
    boolean canViewPurchasingPartners,
    boolean canViewPurchasingDelegates,
    boolean canViewPurchasingReport,
    boolean canViewBankStaff,
    boolean canManageBank,
    boolean isAdmin) {

  /** Every capability off: the fail-closed answer for an anonymous or unresolved caller. */
  public static final CapabilitiesResponse NONE =
      new CapabilitiesResponse(
          false, false, false, false, false, false, false, false, false, false, false, false, false,
          false, false, false, false, false, false, false, false);

  /**
   * Every role-derived capability on, as an admin passes each gate; the server switch, the tab
   * flags, which depend on switches and configured units, and the bank roles stay the backend's.
   *
   * @param backend the backend's answer for the admin
   * @return the admin's capability flags
   */
  public static CapabilitiesResponse admin(CapabilitiesResponse backend) {
    return new CapabilitiesResponse(
        true,
        true,
        true,
        true,
        true,
        true,
        backend.canMarkStolen(),
        true,
        true,
        true,
        true,
        true,
        true,
        backend.canViewSupplyIncoming(),
        backend.canViewSupplyStats(),
        backend.canViewPurchasingPartners(),
        backend.canViewPurchasingDelegates(),
        backend.canViewPurchasingReport(),
        backend.canViewBankStaff(),
        backend.canManageBank(),
        true);
  }
}
