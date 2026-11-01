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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.orgchart.client.OrgChartBackendClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link OrgUnitQuickAccessSearchContributor}: the units the org chart shows. */
@ExtendWith(MockitoExtension.class)
class OrgUnitQuickAccessSearchContributorTest {

  @Mock private OrgChartBackendClient orgChartClient;

  @InjectMocks private OrgUnitQuickAccessSearchContributor contributor;

  /** It searches org units. */
  @Test
  void searchesOrgUnits() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.ORG_UNIT);
  }

  /** Every tier of the chart is searched, each hit highlighting its unit on the chart. */
  @Test
  void findsEveryTierOfTheChart() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("staffel", 10))
        .extracting(QuickAccessHit::href)
        .containsExactly(
            "/org-chart?highlight=" + OrgChartFixture.SQUADRON_ID,
            "/org-chart?highlight=" + OrgChartFixture.UNGROUPED_SQUADRON_ID);
    assertThat(contributor.results("flotte", 10))
        .extracting(QuickAccessHit::name)
        .containsExactly("Flotte");
    assertThat(contributor.results("organisationsleitung", 10))
        .extracting(QuickAccessHit::name)
        .containsExactly("Organisationsleitung");
    assertThat(contributor.results("bravo", 10))
        .extracting(QuickAccessHit::name)
        .containsExactly("Bravo SK");
  }

  /** The short tag matches too, and the match is tolerant like the browser's menu search. */
  @Test
  void matchesTheTagToleratingCase() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("wLf", 10))
        .extracting(QuickAccessHit::name)
        .containsExactly("Wolf Staffel");
  }

  /** A unit the chart does not show is not found; neither is anything for a chart without body. */
  @Test
  void findsNothingTheChartDoesNotShow() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart(), null);

    assertThat(contributor.results("zzz", 10)).isEmpty();
    assertThat(contributor.results("wolf", 10)).isEmpty();
  }

  /** The limit cuts the list, keeping the units whose name starts with the term first. */
  @Test
  void limitsKeepingPrefixMatchesFirst() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("ung", 1))
        .extracting(QuickAccessHit::name)
        .containsExactly("Ungrouped Staffel");
  }
}
