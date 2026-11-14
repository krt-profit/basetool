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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationOptionDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePhaseDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePhaseState;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCount;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCountDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionMode;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroupType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test of {@link AdminPatchChangePageController} (REQ-PATCH-008, REQ-PATCH-024): the page
 * renders the form with the default kind's preset and the curated home locations, the preview, the
 * confirmation and the history with each phase for an admin only, its history fragment swaps in
 * place, a failed read leaves the rest standing, and the danger zone leads to it.
 */
@SpringBootTest
class AdminPatchChangePageControllerMvcTest {

  private static final String HISTORY = "/api/v1/patch-changes/admin";
  private static final String FORM = "/api/v1/patch-changes/admin/form";
  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a4");
  private static final UUID OLDER_ID = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a3");
  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");
  private static final UUID MATERIAL = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000b1");

  private static final HomeLocationOptionDto LORVILLE =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000001"), "Lorville", "Stanton");
  private static final HomeLocationOptionDto LEVSKI =
      new HomeLocationOptionDto(
          UUID.fromString("10c4710c-0000-4000-8000-000000000002"), "Levski", "Nyx");

  private static final PatchChangeOptions REBUILD =
      new PatchChangeOptions(Set.of(WipeGroup.CONSUMABLES), true, false, true);
  private static final PatchChangeOptions FULL_WIPE =
      new PatchChangeOptions(EnumSet.allOf(WipeGroup.class), true, false, true);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** An admin signed in through the identity provider, as the navigation needs one. */
  private static RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /**
   * Stubs the form defaults with a previous patch change, both presets and two curated home
   * locations.
   *
   * @return the stubbed defaults
   */
  private PatchChangeFormDto stubForm() {
    return stubForm(List.of(LORVILLE, LEVSKI));
  }

  /**
   * Stubs the form defaults with a previous patch change, both presets and the given curated home
   * locations.
   *
   * @param homeLocations the curated home locations
   * @return the stubbed defaults
   */
  private PatchChangeFormDto stubForm(List<HomeLocationOptionDto> homeLocations) {
    PatchChangeFormDto form =
        new PatchChangeFormDto(
            "4.5",
            "4.4",
            LIVE_AT,
            List.of(
                new PatchChangeFormDto.Preset(PatchKind.REBUILD, REBUILD),
                new PatchChangeFormDto.Preset(PatchKind.FULL_WIPE, FULL_WIPE)),
            List.of(
                new PatchChangeFormDto.Group(
                    WipeGroup.CONSUMABLES,
                    List.of(
                        new WipeGroupType(WipeGroupType.Source.ITEM_TYPE, "Food"),
                        new WipeGroupType(
                            WipeGroupType.Source.ITEM_SUB_TYPE, "WeaponAttachment:Magazine"))),
                new PatchChangeFormDto.Group(WipeGroup.OTHER_ITEMS, List.of())),
            homeLocations);
    when(backendApiClient.get(FORM, PatchChangeFormDto.class)).thenReturn(form);
    return form;
  }

  /**
   * Stubs a history of one rebuild that cancelled 1284 refinery orders and asks 87 members, 41 of
   * them relocated, above an older one whose phase it superseded.
   */
  private void stubHistory() {
    PatchChangeDto row =
        new PatchChangeDto(
            ID,
            2L,
            "4.4",
            PatchKind.REBUILD,
            LIVE_AT,
            Instant.parse("2026-11-14T21:12:00Z"),
            "Admin Jo",
            new PatchChangeOptions(
                Set.of(WipeGroup.CONSUMABLES),
                Set.of(
                    new WipeException(
                        WipeExceptionTarget.MATERIAL, MATERIAL, WipeExceptionMode.KEEP)),
                true,
                true,
                true),
            List.of(
                new WipeExceptionDto(
                    WipeExceptionTarget.MATERIAL, MATERIAL, WipeExceptionMode.KEEP, "Quantanium")),
            List.of(
                new PatchCountDto(PatchCount.REFINERY_ORDERS_CANCELED, 1284L),
                new PatchCountDto(PatchCount.MEMBERS_ASKED, 87L)),
            new PatchChangePhaseDto(PatchChangePhaseState.OPEN, 87L, 41L));
    PatchChangeDto older =
        new PatchChangeDto(
            OLDER_ID,
            0L,
            "4.3",
            PatchKind.FULL_WIPE,
            Instant.parse("2026-08-02T18:00:00Z"),
            Instant.parse("2026-08-02T18:40:00Z"),
            "Admin Jo",
            FULL_WIPE,
            List.of(),
            List.of(),
            new PatchChangePhaseDto(PatchChangePhaseState.SUPERSEDED, 80L, 76L));
    when(backendApiClient.get(eq(HISTORY), anyTypeRef())).thenReturn(List.of(row, older));
  }

