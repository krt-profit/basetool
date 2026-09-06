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

import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Cross-cutting advice that resolves the per-principal UI capability flags once per request and
 * exposes them as the model attributes the layout fragments gate their menu entries on.
 *
 * <p>The single {@code meCapabilities} round-trip backs the three derived boolean attributes
 * ({@code canSeeBlueprintOverview}, {@code canViewJobOrders}, {@code canViewOwnJobOrders}) so they
 * never cost a call each. It also owns {@code promotionFeatureEnabled}, whose visibility hangs off
 * the active squadron — that value is produced by {@code OrgUnitContextAdvice#activeSquadron} and
 * cross-injected here (Spring's {@code ModelFactory} orders {@code @ModelAttribute} methods by
 * dependency across all advice beans, so the reference resolves regardless of the source bean).
 *
 * <p>The capability resolution fails <em>closed</em>: any backend hiccup yields all-off rather than
 * exposing a gated menu or page the caller may not be entitled to. The backend enforces the same
 * gates, so a hidden control and the API stay in lockstep.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class CapabilityFlagsAdvice {

  private final BackendApiClient backendApiClient;
  private final FrontendAuthHelperService authHelper;

  /**
   * Loads the per-principal UI capability flags once per request from {@code GET
   * /api/v1/me/capabilities} so the derived {@code canSeeBlueprintOverview} and {@code
   * canViewJobOrders} attributes share a single backend round-trip instead of one each. Admins
   * receive every flag without a call (system-wide access); anonymous callers receive every flag
   * off without a call.
   *
   * <p>Fails <em>closed</em>: any backend hiccup yields all-off rather than exposing a gated menu
   * or page the caller may not be entitled to. The backend enforces the same gates (a forbidden API
   * / empty list), so a hidden control and the API stay in lockstep.
   *
   * @return the caller's capability flags; never {@code null}.
   */
  @ModelAttribute("meCapabilities")
  public CapabilitiesResponse meCapabilities() {
    if (!authHelper.isAuthenticated()) {
      return new CapabilitiesResponse(false, false, false);
    }
    if (authHelper.isAdmin()) {
      return new CapabilitiesResponse(true, true, true);
    }
    try {
      CapabilitiesResponse resp =
          backendApiClient.get("/api/v1/me/capabilities", CapabilitiesResponse.class);
      return resp != null ? resp : new CapabilitiesResponse(false, false, false);
    } catch (Exception ex) {
      log.debug("Failed to resolve me-capabilities", ex);
      return new CapabilitiesResponse(false, false, false);
    }
  }

  /**
   * Whether the org-unit blueprint availability overview (#364) menu entry should be shown. The
   * overview is restricted to admins, officers (their Staffel) and Spezialkommando leads (their SK)
   * — but the frontend session flattens SK-lead into {@code ROLE_LOGISTICIAN}, so the lead bit is
   * invisible here. We therefore reuse the backend's authoritative gate, resolved once per request
   * by {@link #meCapabilities()}.
   *
   * @param caps the per-request capability flags resolved by {@link #meCapabilities()}.
   * @return {@code true} iff the caller may open the blueprint availability overview.
   */
  @ModelAttribute("canSeeBlueprintOverview")
  public boolean canSeeBlueprintOverview(
      @ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canSeeBlueprintOverview();
  }

  /**
   * Whether the authenticated caller may enter the Job-Order area (the order list + order details).
   * Drives the sidebar's "Aufträge" vs "Auftrag anlegen" link split and the {@code
   * JobOrderPageController} redirect for non-viewers: only admins and members of a profit-eligible
   * Staffel/SK may see orders, while a non-profit member keeps the create entry only — the "submit
   * but don't track" posture the public request form used to give an anonymous visitor
   * (REQ-ORDERS-023 relaxes it for the requesting unit's own members). The backend gate ({@code
   * OwnerScopeService.canViewJobOrders}) is authoritative; this attribute only steers the UI and
   * fails closed via {@link #meCapabilities()}.
   *
   * @param caps the per-request capability flags resolved by {@link #meCapabilities()}.
   * @return {@code true} iff the caller may view job orders.
   */
  @ModelAttribute("canViewJobOrders")
  public boolean canViewJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewJobOrders();
  }

  /**
   * Whether the authenticated caller may view the orders their own org unit requested — the "Meine
   * Auftr&auml;ge" requester capability (REQ-ORDERS-023). Drives the sidebar link for a non-profit
   * ordering-squad member (who fails {@link #canViewJobOrders(CapabilitiesResponse)}) and lets the
   * {@code JobOrderPageController} render their own placed orders instead of redirecting them to
   * the create form. The backend gate ({@code OwnerScopeService.canViewOwnJobOrders}) is
   * authoritative; this attribute only steers the UI and fails closed via {@link
   * #meCapabilities()}.
   *
   * @param caps the per-request capability flags resolved by {@link #meCapabilities()}.
   * @return {@code true} iff the caller may view the orders their own org unit requested.
   */
  @ModelAttribute("canViewOwnJobOrders")
  public boolean canViewOwnJobOrders(@ModelAttribute("meCapabilities") CapabilitiesResponse caps) {
    return caps != null && caps.canViewOwnJobOrders();
  }

  /**
   * Computes whether the promotion subsystem is exposed to the current caller. The active squadron
   * (admin pin or non-admin home staffel) decides:
   *
   * <ul>
   *   <li>Admin without an active pin — {@code activeSquadron} resolves to {@code null} (all-scopes
   *       mode); the menu stays visible ({@code isAdmin()} is {@code true}) but the promotion pages
   *       render a "pick a squadron" prompt instead of a cross-staffel merge, because a promotion
   *       catalog is inherently per-staffel. The admin selects a staffel via the switcher to view
   *       or manage its system (creating topics/requirements already requires a pin server-side).
   *   <li>Admin pinned to a squadron — {@code activeSquadron} reflects the pin and its {@code
   *       isPromotionEnabled()} flag drives the menu visibility. An admin who pinned a squadron
   *       with promotion disabled now sees the same hidden-menu state as a member would — which
   *       matches the pinned-view UX promise. To re-enable, the admin clears the pin (back to
   *       all-scopes) or navigates directly to {@code /admin/settings} (not gated by this check).
   *   <li>Non-admin with a home staffel — that staffel's flag decides, unchanged from previous
   *       behaviour.
   *   <li>Anonymous / squadron-less non-admin — {@code activeSquadron} is {@code null} and {@code
   *       isAdmin()} is {@code false}, so the menu is hidden and {@code requirePromotionFeature}
   *       blocks direct page access: such a caller has no promotion system of their own.
   * </ul>
   *
   * <p>The active squadron is produced by {@code OrgUnitContextAdvice#activeSquadron} and
   * cross-injected here by name. The sidebar's {@code Beförderung} section reads this attribute via
   * {@code th:if="${promotionFeatureEnabled}"}, and every {@code PromotionPageController} {@code
   * GetMapping} blocks the request with HTTP 403 when it resolves to {@code false}.
   *
   * <p>The earlier blanket admin bypass was dropped because it broke the pinned-view UX — see
   * CLAUDE.md "Multi-squadron tenancy" for the updated semantics.
   *
   * @param activeSquadron previously-resolved squadron mini-record, or {@code null}.
   * @return {@code true} when the promotion menu should be exposed; {@code false} when it must be
   *     hidden / blocked.
   */
  @ModelAttribute("promotionFeatureEnabled")
  public boolean promotionFeatureEnabled(
      @ModelAttribute("activeSquadron") SquadronDto activeSquadron) {
    if (activeSquadron == null) {
      // No single active staffel: an admin in all-scopes mode keeps the menu (the pages then
      // prompt to pick a staffel), while a squadron-less non-admin / anonymous caller has no
      // promotion system, so the menu is hidden and direct page access is blocked.
      return authHelper.isAdmin();
    }
    if (activeSquadron.isPromotionEnabled() == null) {
      return true;
    }
    return activeSquadron.isPromotionEnabled();
  }

  /**
   * Wire-shape mirror of the backend's {@code MeController.CapabilitiesResponse}. Kept local to
   * avoid a frontend dependency on the backend module for one JSON envelope.
   *
   * @param canSeeBlueprintOverview {@code true} iff the caller may open the blueprint availability
   *     overview.
   * @param canViewJobOrders {@code true} iff the caller may enter the Job-Order area.
   * @param canViewOwnJobOrders {@code true} iff the caller may view the orders their own org unit
   *     requested (the "Meine Auftr&auml;ge" requester capability, REQ-ORDERS-023).
   */
  public record CapabilitiesResponse(
      boolean canSeeBlueprintOverview, boolean canViewJobOrders, boolean canViewOwnJobOrders) {}
}
