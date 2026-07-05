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
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationCountResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Cross-cutting advice that injects the current squadron context into every rendered model so the
 * sidebar switcher / context badge / squadron-aware columns can be rendered from the layout
 * fragments without each page controller having to load the data separately.
 *
 * <p>Populates the cross-cutting model attributes the layout fragments read. The core org-unit
 * context ones:
 *
 * <ul>
 *   <li>{@code activeSquadronId} — UUID of the squadron the backend currently scopes queries to, or
 *       {@code null} when an admin is in "all squadrons" mode or the user has no assigned squadron
 *       (also {@code null} for anonymous callers).
 *   <li>{@code activeSquadron} — {@link SquadronDto} resolved from the id, or {@code null} when no
 *       context applies. Used by the templates to render the shorthand badge and the dropdown
 *       selection state.
 *   <li>{@code availableSquadrons} — the full {@link SquadronDto} list the admin can switch to.
 *       Empty for non-admin / anonymous callers (they never see the switcher control).
 *   <li>{@code isAllSquadronsMode} — {@code true} when an admin is currently viewing the
 *       cross-staffel union (no active selection). Members and guests never enter this mode and
 *       always see {@code false}.
 * </ul>
 *
 * <p>Beyond those it also exposes the merged OrgUnit switcher catalogue ({@code availableOrgUnits},
 * {@code activeOrgUnit}), the per-principal capability flags resolved once via {@code
 * meCapabilities} ({@code canSeeBlueprintOverview} / {@code canViewJobOrders}), the dynamic {@code
 * appTitle}, {@code promotionFeatureEnabled}, and {@code currentRequestUri} — each documented on
 * its own {@code @ModelAttribute} method below.
 *
 * <p>Failures from the backend round-trip degrade gracefully: a non-resolvable active-org-unit call
 * leaves the badge empty; a non-resolvable squadron list leaves the dropdown empty. We never let an
 * unrelated UI fail because the context advice could not reach the backend — the page would render
 * empty cells for the squadron columns but the rest of the layout stays intact.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class SquadronContextAdvice {

  /** Captured generic type for decoding the paged Squadron catalogue. */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<>() {};

  /** Captured generic type for decoding the caller's OrgUnit-membership option rows. */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;
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
   * Resolves the full {@link SquadronDto} that matches {@link #activeSquadronId()} so the template
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
    // would break the {@code SquadronContextAdvice.activeSquadron} resolution for non-admin
    // pages. The new sidebar switcher reads {@link #availableOrgUnits} instead — the two
    // attributes coexist with disjoint purposes.
    try {
      // Slow-changing global catalogue, identical URI for every caller — route through the
      // 10-min STATIC_DATA_CACHE (same entry the page controllers already cache, evicted on admin
      // squadron mutations) so this advice does not re-fetch it on every authenticated render and
      // shares the cached entry with the admin switcher's identical call below (REQ-DATA-007).
      PageResponse<SquadronDto> page =
          backendApiClient.getCached("/api/v1/squadrons?size=1000&sort=name,asc", SQUADRON_PAGE);
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
   *   <li>Admin: still uses {@code /api/v1/squadrons} (Staffel-only today) merged with {@code
   *       /api/v1/special-commands}. Both calls are paginated to {@code size=1000} which is
   *       generous for the foreseeable OrgUnit population.
   *   <li>Non-admin: uses the lean {@code /api/v1/users/{id}/memberships} that R5.d.a introduced
   *       for the picker fragment. Reuses the existing wire shape so no new endpoint is needed.
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
    if (authHelper.isAdmin()) {
      return loadAdminOrgUnitCatalogue();
    }
    return loadCallerMemberships();
  }

  /**
   * Admin path of {@link #availableOrgUnits()} — concatenates the Squadron catalogue with the
   * SpecialCommand catalogue. Both lists are paginated server-side; we ask for {@code size=1000}
   * which exceeds the foreseeable OrgUnit count by an order of magnitude.
   *
   * @return Squadron + SK catalogue, never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> loadAdminOrgUnitCatalogue() {
    java.util.List<OrgUnitMembershipOptionDto> combined = new java.util.ArrayList<>();
    try {
      // Cached global catalogue — same URI (and therefore same STATIC_DATA_CACHE entry) as
      // availableSquadrons() above, so the admin render no longer double-fetches the squadron list.
      PageResponse<SquadronDto> squadrons =
          backendApiClient.getCached("/api/v1/squadrons?size=1000&sort=name,asc", SQUADRON_PAGE);
      if (squadrons != null && squadrons.content() != null) {
        for (SquadronDto s : squadrons.content()) {
          combined.add(
              new OrgUnitMembershipOptionDto(
                  s.id(), s.name(), s.shorthand(), "SQUADRON", s.isProfitEligible()));
        }
      }
    } catch (Exception ex) {
      log.debug("Failed to load Squadron catalogue for admin switcher", ex);
    }
    try {
      // Cached global catalogue (REQ-DATA-007): the SK catalogue is now cacheable because every SK
      // lifecycle mutation evicts STATIC_DATA_CACHE — AdminSpecialCommandsPageController
      // create/update/delete/activate (+ AJAX twins) and SpecialCommandAdminProxyController's
      // profit-eligible flip all call clearStaticDataCache(). Same URI-keyed entry the admin
      // switcher shares, so the SK list is fetched at most once per TTL app-wide instead of on
      // every
      // admin render.
      PageResponse<SquadronDto> specialCommands =
          backendApiClient.getCached(
              "/api/v1/special-commands?size=1000&sort=name,asc", SQUADRON_PAGE);
      if (specialCommands != null && specialCommands.content() != null) {
        for (SquadronDto sk : specialCommands.content()) {
          combined.add(
              new OrgUnitMembershipOptionDto(
                  sk.id(), sk.name(), sk.shorthand(), "SPECIAL_COMMAND", sk.isProfitEligible()));
        }
      }
    } catch (Exception ex) {
      log.debug("Failed to load SpecialCommand catalogue for admin switcher", ex);
    }
    return combined;
  }

  /**
   * Non-admin path of {@link #availableOrgUnits()} — reads the caller's memberships via the lean
   * R5.d.a endpoint. One round-trip to {@code /api/v1/users/me} to resolve the principal's id, then
   * one to {@code /api/v1/users/{id}/memberships}; the second call already returns the Staffel +
   * every SK membership in one shot.
   *
   * @return the caller's memberships, never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> loadCallerMemberships() {
    try {
      UserDto me = backendApiClient.get("/api/v1/users/me", UserDto.class);
      if (me == null || me.id() == null) {
        return List.of();
      }
      List<OrgUnitMembershipOptionDto> memberships =
          backendApiClient.get(
              "/api/v1/users/" + me.id() + "/memberships", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
      return memberships != null ? memberships : List.of();
    } catch (Exception ex) {
      log.debug("Failed to load memberships for non-admin switcher", ex);
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

  /**
   * Composes the dynamic application title rendered in the {@code <title>} tag and the sidebar
   * brand logo — the single place the active OrgUnit context surfaces to the user (REQ-ORG-010; the
   * previously-redundant top-right context chip was removed). Resolution:
   *
   * <ul>
   *   <li>An active pin of <em>either</em> kind ({@code SQUADRON} or {@code SPECIAL_COMMAND}) →
   *       "Profit Basetool – &lt;shorthand&gt;", falling back to the OrgUnit name when it carries
   *       no shorthand. Reading from {@link #activeOrgUnit} (the merged Staffel + SK catalogue) —
   *       rather than the Squadron-only {@link #activeSquadron} — is what lets an SK pin show in
   *       the title at all; the chip used to be the only surface that did.
   *   <li>Admin in all-OrgUnits mode (no pin) → "Profit Basetool – Alle Staffeln".
   *   <li>No context (squadron-less non-admin, anonymous) → plain "Profit Basetool".
   * </ul>
   *
   * <p>Resolution uses the request locale via {@link LocaleContextHolder} so the suffix is
   * localised consistently with the rest of the page (the message-format pattern {@code {0}} is
   * filled with the OrgUnit shorthand/name or the localised "all squadrons" label). The {@code
   * app.title.with.squadron} key name predates SK support and is kept generic — it now serves any
   * OrgUnit kind.
   *
   * @param activeOrgUnit resolved active OrgUnit (Staffel or SK), or {@code null}.
   * @param isAllSquadronsMode whether the current viewer is an admin without a selection.
   * @return the rendered title string, never {@code null}.
   */
  @ModelAttribute("appTitle")
  public String appTitle(
      @ModelAttribute("activeOrgUnit") OrgUnitMembershipOptionDto activeOrgUnit,
      @ModelAttribute("isAllSquadronsMode") boolean isAllSquadronsMode) {
    Locale locale = LocaleContextHolder.getLocale();
    if (activeOrgUnit != null) {
      String label =
          activeOrgUnit.orgUnitShorthand() != null
              ? activeOrgUnit.orgUnitShorthand()
              : activeOrgUnit.orgUnitName();
      return messageSource.getMessage("app.title.with.squadron", new Object[] {label}, locale);
    }
    if (isAllSquadronsMode) {
      String allLabel = messageSource.getMessage("squadron.switcher.all", null, locale);
      return messageSource.getMessage("app.title.all.squadrons", new Object[] {allLabel}, locale);
    }
    return messageSource.getMessage("app.title", null, locale);
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
   * <p>The sidebar's {@code Beförderung} section reads this attribute via {@code
   * th:if="${promotionFeatureEnabled}"}, and every {@code PromotionPageController} {@code
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
      return new CapabilitiesResponse(false, false);
    }
    if (authHelper.isAdmin()) {
      return new CapabilitiesResponse(true, true);
    }
    try {
      CapabilitiesResponse resp =
          backendApiClient.get("/api/v1/me/capabilities", CapabilitiesResponse.class);
      return resp != null ? resp : new CapabilitiesResponse(false, false);
    } catch (Exception ex) {
      log.debug("Failed to resolve me-capabilities", ex);
      return new CapabilitiesResponse(false, false);
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
   * Staffel/SK may see orders, while a non-profit member keeps the create entry only — mirroring
   * the anonymous "submit but don't track" flow. The backend gate ({@code
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
   * The caller's unread-notification count, fed to the always-on bell badge rendered on every page
   * (REQ-NOTIF-006). Resolved once per request; fails soft to zero so a backend hiccup hides the
   * badge rather than breaking the chrome, and the bell's client-side polling keeps it fresh after
   * the initial render.
   *
   * @return the unread count, or {@code 0} when unauthenticated or on a backend error.
   */
  @ModelAttribute("unreadNotificationCount")
  public long unreadNotificationCount() {
    if (!authHelper.isAuthenticated()) {
      return 0L;
    }
    try {
      NotificationCountResponse resp =
          backendApiClient.get(
              "/api/v1/notifications/unread-count", NotificationCountResponse.class);
      return resp != null && resp.count() != null ? resp.count() : 0L;
    } catch (Exception ex) {
      log.debug("Failed to resolve unread notification count", ex);
      return 0L;
    }
  }

  /**
   * Wire-shape mirror of the backend's {@code MeController.CapabilitiesResponse}. Kept local to
   * avoid a frontend dependency on the backend module for one JSON envelope.
   *
   * @param canSeeBlueprintOverview {@code true} iff the caller may open the blueprint availability
   *     overview.
   * @param canViewJobOrders {@code true} iff the caller may enter the Job-Order area.
   */
  public record CapabilitiesResponse(boolean canSeeBlueprintOverview, boolean canViewJobOrders) {}

  /**
   * The request URI the sidebar switcher form posts back as {@code _referer} so the redirect after
   * the squadron change lands the user on the same page they were on. We resolve it via a model
   * attribute rather than the Thymeleaf {@code #httpServletRequest} utility because the latter is
   * not exposed in every render context (MockMvc tests in particular).
   *
   * @param request the current HTTP servlet request injected by Spring; never {@code null}.
   * @return the path + query of the current request, or {@code "/"} as a defensive fallback.
   */
  @ModelAttribute("currentRequestUri")
  public String currentRequestUri(HttpServletRequest request) {
    if (request == null) {
      return "/";
    }
    String uri = request.getRequestURI();
    String query = request.getQueryString();
    if (uri == null || uri.isBlank()) {
      return "/";
    }
    return query != null && !query.isBlank() ? uri + "?" + query : uri;
  }
}
