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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Resolves the caller's UI capability flags once per request and exposes the model attributes the
 * layout menu entries are gated on.
 *
 * <p>Fails closed: a backend error turns every flag off. Scoped to {@link UsesLayoutModel}; the
 * flags come from the request's shared {@link LayoutContextLoader} read.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class CapabilityFlagsAdvice {

  /** Reads the request's layout context once and shares it with the other layout advices. */
  private final LayoutContextLoader layoutContextLoader;

  /** Answers whether the caller is authenticated and whether they hold {@code ADMIN}. */
  private final FrontendAuthHelperService authHelper;

  /**
   * Resolves the caller's UI capability flags once per request: all role gates on for admins, all
   * off for anonymous callers, otherwise from {@link LayoutContextLoader}. The server switch {@code
   * canMarkStolen} and the supply and purchasing tab flags always come from the backend, admins
   * included. Fails closed to all off.
   *
   * @param request the current request, through which the layout context is memoised
   * @return the caller's capability flags; never {@code null}
   */
  @ModelAttribute("meCapabilities")
  public CapabilitiesResponse meCapabilities(HttpServletRequest request) {
    if (!authHelper.isAuthenticated()) {
      return CapabilitiesResponse.NONE;
    }
    if (authHelper.isAdmin()) {
      return CapabilitiesResponse.admin(layoutContextLoader.load(request).capabilities());
    }
    return layoutContextLoader.load(request).capabilities();
  }

  /**
   * Whether the org-unit blueprint availability overview menu entry is shown, using the backend's
   * gate.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the caller may open the blueprint availability overview
   */
  @ModelAttribute("canSeeBlueprintOverview")
  public boolean canSeeBlueprintOverview(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canSeeBlueprintOverview();
  }

  /**
   * Whether the caller may enter the Job-Order area; steers the sidebar link and the page redirect,
   * while the backend gate stays authoritative.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the caller may view job orders
   */
  @ModelAttribute("canViewJobOrders")
  public boolean canViewJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewJobOrders();
  }

  /**
   * Whether the caller may view the orders their own org unit requested ("Meine Aufträge",
   * REQ-ORDERS-023); steers the UI only.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the caller may view their own org unit's orders
   */
  @ModelAttribute("canViewOwnJobOrders")
  public boolean canViewOwnJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewOwnJobOrders();
  }

  /**
   * Whether the caller holds an assignee entry on at least one order, which opens the order list to
   * them without profit access (REQ-ORDERS-040); steers the UI only.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the caller is an assignee of at least one order
   */
  @ModelAttribute("canViewAssignedJobOrders")
  public boolean canViewAssignedJobOrders(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewAssignedJobOrders();
  }

  /**
   * Whether the shared permitted-order workspace is offered.
   *
   * @param caps the authoritative backend capability projection
   * @return the current backend flag, or false when unresolved
   */
  @ModelAttribute("canViewOrderWorkspace")
  public boolean canViewOrderWorkspace(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewOrderWorkspace();
  }

  /**
   * Whether the current units' responsible or supporting queue is offered.
   *
   * @param caps the authoritative backend capability projection
   * @return the current backend flag, or false when unresolved
   */
  @ModelAttribute("canViewOrderUnitWork")
  public boolean canViewOrderUnitWork(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewOrderUnitWork();
  }

  /**
   * Whether at least one authorized unit pair can create an order.
   *
   * @param caps the authoritative backend capability projection
   * @return the current backend flag, or false when unresolved
   */
  @ModelAttribute("canCreateJobOrders")
  public boolean canCreateJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canCreateJobOrders();
  }

  /**
   * Whether stock may be booked in as, marked or unmarked „gestohlen" (REQ-INV-053); a server
   * switch that is the same for every caller. Reading, the chip and the filters never depend on it.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the marking actions are offered
   */
  @ModelAttribute("canMarkStolen")
  public boolean canMarkStolen(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canMarkStolen();
  }

  /**
   * Whether the caller holds a Logistician grant in the active context: the pinned unit, or any
   * unit when none is pinned. Presentation only; the backend re-checks the owning unit.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend reports a Logistician grant in the active context
   */
  @ModelAttribute("isLogisticianOrAbove")
  public boolean isLogisticianOrAbove(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.isLogisticianOrAbove();
  }

  /**
   * Whether the caller holds a Mission Manager grant in the active context: the pinned unit, or any
   * unit when none is pinned. Presentation only; the backend re-checks the owning unit.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend reports a Mission Manager grant in the active context
   */
  @ModelAttribute("isMissionManagerOrAbove")
  public boolean isMissionManagerOrAbove(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.isMissionManagerOrAbove();
  }

  /**
   * Whether the caller holds officer reach in the active context: the pinned unit, or any unit when
   * none is pinned (REQ-SEC-047). Presentation only; the backend re-checks the owning unit.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend reports officer reach in the active context
   */
  @ModelAttribute("hasOfficerReach")
  public boolean hasOfficerReach(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.hasOfficerReach();
  }

  /**
   * Whether the Leitung page is offered in the active context.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend offers the Leitung page
   */
  @ModelAttribute("canOpenLeitung")
  public boolean canOpenLeitung(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canOpenLeitung();
  }

  /**
   * Whether hangar owner details are offered in the active context.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend offers hangar owner details
   */
  @ModelAttribute("canSeeHangarOwnerDetails")
  public boolean canSeeHangarOwnerDetails(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canSeeHangarOwnerDetails();
  }

  /**
   * Whether the officer job-order writes are offered in the active context.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend offers the officer job-order writes
   */
  @ModelAttribute("canManageJobOrders")
  public boolean canManageJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canManageJobOrders();
  }

  /**
   * Whether mission finance and owner change are offered in the active context.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend offers mission finance and owner change
   */
  @ModelAttribute("canManageMissionFinance")
  public boolean canManageMissionFinance(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canManageMissionFinance();
  }
}