  /**
   * Renders the page for an admin and returns its markup.
   *
   * @return the rendered page
   * @throws Exception if the request fails
   */
  private String renderPage() throws Exception {
    return mockMvc
        .perform(get("/admin/settings/patch-change").with(admin()))
        .andExpect(status().isOk())
        .andExpect(view().name("patchchange/admin/patch-change"))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void theFormStartsFromTheDefaultKindsPresetAndTheSuggestedLabel() throws Exception {
    PatchChangeFormDto form = stubForm();
    stubHistory();

    mockMvc
        .perform(get("/admin/settings/patch-change").with(admin()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("form", form))
        .andExpect(model().attribute("defaultKind", PatchKind.REBUILD))
        .andExpect(model().attribute("defaults", REBUILD))
        .andExpect(model().attribute("kinds", List.of(PatchKind.values())))
        .andExpect(model().attribute("wipeGroups", List.of(WipeGroup.values())))
        .andExpect(model().attributeDoesNotExist("formError", "historyError"));

    String html = renderPage();
    assertThat(html)
        .contains("id=\"pc-form\"")
        .containsPattern("name=\"kind\" value=\"REBUILD\"[^>]*checked")
        .containsPattern("name=\"kind\" value=\"FULL_WIPE\"(?![^>]*checked)[^>]*>")
        .containsPattern("value=\"FULL_WIPE\" data-wipe-groups=\"[A-Z_ ]*IN_GAME_SHIPS")
        .containsPattern("name=\"wipeGroup\" value=\"CONSUMABLES\"[^>]*checked")
        .containsPattern("name=\"wipeGroup\" value=\"FPS_EQUIPMENT\"(?![^>]*checked)[^>]*>")
        .containsPattern("name=\"cancelRefineryOrders\"[^>]*checked")
        .containsPattern("name=\"currencyWipe\"(?![^>]*checked)[^>]*>")
        .contains("id=\"pc-label\"")
        .contains("value=\"4.5\"")
        .contains("data-confirm-token=\"4.5\"")
        .contains("Patchwechsel 4.5 auslösen")
        .contains("Zum Bestätigen die Patch-Bezeichnung 4.5 eintippen")
        .contains("Letzter Patchwechsel: 4.4, live seit 14.11.2026 20:00 UTC.")
        .contains("data-previous-live-at=\"2026-11-14T20:00:00Z\"")
        .containsPattern("id=\"pc-trigger\"[^>]*disabled")
        .containsPattern("id=\"pc-currency-alert\"[^>]*hidden")
        .contains("data-count-kind=\"REFINERY_ORDERS_CANCELED\"")
        .contains("Raffinerieaufträge storniert")
        .contains("/js/patchchange/admin-patch-change")
        .doesNotContain("??admin.patchChange");
  }

  @Test
  void theWipeSectionShowsEachGroupsTypesTheCountCellsAndTheExceptionEditor() throws Exception {
    stubForm();
    stubHistory();

    mockMvc
        .perform(get("/admin/settings/patch-change").with(admin()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("exceptionTargets", List.of(WipeExceptionTarget.values())))
        .andExpect(model().attribute("exceptionModes", List.of(WipeExceptionMode.values())))
        .andExpect(model().attribute("exceptionLimit", PatchChangeOptions.MAX_WIPE_EXCEPTIONS));

    assertThat(renderPage())
        .contains("data-pc-group=\"CONSUMABLES\"")
        .contains("Katalogtypen")
        .contains(">Typ Food<")
        .contains(">Typ:Untertyp WeaponAttachment:Magazine<")
        .contains("data-pc-group-rows")
        .contains("data-pc-group-members")
        .contains("data-pc-exceptions")
        .contains("data-limit=\"100\"")
        .contains("id=\"pc-exception-target\"")
        .containsPattern("id=\"pc-exception-item\"[^>]*data-krt-combobox=\"remote-game-items\"")
        .containsPattern("id=\"pc-exception-material\"[^>]*data-krt-combobox=\"remote-materials\"")
        .contains("zusätzlich löschen")
        .contains("Ausnahme hinzufügen")
        .contains("data-count-kind=\"STOCK_ROWS_WIPED\"")
        .contains("Lagerposten gelöscht")
        .doesNotContain("??admin.patchChange");
  }

  @Test
  void thePageSitsUnderTheDangerZoneTabAndJoinsThePatchChangeRoom() throws Exception {
    stubForm();
    stubHistory();

    assertThat(renderPage())
        .contains("data-testid=\"page-tabs\"")
        .containsPattern(
            "class=\"tab active\"[^>]*data-entry-key=\"admin.dangerzone\""
                + "|data-entry-key=\"admin.dangerzone\"[^>]*class=\"tab active\"")
        .containsPattern(
            "name=\"krt-live-sync-sections\"\\s+content=\"history\""
                + " data-topic-class=\"PATCH_CHANGE\"");
  }

  @Test
  void theHistoryShowsEachPatchChangeWithItsCountsOptionsAndCorrection() throws Exception {
    stubForm();
    stubHistory();

    assertThat(renderPage())
        .contains("id=\"pc-history-host\"")
        .contains("id=\"pc-row-" + ID + "\"")
        .contains("data-version=\"2\"")
        .contains("data-label=\"4.4\"")
        .contains("LTP-Rebuild")
        .contains("14.11.2026 21:12 UTC")
        .contains("Admin Jo")
        .contains("Raffinerieaufträge storniert: 1.284")
        .contains(">Verbrauchsgüter<")
        .contains("Währungs-Wipe")
        .contains("Quantanium: behalten")
        .contains("data-pc-correct")
        .contains("Bezeichnung korrigieren")
        .contains("id=\"pc-correct-modal\"")
        .contains("id=\"pc-correct-label\"");
  }

  @Test
  void theHistoryShowsEachPhaseAndOffersToEndOnlyTheOpenOne() throws Exception {
    stubForm();
    stubHistory();

    String html = renderPage();

    assertThat(html)
        .contains(">Abfrage<")
        .contains("class=\"chip chip--warning\" data-testid=\"pc-phase\"")
        .contains("data-phase-state=\"OPEN\">Offen · 41 / 87 umgezogen<")
        .contains("class=\"chip chip--muted\" data-testid=\"pc-phase\"")
        .contains("data-phase-state=\"SUPERSEDED\">Abgelöst<")
        .contains("Mitglieder werden gefragt: 87")
        .contains("data-count-kind=\"MEMBERS_ASKED\"")
        .containsPattern("class=\"btn btn-quiet-danger btn-xs\" data-pc-phase-end")
        .contains("Abfrage beenden")
        .contains("id=\"pc-phase-end-modal\"")
        .contains("krt-modal--danger")
        .contains("id=\"pc-phase-end-submit\"")
        .contains("data-phase-end-text=\"Die Home-Location-Abfrage von Patch {0} endet.");
    assertThat(html.split("data-pc-phase-end", -1))
        .as("only the open phase offers its end")
        .hasSize(2);
  }

  @Test
  void theFormNamesTheCuratedHomeLocationsAndLinksToTheirCuration() throws Exception {
    stubForm();
    stubHistory();

    assertThat(renderPage())
        .contains("Wählbare Home Locations: Lorville, Levski")
        .contains("data-count=\"2\"")
        .containsPattern("href=\"/admin/locations\"[^>]*data-testid=\"pc-home-locations-curate\"")
        .contains("Home Locations pflegen")
        .containsPattern("id=\"pc-home-alert\"[^>]*hidden")
        .contains("data-home-locations=\"Wählbare Home Locations: {0}\"");
  }

  @Test
  void withoutACuratedHomeLocationTheFormWarnsWhileThePromptIsOn() throws Exception {
    stubForm(List.of());
    stubHistory();

    assertThat(renderPage())
        .contains("Wählbare Home Locations: keine")
        .contains("data-count=\"0\"")
        .containsPattern("id=\"pc-home-alert\"(?![^>]*hidden)[^>]*>")
        .contains("Es ist keine Home Location gepflegt.");
  }

  @Test
  void theHistoryFragmentRendersOnlyTheHistoryWithoutReadingTheForm() throws Exception {
    stubHistory();

    String html =
        mockMvc
            .perform(get("/admin/settings/patch-change").param("fragment", "history").with(admin()))
            .andExpect(status().isOk())
            .andExpect(view().name("patchchange/admin/patch-change :: history"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("id=\"pc-history-table\"")
        .doesNotContain("id=\"pc-history-host\"")
        .doesNotContain("id=\"pc-form\"");
    verify(backendApiClient, never()).get(eq(FORM), eq(PatchChangeFormDto.class));
  }

  @Test
  void beforeTheFirstPatchChangeTheHistoryIsEmptyAndTheHintHidden() throws Exception {
    when(backendApiClient.get(FORM, PatchChangeFormDto.class))
        .thenReturn(
            new PatchChangeFormDto(
                null,
                null,
                null,
                List.of(new PatchChangeFormDto.Preset(PatchKind.REBUILD, REBUILD)),
                List.of(),
                List.of()));
    when(backendApiClient.get(eq(HISTORY), anyTypeRef())).thenReturn(List.of());

    assertThat(renderPage())
        .contains("id=\"pc-history-empty\"")
        .doesNotContain("id=\"pc-history-table\"")
        .containsPattern("id=\"pc-previous\"[^>]*hidden")
        .doesNotContain("data-previous-label");
  }

  @Test
  void aFormThatCannotBeReadLeavesTheHistoryStanding() throws Exception {
    when(backendApiClient.get(FORM, PatchChangeFormDto.class))
        .thenThrow(new BackendServiceException("backend down", null, 503));
    stubHistory();

    mockMvc
        .perform(get("/admin/settings/patch-change").with(admin()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("formError", AdminPatchChangePageController.FORM_ERROR))
        .andExpect(model().attributeDoesNotExist("form"));
    assertThat(renderPage())
        .contains("data-testid=\"pc-form-error\"")
        .contains("Das Formular konnte nicht geladen werden.")
        .doesNotContain("id=\"pc-form\"")
        .contains("id=\"pc-history-table\"");
  }

  @Test
  void aHistoryThatCannotBeReadLeavesTheFormStanding() throws Exception {
    stubForm();
    when(backendApiClient.get(eq(HISTORY), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    mockMvc
        .perform(get("/admin/settings/patch-change").with(admin()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("historyError", AdminPatchChangePageController.HISTORY_ERROR))
        .andExpect(model().attribute("history", List.of()));
    assertThat(renderPage())
        .contains("data-testid=\"pc-history-error\"")
        .contains("id=\"pc-form\"")
        .doesNotContain("id=\"pc-history-empty\"");
  }

  @Test
  void theDangerZoneLeadsToThePage() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/settings/danger-zone").with(admin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"patch-change-card\"")
        .containsPattern(
            "href=\"/admin/settings/patch-change\"[^>]*data-testid=\"patch-change-open\"")
        .contains("Patchwechsel erfassen");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberIsRefused() throws Exception {
    mockMvc.perform(get("/admin/settings/patch-change")).andExpect(status().isForbidden());
    verify(backendApiClient, never()).get(eq(HISTORY), anyTypeRef());
  }
}
