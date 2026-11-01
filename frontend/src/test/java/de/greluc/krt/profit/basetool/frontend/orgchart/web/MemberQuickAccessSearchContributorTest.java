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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MemberQuickAccessSearchContributor}: the people the org chart shows. */
@ExtendWith(MockitoExtension.class)
class MemberQuickAccessSearchContributorTest {

  @Mock private OrgChartBackendClient orgChartClient;

  @InjectMocks private MemberQuickAccessSearchContributor contributor;

  /** It searches members. */
  @Test
  void searchesMembers() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.MEMBER);
  }

  /** The search finds exactly the people the chart shows, and nobody else. */
  @Test
  void findsExactlyThePeopleOfTheChart() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    List<QuickAccessHit> everybody = contributor.results("", 100);

    assertThat(everybody)
        .extracting(QuickAccessHit::name)
        .containsExactlyInAnyOrder(
            "Alice", "Eric", "Gustav Freitext", "Dora", "Cara", "Bob", "Fred");
  }

  /** A hit highlights the person on the chart and shows the rank held there as its status. */
  @Test
  void aHitHighlightsThePersonAndShowsTheRank() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("dora", 5))
        .containsExactly(
            new QuickAccessHit(
                "/org-chart?highlight=" + OrgChartFixture.DORA,
                "Dora",
                "orgChart.rank.COMMAND_LEAD",
                null,
                "/members/" + OrgChartFixture.DORA));
    assertThat(contributor.results("alice", 5))
        .extracting(QuickAccessHit::statusKey)
        .containsExactly("orgChart.rank.OL_MEMBER");
  }

  /** A holder without an account is found by the name the chart shows and keyed by the seat. */
  @Test
  void aHolderWithoutAnAccountIsKeyedBySeat() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("freitext", 5))
        .extracting(QuickAccessHit::adminHref)
        .containsExactly((String) null);
    assertThat(contributor.results("freitext", 5))
        .extracting(QuickAccessHit::href)
        .containsExactly("/org-chart?highlight=" + OrgChartFixture.FREE_TEXT_POSITION);
  }

  /** A person holding several seats is one hit. */
  @Test
  void aPersonWithSeveralSeatsIsOneHit() {
    var chart = OrgChartFixture.chart();
    var twice =
        new de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto(
            chart.organisationsleitung(),
            chart.bereiche(),
            new de.greluc.krt.profit.basetool.frontend.leadership.model.AreaLeadershipDto(
                OrgChartFixture.node("AREA_LEAD", OrgChartFixture.ALICE, "Alice", null),
                List.of(),
                List.of(),
                List.of()),
            chart.squadrons(),
            chart.specialCommands());
    when(orgChartClient.orgChart()).thenReturn(twice);

    assertThat(contributor.results("alice", 5)).hasSize(1);
  }

  /** Name matching is tolerant like the browser's menu search, and the prefix match ranks first. */
  @Test
  void matchesTolerantlyAndRanksPrefixesFirst() {
    when(orgChartClient.orgChart()).thenReturn(OrgChartFixture.chart());

    assertThat(contributor.results("ERIC", 5))
        .extracting(QuickAccessHit::name)
        .containsExactly("Eric");
    assertThat(contributor.results("fr", 5))
        .extracting(QuickAccessHit::name)
        .containsExactly("Fred", "Gustav Freitext");
  }

  /** A chart without body finds nobody. */
  @Test
  void noBodyFindsNobody() {
    when(orgChartClient.orgChart()).thenReturn(null);

    assertThat(contributor.results("bob", 5)).isEmpty();
  }
}
