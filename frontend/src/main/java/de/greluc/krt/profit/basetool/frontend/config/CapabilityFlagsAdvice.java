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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
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
      return new CapabilitiesResponse(
          true, true, true, layoutContextLoader.load(request).capabilities().canMarkStolen());
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
   * @param canMarkStolen whether stock may be booked in as, marked or unmarked „gestohlen"
   *     (REQ-INV-053); a server switch, the same for every caller
   */
  public record CapabilitiesResponse(
      boolean canSeeBlueprintOverview,
      boolean canViewJobOrders,
      boolean canViewOwnJobOrders,
      boolean canMarkStolen) {

    /** Every capability off: the fail-closed answer for an anonymous or unresolved caller. */
    public static final CapabilitiesResponse NONE =
        new CapabilitiesResponse(false, false, false, false);
  }
}
