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
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the active org-unit context to every model of a {@link UsesLayoutModel} controller for the
 * layout fragments: {@code activeSquadronId}, {@code activeSquadron}, {@code activeOrgUnit}, {@code
 * availableSquadrons}, {@code availableOrgUnits} and {@code isAllSquadronsMode}.
 *
 * <p>Data comes from the request's {@code GET /api/v1/me/layout} read via {@link
 * LayoutContextLoader} and the cached squadron catalogue. Backend failures degrade to empty or
 * {@code null} values instead of failing the page.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
@Slf4j
public class OrgUnitContextAdvice {

  /** Captured generic type for decoding the paged Squadron catalogue. */
  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<>() {};

  /** The single seam to the backend, used here only for the cached squadron catalogue. */
  private final BackendApiClient backendApiClient;

  /** Reads the request's layout context once and shares it with the other layout advices. */
  private final LayoutContextLoader layoutContextLoader;

  /** Answers whether the caller is authenticated and whether they hold {@code ADMIN}. */
  private final FrontendAuthHelperService authHelper;

  /**
   * Resolves {@code activeSquadronId}: an admin's switcher selection from the session ({@code null}
   * meaning all squadrons), or a member's home squadron from {@code GET /api/v1/me/layout}. Returns
   * {@code null} for anonymous callers and on backend failure.
   *
   * @param request the current HTTP servlet request; never {@code null}.
   * @return the active squadron UUID, or {@code null}.
   */
  @Nullable
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
      return null;
    }
    return layoutContextLoader.load(request).activeOrgUnitId();
  }

  /**
   * Resolves the {@link SquadronDto} matching {@link #activeSquadronId} from the loaded catalogue.
   *
   * @param activeSquadronId the id resolved by {@link #activeSquadronId}
   * @param availableSquadrons the loaded squadron catalogue
   * @return matching squadron, or {@code null}.
   */
  @Nullable
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
   * Resolves the active pin to a kind-tagged {@link OrgUnitMembershipOptionDto} from {@link
   * #availableOrgUnits(HttpServletRequest)}, so the context chip can render Staffel and SK pins
   * differently.
   *
   * @param activeOrgUnitId the resolved pin id; {@code null} when no pin applies
   * @param availableOrgUnits the loaded org-unit catalogue
   * @return matching option (kind-tagged), or {@code null} when no pin applies or it is not in the
   *     catalogue
   */
  @Nullable
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
   * Loads the squadron catalogue once per request for the admin switcher and {@link
   * #activeSquadron(UUID, List)}. Empty for anonymous callers, on backend failure, and for handlers
   * that read no model attribute.
   *
   * @param request the current request, carrying the matched handler
   * @return list of active squadrons, ordered by name; never {@code null}.
   */
  @ModelAttribute("availableSquadrons")
  public List<SquadronDto> availableSquadrons(HttpServletRequest request) {
    if (!authHelper.isAuthenticated() || !LayoutContextLoader.needsLayoutModel(request)) {
      return List.of();
    }
    try {
      PageResponse<SquadronDto> page =
          backendApiClient.getCached(CachedCatalog.SQUADRONS, SQUADRON_PAGE);
      return page != null && page.content() != null ? page.content() : List.of();
    } catch (Exception ex) {
      log.debug("Failed to load squadron list for sidebar dropdown", ex);
      return List.of();
    }
  }

  /**
   * Lists the org units of all four kinds the caller can pin, from the {@code orgUnits} part of
   * {@code GET /api/v1/me/layout}: every active one for admins, otherwise the caller's own reach
   * (REQ-SEC-048). Empty on backend failure.
   *
   * @param request the current request, through which the layout context is memoised
   * @return the OrgUnit options visible in the switcher; never {@code null}.
   */
  @ModelAttribute("availableOrgUnits")
  public List<OrgUnitMembershipOptionDto> availableOrgUnits(HttpServletRequest request) {
    return layoutContextLoader.load(request).orgUnits();
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
