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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungMemberDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronChartDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render test for the Leitung page on the master-detail pattern (REQ-UI-027, REQ-ROLE-004): page
 * head, unit tree with department squares and counts, the {@code ?unit=} / {@code ?tab=} deep link,
 * one primary action per unit pane, rank and group selects that save on change, and the
 * Spezialkommando roster with its role flags.
 */
@SpringBootTest
class LeitungPagePatternRenderTest {

  private static final UUID OL = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID BEREICH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  private static final UUID SQUADRON = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID SK = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID LEAD = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID PILOT = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final UUID GROUP = UUID.fromString("00000000-0000-0000-0000-000000000021");

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /**
   * Renders the page in German.
   *
   * @param request the request to perform
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(request.locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** Stubs one unit of each kind, the org chart above them and the SK's role flags. */
  private void stubFullView() {
    LeitungUnitDto ol =
        new LeitungUnitDto(
            OL,
            "OL",
            "OL",
            OrgUnitKind.ORGANISATIONSLEITUNG,
            true,
            true,
            List.of(new LeitungMemberDto(LEAD, "Lead", "OL_MEMBER", null, 0L, true)),
            List.of(),
            LEAD);
    LeitungUnitDto bereich =
        new LeitungUnitDto(
            BEREICH,
            "Logistik",
            "LOG",
            OrgUnitKind.BEREICH,
            true,
            true,
            List.of(new LeitungMemberDto(PILOT, "Pilot", "BEREICHSKOORDINATOR", null, 0L, false)),
            List.of(),
            null);
    LeitungUnitDto squadron =
        new LeitungUnitDto(
            SQUADRON,
            "IRIDIUM",
            "IRI",
            OrgUnitKind.SQUADRON,
            true,
            true,
            List.of(
                new LeitungMemberDto(LEAD, "Lead", "STAFFELLEITER", null, 4L, true),
                new LeitungMemberDto(PILOT, "Pilot", "ENSIGN", GROUP, 2L, false)),
            List.of(new KommandoGroupDto(GROUP, SQUADRON, "Alpha", 0, 1L)),
            null);
    LeitungUnitDto sk =
        new LeitungUnitDto(
            SK,
            "Bergung",
            "BRG",
            OrgUnitKind.SPECIAL_COMMAND,
            true,
            true,
            List.of(new LeitungMemberDto(PILOT, "Pilot", "MEMBER", null, 3L, false)),
            List.of(),
            null);
    when(backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(
            new LeitungViewDto(
                true, List.of(ol), List.of(bereich), List.of(squadron), List.of(sk)));
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(
                    new BereichChartDto(
                        BEREICH,
                        "Logistik",
                        "LOG",
                        "FORSCHUNG",
                        null,
                        List.of(
                            new SquadronChartDto(
                                SQUADRON, "IRIDIUM", "IRI", null, List.of(), List.of(), false,
                                false)),
                        List.of(
                            new SpecialCommandChartDto(SK, "Bergung", "BRG", List.of(), false)))),
                null,
                List.of(),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/special-commands/{id}/members"), anyTypeRef(), eq(SK)))
        .thenReturn(
            List.of(
                Map.of(
                    "userId",
                    PILOT.toString(),
                    "isLogistician",
                    true,
                    "isMissionManager",
                    false,
                    "version",
                    3)));
  }

  /**
   * The markup of one unit pane, from its opening tag to its closing {@code </section>}.
   *
   * @param html the rendered page
   * @param unitId the unit
   * @return the pane's markup
   */
  private static @NotNull String pane(@NotNull String html, @NotNull UUID unitId) {
    int start = html.indexOf("id=\"lt-pane-" + unitId + "\"");
    int open = html.lastIndexOf("<section", start);
    int end = html.indexOf("</section>", start);
    assertThat(open).isNotNegative();
    assertThat(end).isGreaterThan(open);
    return html.substring(open, end);
  }

  /** The page renders head, tree and the deep-linked Staffel pane with its groups tab open. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTreeAndTheDeepLinkedUnit() throws Exception {
    stubFullView();

    String html =
        render(
            get("/organisation/leitung").param("unit", SQUADRON.toString()).param("tab", "groups"));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Organisation<")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting")
        .contains("data-testid=\"leitung-md\"")
        .contains("id=\"leitung-tree-search\"");
    assertThat(html.substring(html.indexOf("<main"), html.indexOf("</main>")))
        .doesNotContainPattern("class=\"[^\"]*krtm-")
        .doesNotContain("colspan");
    assertThat(html.split("data-testid=\"leitung-tree-row\"", -1)).hasSize(5);
    assertThat(html)
        .containsPattern(
            "class=\"master-row leitung-tree__row"
                + " is-active\"\\s+href=\"/organisation/leitung\\?unit="
                + SQUADRON
                + "\"")
        .contains("leitung-dept leitung-dept--ol")
        .contains("leitung-dept leitung-dept--forschung");

    String squadronPane = pane(html, SQUADRON);
    assertThat(squadronPane.substring(0, squadronPane.indexOf('>'))).doesNotContain("hidden");
    assertThat(squadronPane)
        .contains("Staffel · Logistik")
        .containsPattern("data-lt-panel=\"members\"[^>]*hidden")
        .doesNotContainPattern("data-lt-panel=\"groups\" hidden")
        .contains("class=\"data-table data-table--stack unit-roster leitung-rank-table\"")
        .contains("class=\"leitung-group-select\"")
        .contains("class=\"leitung-group-name\"")
        .contains("data-leitung-action=\"create-group\"")
        .contains("data-leitung-action=\"clear-rank\"")
        .doesNotContain("save-rank")
        .doesNotContain("btn--cta");

    String olPane = pane(html, OL);
    assertThat(olPane.substring(0, olPane.indexOf('>'))).contains("hidden");
    assertThat(olPane.split("btn--cta", -1)).hasSize(2);
    assertThat(olPane)
        .contains("data-leitung-open=\"add-ol\"")
        .containsPattern("class=\"chip chip--primary\">Grand Admiral<")
        .contains("data-leitung-action=\"remove-grand-admiral\"")
        .contains("overflow-menu__item overflow-menu__item--danger");

    String bereichPane = pane(html, BEREICH);
    assertThat(bereichPane.split("btn--cta", -1)).hasSize(2);
    assertThat(bereichPane).contains("Bereich · Forschung").contains("remove-bereich-role");

    String skPane = pane(html, SK);
    assertThat(skPane.split("btn--cta", -1)).hasSize(2);
    assertThat(skPane)
        .contains("Spezialkommando · Logistik")
        .contains("data-leitung-open=\"add-sk\"")
        .contains("data-sk-roster")
        .containsPattern("data-sk-flag=\"isLogistician\"\\s+aria-pressed=\"true\"")
        .containsPattern("data-sk-flag=\"isMissionManager\"\\s+aria-pressed=\"false\"")
        .contains("class=\"matrix-flag on\"")
        .contains("data-sk-action=\"toggle-lead\"")
        .contains(
            "data-lead-url=\"/organisation/leitung/special-commands/"
                + SK
                + "/members/"
                + PILOT
                + "/lead/ajax\"");
  }

  /** Without a deep link the first unit in tree order is shown. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void selectsTheFirstUnitWithoutDeepLink() throws Exception {
    stubFullView();

    String html = render(get("/organisation/leitung"));

    String olPane = pane(html, OL);
    assertThat(olPane.substring(0, olPane.indexOf('>'))).doesNotContain("hidden");
    String squadronPane = pane(html, SQUADRON);
    assertThat(squadronPane.substring(0, squadronPane.indexOf('>'))).contains("hidden");
  }

  /** A caller who manages no unit sees the empty state instead of the tree. */
  @Test
  @WithMockUser(roles = "OFFICER")
  void rendersTheEmptyState() throws Exception {
    when(backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class))
        .thenReturn(new LeitungViewDto(false, List.of(), List.of(), List.of(), List.of()));

    String html = render(get("/organisation/leitung"));

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("data-testid=\"leitung-md\"")
        .contains("id=\"leitung-modal\"");
  }
}
