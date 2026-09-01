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

import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Cross-cutting advice that injects the active OrgUnit context into every rendered model so the
 * sidebar switcher / context badge / squadron-aware columns can be rendered from the layout
 * fragments without each page controller having to load the data separately.
 *
 * <p>Populates the core org-unit model attributes the layout fragments read:
 *
 * <ul>
 *   <li>{@code activeSquadronId} — UUID of the squadron the backend currently scopes queries to, or
 *       {@code null} when an admin is in "all squadrons" mode or the user has no assigned squadron
 *       (also {@code null} for anonymous callers).
 *   <li>{@code activeSquadron} — {@link SquadronDto} resolved from the id, or {@code null} when no
 *       context applies. Used by the templates to render the shorthand badge and the dropdown
 *       selection state.
 *   <li>{@code activeOrgUnit} — the kind-tagged {@link OrgUnitMembershipOptionDto} for the active
 *       pin (Staffel <em>or</em> SK), resolved from the merged {@code availableOrgUnits} catalogue.
 *   <li>{@code availableSquadrons} — the full {@link SquadronDto} list the admin can switch to.
 *       Empty for non-admin / anonymous callers (they never see the switcher control).
 *   <li>{@code availableOrgUnits} — the merged Squadron + SpecialCommand switcher catalogue.
 *   <li>{@code isAllSquadronsMode} — {@code true} when an admin is currently viewing the
 *       cross-staffel union (no active selection). Members and guests never enter this mode and
 *       always see {@code false}.
 * </ul>
 *
 * <p>These attributes are consumed cross-bean: {@code appTitle} (in {@code LayoutMiscAdvice}) reads
 * {@code activeOrgUnit} + {@code isAllSquadronsMode}, and {@code promotionFeatureEnabled} (in
 * {@code CapabilityFlagsAdvice}) reads {@code activeSquadron}. Spring's {@code ModelFactory} orders
 * {@code @ModelAttribute} methods by dependency across all advice beans, so those cross-references
 * resolve regardless of the bean they live in.
 *
 * <p>Failures from the backend round-trip degrade gracefully: a non-resolvable active-org-unit call
 * leaves the badge empty; a non-resolvable squadron list leaves the dropdown empty. We never let an
 * unrelated UI fail because the context advice could not reach the backend — the page would render
 * empty cells for the squadron columns but the rest of the layout stays intact.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class OrgUnitContextAdvice {

  /** Captured generic type for decoding the paged Squadron catalogue. */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<>() {};

  /** Captured generic type for decoding the caller's OrgUnit-membership option rows. */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final FrontendAuthHelperService authHelper;

  /**
   * Resolves {@code activeSquadronId} for the current request. Two different paths because the
   * state lives in different places for admins and members:
   *
   * <ul>
   *   <li>Admin: read the switcher selection from the frontend's Redis-backed Spring Session (set
   *       by {@link MeFrontendController}). {@code null} means "all squadrons" mode.
   *   <li>Non-admin: the user's persistent home squadron from {@code app_user.squadron_id} on the
   *       backend. The {@code GET /api/v1/me/active-org-unit} endpoint already resolves this for
   *       the current principal; we reuse it instead of duplicating the lookup on the frontend.
   * </ul>
   *
   * <p>Anonymous callers return {@code null}; the failure of the backend round-trip degrades
   * silently to {@code null} so an unrelated UI never breaks because of this advice.
   *
   * @param request the current HTTP servlet request; never {@code null}.
   * @return the active squadron UUID, or {@code null}.
   */
  @ModelAttribute("activeSquadronId")
  public UUID activeSquadronId(HttpServletRequest request) {
    if (!authHelper.isAuthenticated()) {
      return null;
    }
    HttpSession session = request.getSession(false);
    if (session != null) {
      UUID fromSession =
          de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext.coerce(
              session.getAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY));
      if (fromSession != null) {
        return fromSession;
      }
    }
    if (authHelper.isAdmin()) {
      // Admin without an active session pin → all-scopes mode, no badge.
      return null;
    }
    try {
      ActiveOrgUnitResponse resp =
          backendApiClient.get("/api/v1/me/active-org-unit", ActiveOrgUnitResponse.class);
      return resp != null ? resp.orgUnitId() : null;
    } catch (Exception ex) {
      log.debug("Failed to resolve home squadron for non-admin caller", ex);
      return null;
    }
  }

  /**
   * Wire-shape mirror of the backend's {@code MeController.ActiveOrgUnitResponse} record. Kept
   * local to avoid a frontend dependency on the backend module just for one JSON envelope.
   *
   * @param orgUnitId resolved OrgUnit UUID, or {@code null} when none applies.
   */
  public record ActiveOrgUnitResponse(UUID orgUnitId) {}

  /**
   * Resolves the full {@link SquadronDto} that matches {@link #activeSquadronId} so the template
   * can render the shorthand badge without doing a second lookup. {@code null} when no active
   * squadron applies.
   *
   * @param activeSquadronId previously-resolved id (Spring re-injects model attributes between
   *     {@code @ModelAttribute} methods).
   * @param availableSquadrons the squadron catalogue the advice already loaded; reused to avoid a
   *     dedicated per-id GET.
   * @return matching squadron, or {@code null}.
   */
  @ModelAttribute("activeSquadron")
  public SquadronDto activeSquadron(
      @ModelAttribute("activeSquadronId") UUID activeSquadronId,
      @ModelAttribute("availableSquadrons") List<SquadronDto> availableSquadrons) {
    if (activeSquadronId == null || availableSquadrons == null) {
      return null;
    }
    return availableSquadrons.stream()
        .filter(s -> activeSquadronId.equals(s.id()))
        .findFirst()
        .orElse(null);
  }

  /**
   * R5.e / SPEZIALKOMMANDO_PLAN.md §7.2 — resolves the active context to an {@link
   * OrgUnitMembershipOptionDto} (carries the {@code kind} discriminator) so the context chip can
   * render {@code [Staffel: IRI]} vs {@code [SK: ALPHA]} and apply a kind-specific style. Where
   * {@link #activeSquadron} can only resolve {@code SQUADRON}-kind pins (its catalogue is the
   * Squadron-only list), this attribute reads from {@link #availableOrgUnits()} which already
   * carries the merged Squadron + SK catalogue with the discriminator inline.
   *
   * <p>Returns {@code null} when no pin is active (admin in all-OrgUnits mode, member with no home
   * Staffel) or the pinned id is not present in the caller's catalogue (e.g. an admin pin that
   * predates the destructive cleanup release and no longer resolves).
   *
   * @param activeOrgUnitId previously-resolved id (Spring re-injects model attributes between
   *     {@code @ModelAttribute} methods); {@code null} when no pin applies.
   * @param availableOrgUnits the OrgUnit catalogue the advice already loaded; reused to avoid a
   *     dedicated per-id GET.
   * @return matching option (kind-tagged), or {@code null}.
   */
  @ModelAttribute("activeOrgUnit")
  public OrgUnitMembershipOptionDto activeOrgUnit(
      @ModelAttribute("activeSquadronId") UUID activeOrgUnitId,
      @ModelAttribute("availableOrgUnits") List<OrgUnitMembershipOptionDto> availableOrgUnits) {
    if (activeOrgUnitId == null || availableOrgUnits == null) {
      return null;
    }
    return availableOrgUnits.stream()
        .filter(o -> activeOrgUnitId.equals(o.orgUnitId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Loads the squadron catalogue once per request. Returned to admins for the switcher dropdown and
   * reused by {@link #activeSquadron(UUID, List)} to dereference the active id without a second
   * round-trip. Empty list for anonymous callers or when the backend call fails - the dropdown
   * gracefully renders without options rather than 500ing the page.
   *
   * @return list of active squadrons, ordered by name; never {@code null}.
   */
  @ModelAttribute("availableSquadrons")
  public List<SquadronDto> availableSquadrons() {
    if (!authHelper.isAuthenticated()) {
      return List.of();
    }
    // R5.e: kept identical to the pre-R5.e semantics — load the full Squadron catalogue for
    // every authenticated caller. The {@link #activeSquadron} dereference and the per-squadron
    // {@code promotionEnabled} gate downstream both read from this list, so a non-admin narrowing
    // would break the {@code activeSquadron} resolution for non-admin pages. The new sidebar
    // switcher reads {@link #availableOrgUnits} instead — the two attributes coexist with disjoint
    // purposes.
    try {
      // Slow-changing global catalogue, identical URI for every caller — route through the
      // 10-min STATIC_DATA_CACHE (same entry the page controllers already cache, evicted on admin
      // squadron mutations) so this advice does not re-fetch it on every authenticated render and
      // shares the cached entry with the admin switcher's identical call below (REQ-DATA-007).
      PageResponse<SquadronDto> page =
          backendApiClient.getCached(CachedCatalog.SQUADRONS, SQUADRON_PAGE);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception ex) {
      log.debug("Failed to load squadron list for sidebar dropdown", ex);
      return List.of();
    }
  }

  /**
   * R5.e — list of {@link OrgUnitMembershipOptionDto} that the caller can switch their active scope
   * to. Replaces {@link #availableSquadrons()} for the post-R5.e sidebar switcher: admins see the
   * full Squadron + SpecialCommand catalogue; non-admins see only the OrgUnits they are a member of
   * (Staffel + every SK membership). The switcher template hides itself when this list has fewer
   * than two entries — no choice to offer means no UI noise (plan §7.2).
   *
   * <p>Backend round-trips:
   *
   * <ul>
   *   <li><strong>One call, no branch here.</strong> {@code GET /api/v1/me/org-units} answers it:
   *       an admin gets every active Staffel and Spezialkommando, everyone else their own
   *       memberships. The fork used to live in this class — page-walking {@code /squadrons} plus
   *       {@code /special-commands} for admins, and two round-trips ({@code /users/me}, then {@code
   *       /users/{id}/memberships}) for everyone else. The Android client had to know the same rule
   *       and did not, so an admin was offered nothing to pin at all (ADR-0151, REQ-SEC-048). One
   *       endpoint means one place to be right, and collapses up to four round-trips into one.
   * </ul>
   *
   * <p>Failures degrade silently to an empty list — the switcher then hides itself rather than
   * 500'ing the sidebar render.
   *
   * @return the OrgUnit options visible in the switcher; never {@code null}.
   */
  @ModelAttribute("availableOrgUnits")
  public List<OrgUnitMembershipOptionDto> availableOrgUnits() {
    if (!authHelper.isAuthenticated()) {
      return List.of();
    }
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get("/api/v1/me/org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return options != null ? options : List.of();
    } catch (Exception ex) {
      log.debug("Failed to load pinnable org units for the switcher", ex);
      return List.of();
    }
  }

  /**
   * {@code true} when the current admin is viewing the cross-staffel union (no active squadron
   * selection). False for everyone else - non-admins always operate in their persistent home
   * squadron and cannot enter this mode.
   *
   * @param activeSquadronId previously-resolved id, {@code null} signals all-squadrons mode for
   *     admins.
   * @return whether the current viewer is an admin without a selection.
   */
  @ModelAttribute("isAllSquadronsMode")
  public boolean isAllSquadronsMode(@ModelAttribute("activeSquadronId") UUID activeSquadronId) {
    return authHelper.isAdmin() && activeSquadronId == null;
  }
}
