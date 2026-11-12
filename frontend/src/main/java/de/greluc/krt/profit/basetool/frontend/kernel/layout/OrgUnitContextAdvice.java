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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader.ContextKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the active org-unit context to every model of a {@link UsesLayoutModel} controller for the
 * layout fragments: {@code activeSquadronId}, {@code activeOrgUnit}, {@code availableSquadrons},
 * {@code availableOrgUnits}, {@code isAllSquadronsMode}, {@code isAllMyOrgUnitsMode}, {@code
 * contextSpansOrgUnits} and {@code activeOrgUnitContextKey}.
 *
 * <p>The context is the backend's effective one from the request's {@code GET /api/v1/me/layout}
 * read via {@link LayoutContextLoader}, so the page shows exactly the context its data was read in
 * (REQ-ORG-024). Backend failures degrade to empty or {@code null} values instead of failing the
 * page.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
@Slf4j
public class OrgUnitContextAdvice {

  /** The prefix of {@code activeOrgUnitContextKey} for a context of one org unit. */
  private static final String ORG_UNIT_CONTEXT_KEY_PREFIX = "ORG_UNIT:";

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
   * Resolves {@code activeSquadronId}: the one org unit the backend's effective context covers, the
   * honoured pin or a member's only unit. {@code null} when the context covers several org units
   * („Alle meine Org-Einheiten" or an admin's „Alle Org-Einheiten"), none, for anonymous callers
   * and on backend failure.
   *
   * @param request the current HTTP servlet request; never {@code null}.
   * @return the active org unit's UUID, or {@code null}.
   */
  @Nullable
  @ModelAttribute("activeSquadronId")
  public UUID activeSquadronId(HttpServletRequest request) {
    if (!authHelper.isAuthenticated()) {
      return null;
    }
    return layoutContextLoader.load(request).activeOrgUnitId();
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
   * Loads the squadron catalogue once per request for the squadron pickers. Empty for anonymous
   * callers, on backend failure, and for handlers that read no model attribute.
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
      SectionFallback.rethrowIfReauthenticationRequired(ex);
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
   * {@code true} when the context covers every org unit: an admin without a pin.
   *
   * @param request the current request, through which the layout context is memoised
   * @return whether the current viewer is an admin in „Alle Org-Einheiten"
   */
  @ModelAttribute("isAllSquadronsMode")
  public boolean isAllSquadronsMode(HttpServletRequest request) {
    return contextKind(request) == ContextKind.ALL_ORG_UNITS;
  }

  /**
   * {@code true} when the context covers all of a member's several org units: „Alle meine
   * Org-Einheiten", a non-admin without a pin who belongs to more than one unit.
   *
   * @param request the current request, through which the layout context is memoised
   * @return whether the current viewer is a member in „Alle meine Org-Einheiten"
   */
  @ModelAttribute("isAllMyOrgUnitsMode")
  public boolean isAllMyOrgUnitsMode(HttpServletRequest request) {
    return contextKind(request) == ContextKind.ALL_MY_ORG_UNITS;
  }

  /**
   * {@code true} when the context covers several org units, so a list shows which unit each row
   * belongs to.
   *
   * @param isAllSquadronsMode whether an admin views every org unit
   * @param isAllMyOrgUnitsMode whether a member views all of their org units
   * @return whether rows carry their org unit
   */
  @ModelAttribute("contextSpansOrgUnits")
  public boolean contextSpansOrgUnits(
      @ModelAttribute("isAllSquadronsMode") boolean isAllSquadronsMode,
      @ModelAttribute("isAllMyOrgUnitsMode") boolean isAllMyOrgUnitsMode) {
    return isAllSquadronsMode || isAllMyOrgUnitsMode;
  }

  /**
   * The key a page announces to the browser's other tabs (REQ-ORG-032): {@code ORG_UNIT:<id>},
   * {@code ALL_MY_ORG_UNITS} or {@code ALL_ORG_UNITS}; {@code null} when no context could be read,
   * so the page takes no part.
   *
   * @param request the current request, through which the layout context is memoised
   * @return the context key, or {@code null}
   */
  @Nullable
  @ModelAttribute("activeOrgUnitContextKey")
  public String activeOrgUnitContextKey(HttpServletRequest request) {
    if (!authHelper.isAuthenticated()) {
      return null;
    }
    LayoutContextLoader.LayoutContext context = layoutContextLoader.load(request);
    return switch (context.activeContext()) {
      case ORG_UNIT ->
          context.activeOrgUnitId() == null
              ? null
              : ORG_UNIT_CONTEXT_KEY_PREFIX + context.activeOrgUnitId();
      case ALL_MY_ORG_UNITS, ALL_ORG_UNITS -> context.activeContext().name();
      case NONE -> null;
    };
  }

  /**
   * What the backend's effective context covers for the current request.
   *
   * @param request the current request
   * @return the kind; {@link ContextKind#NONE} for anonymous callers and on backend failure
   */
  @NotNull
  private ContextKind contextKind(@NotNull HttpServletRequest request) {
    return authHelper.isAuthenticated()
        ? layoutContextLoader.load(request).activeContext()
        : ContextKind.NONE;
  }
}
