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

import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Resolves the caller's UI capability flags once per request and exposes the model attributes the
 * layout menu entries are gated on, including {@code promotionFeatureEnabled}.
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
   * canMarkStolen} always comes from the backend, admins included. Fails closed to all off.
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
      return CapabilitiesResponse.admin(
          layoutContextLoader.load(request).capabilities().canMarkStolen());
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
   * Whether promotion management is offered in the active context.
   *
   * @param caps the per-request capability flags from {@link #meCapabilities(HttpServletRequest)}
   * @return {@code true} iff the backend offers promotion management
   */
  @ModelAttribute("canManagePromotions")
  public boolean canManagePromotions(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canManagePromotions();
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

  /**
   * Computes whether the promotion subsystem is exposed to the caller, based on the active
   * squadron.
   *
   * <p>An admin without a pin sees the menu; otherwise the active squadron's promotion flag
   * decides, and a caller without a squadron sees nothing. {@code PromotionPageController} answers
   * 403 when this is {@code false}.
   *
   * @param activeSquadron the resolved active squadron, or {@code null}
   * @return {@code true} when the promotion menu should be exposed
   */
  @ModelAttribute("promotionFeatureEnabled")
  public boolean promotionFeatureEnabled(
      @ModelAttribute("activeSquadron") SquadronDto activeSquadron) {
    if (activeSquadron == null) {
      return authHelper.isAdmin();
    }
    if (activeSquadron.isPromotionEnabled() == null) {
      return true;
    }
    return activeSquadron.isPromotionEnabled();
  }

  /**
   * Wire-shape mirror of the backend's {@code MeController.CapabilitiesResponse}.
   *
   * @param canSeeBlueprintOverview whether the caller may open the blueprint availability overview
   * @param canViewJobOrders whether the caller may enter the Job-Order area
   * @param canViewOwnJobOrders whether the caller may view their own org unit's orders
   *     (REQ-ORDERS-023)
   * @param canViewAssignedJobOrders whether the caller holds an assignee entry on at least one
   *     order (REQ-ORDERS-040)
   * @param isMissionManagerOrAbove whether the caller holds a Mission Manager grant in the active
   *     context: in the pinned unit, or in any unit when none is pinned
   * @param canMarkStolen whether stock may be booked in as, marked or unmarked „gestohlen"
   *     (REQ-INV-053); a server switch, the same for every caller
   * @param hasOfficerReach whether the caller holds officer reach in the active context
   * @param canOpenLeitung whether the Leitung page is offered in the active context
   * @param canManagePromotions whether promotion management is offered in the active context
   * @param canSeeHangarOwnerDetails whether hangar owner details are offered in the active context
   * @param canManageJobOrders whether the officer job-order writes are offered in the active
   *     context
   * @param canManageMissionFinance whether mission finance and owner change are offered in the
   *     active context
   */
  public record CapabilitiesResponse(
      boolean canSeeBlueprintOverview,
      boolean canViewJobOrders,
      boolean canViewOwnJobOrders,
      boolean canViewAssignedJobOrders,
      boolean isMissionManagerOrAbove,
      boolean canMarkStolen,
      boolean hasOfficerReach,
      boolean canOpenLeitung,
      boolean canManagePromotions,
      boolean canSeeHangarOwnerDetails,
      boolean canManageJobOrders,
      boolean canManageMissionFinance) {

    /** Every capability off: the fail-closed answer for an anonymous or unresolved caller. */
    public static final CapabilitiesResponse NONE =
        new CapabilitiesResponse(
            false, false, false, false, false, false, false, false, false, false, false, false);

    /**
     * Every role-derived capability on, as an admin passes each gate; the server switch stays the
     * backend's.
     *
     * @param canMarkStolen the backend's „gestohlen" switch
     * @return the admin's capability flags
     */
    public static CapabilitiesResponse admin(boolean canMarkStolen) {
      return new CapabilitiesResponse(
          true, true, true, true, true, canMarkStolen, true, true, true, true, true, true);
    }
  }
}
