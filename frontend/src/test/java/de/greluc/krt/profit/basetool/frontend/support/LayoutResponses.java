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

package de.greluc.krt.profit.basetool.frontend.support;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.UUID;

/**
 * Builds canned {@code GET /api/v1/me/layout} answers for tests that stub the mocked {@code
 * BackendApiClient}. Since FE-PERF-01 the layout advices read their org-unit context, capability
 * flags and unread count from that one endpoint, so a render test that needs, say, {@code
 * canViewJobOrders} stubs the layout answer instead of the retired {@code /api/v1/me/capabilities}
 * read. Every factory leaves the parts it is not about at their fail-closed value.
 */
public final class LayoutResponses {

  /** The backend path the layout advices read, as {@code LayoutContextLoader} calls it. */
  public static final String PATH = "/api/v1/me/layout";

  private LayoutResponses() {}

  /**
   * A layout answer carrying only the three capability flags the web layout reads.
   *
   * @param canSeeBlueprintOverview whether the blueprint availability overview is offered
   * @param canViewJobOrders whether the Job-Order area is offered
   * @param canViewOwnJobOrders whether the own-unit "Meine Aufträge" view is offered
   * @return the answer, with no org unit, no pinnable units and no unread notifications
   */
  public static MeLayoutResponse capabilities(
      boolean canSeeBlueprintOverview, boolean canViewJobOrders, boolean canViewOwnJobOrders) {
    return new MeLayoutResponse(
        null,
        List.of(),
        new CapabilitiesResponse(canSeeBlueprintOverview, canViewJobOrders, canViewOwnJobOrders),
        0L);
  }

  /**
   * A layout answer carrying only the effective org unit a non-admin's home Staffel resolves to.
   *
   * @param activeOrgUnitId the effective org unit, or {@code null} for none
   * @return the answer, with every capability off, no pinnable units and no unread notifications
   */
  public static MeLayoutResponse activeOrgUnit(UUID activeOrgUnitId) {
    return new MeLayoutResponse(activeOrgUnitId, List.of(), CapabilitiesResponse.NONE, 0L);
  }

  /**
   * A layout answer carrying only the org units the caller may pin.
   *
   * @param orgUnits the pinnable org units, in switcher order
   * @return the answer, with no active org unit, every capability off and no unread notifications
   */
  public static MeLayoutResponse orgUnits(List<OrgUnitMembershipOptionDto> orgUnits) {
    return new MeLayoutResponse(null, List.copyOf(orgUnits), CapabilitiesResponse.NONE, 0L);
  }
}
