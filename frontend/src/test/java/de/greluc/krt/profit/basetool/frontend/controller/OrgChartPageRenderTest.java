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

import de.greluc.krt.profit.basetool.frontend.model.dto.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CommandChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OlChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartNodeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronChartDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full Thymeleaf render tests for the org-chart page. Renders {@code org-chart.html} together with
 * the {@code ocNode} person-node fragment through the real template engine, so a broken node
 * expression fails the build instead of only surfacing as a 500 at runtime (the pure-method {@link
 * OrgChartPageControllerTest} never touches the template).
 *
 * <p>Pins the empty-chart path: an unfilled Bereichsleiter or Staffelleiter seat must render its
 * vacant placeholder rather than invoking {@code ocNode} with a {@code null} node. That null-node
 * invocation is exactly what threw {@code EL1011E: ... positionType() on null context object} when
 * the {@code th:if} guard sat on the same element as {@code th:replace} (fragment inclusion runs
 * before the conditional, so the guard was dead code).
 */
@SpringBootTest
class OrgChartPageRenderTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyChart_member_rendersVacantSeatInsteadOfInvokingNodeFragmentWithNull() throws Exception {
    // Given: a completely empty chart — the Bereichsleiter seat (area.lead) is null. Pre-fix this
    // rendered ocNode(null) and NPE'd; now the vacant placeholder must render and the page 200s.
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(),
                List.of()));

    // When / Then
    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("chart root rendered").contains("oc-chart");
    assertThat(html)
        .as("vacant Bereichsleiter placeholder, not an NPE")
        .contains("oc-node--vacant");
    assertThat(html).as("chart exposes an ARIA tree").contains("role=\"tree\"");
    assertThat(html).as("vacant Bereichsleiter is a level-1 treeitem").contains("aria-level=\"1\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void unledSquadron_admin_rendersVacantSquadronLeadAndEditorAffordance() throws Exception {
    // Given: a profit-eligible Staffel with no Staffelleiter (sq.lead == null) — the second
    // same-element guard that previously NPE'd. The admin view additionally renders the inline
    // editor's add affordances (sec:authorize ADMIN).
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(
                    new SquadronChartDto(
                        UUID.randomUUID(),
                        "IRIDIUM",
                        "IRI",
                        null,
                        List.of(),
                        List.of(),
                        true,
                        true)),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    // When / Then
    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("Staffel column rendered").contains("IRIDIUM");
    assertThat(html).as("vacant Staffelleiter placeholder, not an NPE").contains("oc-node--vacant");
    assertThat(html)
        .as("admin assign-Staffelleiter affordance")
        .contains("data-position-type=\"SQUADRON_LEAD\"");
    assertThat(html).as("child rows are ARIA groups").contains("role=\"group\"");
    assertThat(html).as("boxes are ARIA treeitems").contains("role=\"treeitem\"");
    assertThat(html).as("unit box sits at level 2").contains("aria-level=\"2\"");
    assertThat(html).as("vacant Staffelleiter sits at level 3").contains("aria-level=\"3\"");
    assertThat(html).as("edit toggle exposes its pressed state").contains("aria-pressed=\"false\"");
    assertThat(html).as("edit-mode hint rendered (hidden until editing)").contains("oc-edit-hint");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void namedLeaderlessCommand_admin_rendersGroupHeaderVacantLeadAndChildren() throws Exception {
    // Given: a named Kommando with a vacant Kommandoleiter but an already-attached Stv. + Ensign —
    // the exact shape the decoupled command-group model introduces. Renders the group header
    // (name), the vacant-leader placeholder + assign affordance, and the child person-nodes via
    // ocNode. A broken expression in any of those new paths fails here instead of as a runtime 500.
    OrgChartNodeDto deputy =
        new OrgChartNodeDto(
            UUID.randomUUID(), "DEPUTY_COMMAND_LEAD", UUID.randomUUID(), "Deputy", null, 0, 0L);
    OrgChartNodeDto ensign =
        new OrgChartNodeDto(UUID.randomUUID(), "ENSIGN", UUID.randomUUID(), "Ensign", null, 0, 0L);
    CommandChartDto command =
        new CommandChartDto(
            UUID.randomUUID(), "Alpha", 0L, 0, null, null, null, null, deputy, List.of(ensign));
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(
                    new SquadronChartDto(
                        UUID.randomUUID(),
                        "IRIDIUM",
                        "IRI",
                        null,
                        List.of(command),
                        List.of(),
                        true,
                        true)),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("Kommando group header").contains("oc-command-head");
    assertThat(html).as("Kommando name rendered").contains("Alpha");
    assertThat(html).as("admin rename affordance").contains("data-trigger=\"oc-rename\"");
    assertThat(html).as("vacant Kommandoleiter placeholder").contains("oc-node--vacant");
    assertThat(html).as("child Stv. node rendered via ocNode").contains("Deputy");
    assertThat(html).as("child Ensign node rendered via ocNode").contains("Ensign");
    assertThat(html).as("command head is a level-4 treeitem").contains("aria-level=\"4\"");
    assertThat(html).as("vacant Kommandoleiter sits at level 5").contains("aria-level=\"5\"");
    assertThat(html).as("child Stv./Ensign nodes sit at level 6").contains("aria-level=\"6\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void groupLinkedCommand_admin_rendersReadOnlyHeadWithNoEditAffordances() throws Exception {
    // Given: a Kommando that mirrors a kommando_group (kommandoGroupId set, epic #800 /
    // REQ-ROLE-006) with its Kommandoleiter appointed under Organisation -> Leitung (an account).
    // The whole Kommando subtree is Leitung-managed, so the chart renders it read-only: the head
    // carries the "managed under Leitung" marker and NONE of the rename / remove / vacate /
    // add-child controls render, even for an admin — those would 400 at the backend.
    CommandChartDto command =
        new CommandChartDto(
            UUID.randomUUID(),
            "Alpha",
            0L,
            0,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Cmd",
            null,
            null,
            List.of());
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(
                    new SquadronChartDto(
                        UUID.randomUUID(),
                        "IRIDIUM",
                        "IRI",
                        null,
                        List.of(command),
                        List.of(),
                        false,
                        false)),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("the Kommando still renders").contains("oc-command-head");
    assertThat(html).as("the Kommando name renders").contains("Alpha");
    assertThat(html).as("the appointed Kommandoleiter renders").contains("Cmd");
    assertThat(html)
        .as("the head carries the managed-in-Leitung marker")
        .contains("oc-node-managed");
    assertThat(html)
        .as("no rename control on a group-linked Kommando head")
        .doesNotContain("data-trigger=\"oc-rename\"");
    assertThat(html)
        .as("no remove control on a group-linked Kommando head")
        .doesNotContain("data-trigger=\"oc-remove\"");
    assertThat(html)
        .as("no vacate control on a Leitung-managed Kommandoleiter")
        .doesNotContain("data-trigger=\"oc-vacate\"");
    assertThat(html)
        .as("no add-Stv. affordance on a group-linked Kommando")
        .doesNotContain("data-position-type=\"DEPUTY_COMMAND_LEAD\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void chartBodyFragment_rendersTreeWithoutPageChrome() throws Exception {
    // The in-place chart refresh (epic #571 / REQ-FE-005) swaps ?fragment=chartBody into the stable
    // #oc-chart container. The fragment must render the tree + its add affordances but NOT the page
    // chrome that lives outside the swap region (the edit toolbar and the assign modal) — otherwise
    // an innerHTML swap would inject a duplicate toolbar/modal into the chart.
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(
                    new SquadronChartDto(
                        UUID.randomUUID(),
                        "IRIDIUM",
                        "IRI",
                        null,
                        List.of(),
                        List.of(),
                        true,
                        true)),
                List.of()));

    String html =
        mockMvc
            .perform(get("/org-chart").param("fragment", "chartBody"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("fragment renders the tree").contains("role=\"tree\"");
    assertThat(html).as("fragment renders the Staffel column").contains("IRIDIUM");
    assertThat(html)
        .as("fragment renders the in-tree add affordance")
        .contains("data-position-type=\"SQUADRON_LEAD\"");
    assertThat(html)
        .as("edit toolbar lives OUTSIDE the fragment")
        .doesNotContain("data-trigger=\"oc-toggle-edit\"");
    assertThat(html)
        .as("assign modal lives OUTSIDE the fragment")
        .doesNotContain("id=\"oc-modal\"");
    assertThat(html)
        .as("fragment is chrome-free — no page header brand")
        .doesNotContain("class=\"brand\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void olTier_admin_rendersRootMembersAndAddAffordance() throws Exception {
    // Given: an Organisationsleitung tier with one member (epic #692 / REQ-ORG-018). The OL renders
    // as its own ARIA tree — the OL root box (level 1) with its OL_MEMBER nodes (level 2) — plus
    // the
    // admin add-OL-member affordance, which needs the OL's org-unit id carried by OlChartDto.
    UUID olId = UUID.randomUUID();
    OrgChartNodeDto member =
        new OrgChartNodeDto(
            UUID.randomUUID(), "OL_MEMBER", UUID.randomUUID(), "Chief", null, 0, 0L);
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                new OlChartDto(olId, "Organisationsleitung", "OL", List.of(member)),
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("OL root box rendered").contains("oc-unit-box--ol");
    assertThat(html).as("OL member rendered via ocNode").contains("Chief");
    assertThat(html).as("OL root is a level-1 treeitem").contains("aria-level=\"1\"");
    assertThat(html)
        .as("admin add-OL-member affordance")
        .contains("data-position-type=\"OL_MEMBER\"");
    assertThat(html)
        .as("legacy area tier hidden when an OL exists")
        .doesNotContain("data-trigger=\"oc-add-staff\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void freeTextHolder_admin_rendersTypedNameAndNoAccountMarker_notVacant() throws Exception {
    // Given: an OL member named on the chart who has no Basetool account yet (REQ-ORG-020) — userId
    // null but a free-text displayName. The node must render the typed name through ocNode with the
    // free-text marker class (not the dashed vacant placeholder), and the reassign control must
    // carry the typed name so an admin can later swap it for an account without losing it.
    UUID olId = UUID.randomUUID();
    OrgChartNodeDto freeTextMember =
        new OrgChartNodeDto(UUID.randomUUID(), "OL_MEMBER", null, null, "Max Mustermann", 0, 0L);
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                new OlChartDto(olId, "Organisationsleitung", "OL", List.of(freeTextMember)),
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("the free-text holder's typed name renders").contains("Max Mustermann");
    assertThat(html)
        .as("free-text node carries the no-account marker class, not the vacant style")
        .contains("oc-node--freetext");
    assertThat(html).as("the no-account badge element renders").contains("oc-node-flag");
    assertThat(html)
        .as("the reassign control carries the typed name for the account swap")
        .contains("data-display-name=\"Max Mustermann\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void freeTextCommandLeader_admin_rendersTypedNameAndNoAccountMarker_notVacant() throws Exception {
    // Given: a Kommando whose Kommandoleiter is a Kartell member with no Basetool account yet
    // (REQ-ORG-020) — leaderUserId null but a free-text leaderDisplayName. Exercises the inline
    // command-leader branch (cmd.leaderDisplayName != null), distinct from the ocNode path above:
    // the leader node renders the typed name with the no-account marker (not the dashed vacant
    // placeholder), and its reassign control carries the typed name for a later account swap.
    CommandChartDto command =
        new CommandChartDto(
            UUID.randomUUID(), "Alpha", 0L, 0, null, null, null, "Max Mustermann", null, List.of());
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(
                    new SquadronChartDto(
                        UUID.randomUUID(),
                        "IRIDIUM",
                        "IRI",
                        null,
                        List.of(command),
                        List.of(),
                        true,
                        true)),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the free-text Kommandoleiter's typed name renders")
        .contains("Max Mustermann");
    assertThat(html)
        .as("free-text leader node carries the no-account marker class")
        .contains("oc-node--freetext");
    assertThat(html).as("the no-account badge element renders").contains("oc-node-flag");
    assertThat(html)
        .as("the leader reassign control carries the typed name for the account swap")
        .contains("data-display-name=\"Max Mustermann\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void bereichTier_admin_rendersDepartmentTintLeadershipAndUnits() throws Exception {
    // Given: one Bereich tier carrying the PROFIT Bereichsfarbe, a Bereichsleiter (hero) and one
    // Staffel (epic #692 / REQ-ORG-018). The tier renders as its own ARIA tree, tinted via the
    // oc-dept--profit class, with the Staffel fanning out through the shared ocUnitFan fragment.
    // The
    // legacy area tier must stay hidden once a Bereich is populated.
    OrgChartNodeDto lead =
        new OrgChartNodeDto(
            UUID.randomUUID(), "BEREICHSLEITER", UUID.randomUUID(), "Area Boss", null, 0, 0L);
    SquadronChartDto sq =
        new SquadronChartDto(
            UUID.randomUUID(), "IRIDIUM", "IRI", null, List.of(), List.of(), true, true);
    BereichChartDto bereich =
        new BereichChartDto(
            UUID.randomUUID(),
            "Profit-Bereich",
            "PRF",
            "PROFIT",
            new AreaLeadershipDto(lead, List.of(), List.of(), List.of()),
            List.of(sq),
            List.of());
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                null,
                List.of(bereich),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("Bereich tier rendered").contains("oc-bereich-tier");
    assertThat(html).as("Bereich department tint class").contains("oc-dept--profit");
    assertThat(html).as("Bereich caption swatch rendered").contains("oc-dept-swatch");
    assertThat(html).as("Bereich caption shows its name").contains("Profit-Bereich");
    assertThat(html).as("Bereichsleiter renders as a hero node").contains("oc-node--hero");
    assertThat(html).as("Bereichsleiter holder rendered").contains("Area Boss");
    assertThat(html).as("Bereich's Staffel rendered via ocUnitFan").contains("IRIDIUM");
    assertThat(html)
        .as("Bereich carries a collapse toggle")
        .contains("data-trigger=\"oc-collapse\"");
    assertThat(html).as("Bereich body is collapsible").contains("oc-bereich-body");
    assertThat(html)
        .as("legacy area tier hidden when a Bereich is populated")
        .doesNotContain("data-trigger=\"oc-add-staff\"");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void olWithBereiche_admin_rendersConnectorFanSideBySide() throws Exception {
    // Given: an OL plus two Bereiche. The Bereiche must fan out side by side beneath the OL via the
    // oc-fan--bereiche connector (epic #692) — each its own collapsible Bereich subtree.
    UUID olId = UUID.randomUUID();
    OrgChartNodeDto leadA =
        new OrgChartNodeDto(
            UUID.randomUUID(), "BEREICHSLEITER", UUID.randomUUID(), "Boss A", null, 0, 0L);
    OrgChartNodeDto leadB =
        new OrgChartNodeDto(
            UUID.randomUUID(), "BEREICHSLEITER", UUID.randomUUID(), "Boss B", null, 0, 0L);
    BereichChartDto bereichA =
        new BereichChartDto(
            UUID.randomUUID(),
            "Profit-Bereich",
            "PRF",
            "PROFIT",
            new AreaLeadershipDto(leadA, List.of(), List.of(), List.of()),
            List.of(),
            List.of());
    BereichChartDto bereichB =
        new BereichChartDto(
            UUID.randomUUID(),
            "Sub-Radar-Bereich",
            "SUB",
            "SUB_RADAR",
            new AreaLeadershipDto(leadB, List.of(), List.of(), List.of()),
            List.of(),
            List.of());
    when(backendApiClient.get("/api/v1/org-chart", OrgChartDto.class))
        .thenReturn(
            new OrgChartDto(
                new OlChartDto(olId, "Organisationsleitung", "OL", List.of()),
                List.of(bereichA, bereichB),
                new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
                List.of(),
                List.of()));
    when(backendApiClient.get(eq("/api/v1/users/lookup"), anyTypeRef()))
        .thenReturn(List.of(Map.of("id", UUID.randomUUID().toString(), "effectiveName", "Pilot")));

    String html =
        mockMvc
            .perform(get("/org-chart"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("OL apex rendered").contains("oc-unit-box--ol");
    assertThat(html).as("OL → Bereiche connector fan rendered").contains("oc-fan--bereiche");
    assertThat(html).as("first Bereich rendered side by side").contains("Profit-Bereich");
    assertThat(html).as("second Bereich rendered side by side").contains("Sub-Radar-Bereich");
    assertThat(html)
        .as("each Bereich carries a collapse toggle")
        .contains("data-trigger=\"oc-collapse\"");
  }
}
