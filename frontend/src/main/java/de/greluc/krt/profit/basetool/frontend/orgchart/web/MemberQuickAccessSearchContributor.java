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

package de.greluc.krt.profit.basetool.frontend.orgchart.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.orgchart.client.OrgChartBackendClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds the people the org chart shows, by the name it shows, so a member finds exactly the people
 * the chart page displays and nothing the page does not (REQ-UI-033).
 */
@Component
@Order(8)
@RequiredArgsConstructor
public class MemberQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final String RANK_KEY_PREFIX = "orgChart.rank.";
  private static final String MEMBER_EDIT_PATH = "/members/";

  private final OrgChartBackendClient orgChartClient;

  /**
   * Searches members.
   *
   * @return {@link QuickAccessSearchKind#MEMBER}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.MEMBER;
  }

  /**
   * Filters the org chart read for matching people.
   *
   * @param query the search term
   * @param limit the most hits
   * @return the people as hits on the org chart with the person highlighted, their rank as status;
   *     an account also opens its member edit page in administration mode
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    return OrgChartSearchEntries.matchingPeople(orgChartClient.orgChart(), query, limit).stream()
        .map(p ->
            new QuickAccessHit(
                p.highlightKey() == null
                    ? OrgChartPageController.PATH
                    : OrgChartPageController.highlightHref(p.highlightKey()),
                p.name(),
                RANK_KEY_PREFIX + p.positionType(),
                null,
                p.accountId() == null ? null : MEMBER_EDIT_PATH + p.accountId()))
        .toList();
  }
}
