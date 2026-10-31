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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the storage tab „Mindestbestände" (REQ-INV-068): one list per unit with the figures, the
 * low and inactive-tier flags, the edit controls only on editable rows, the create dialog only for
 * a Logistician, and the live-sync wiring script.
 */
@SpringBootTest
class StockLevelTabRenderTest {

  private static final String LEVELS = "/api/v1/inventory/stock-levels";
  private static final String UNITS = LEVELS + "/units";

  private static final OrgUnitReferenceDto ARGON =
      new OrgUnitReferenceDto(
          UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
          "Spezialkommando ARGON",
          "ARG",
          "SPECIAL_COMMAND");

  private static final QualityTierDto INACTIVE_TIER =
      new QualityTierDto(
          UUID.fromString("00000000-0000-0000-0000-0000000000c3"),
          "OLD",
          500,
          "Alt (500+)",
          "Old (500+)",
          500,
          false,
          0L);

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @Autowired private SupplyFeatureState supplyFeatureState;

  private boolean supplyOn;
  private List<SupplyUnitDto> supplyUnits = List.of();

  @BeforeEach
  void resetSupply() {
    supplyOn = false;
    supplyUnits = List.of();
    supplyFeatureState.evict();
  }

  private @NotNull String render(
      @NotNull List<StockLevelDto> levels, @NotNull List<OrgUnitReferenceDto> units, String query)
      throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.get(eq(LEVELS), anyTypeRef())).thenReturn(levels);
    when(backendApiClient.get(eq(UNITS), anyTypeRef())).thenReturn(units);
    when(backendApiClient.get(eq("/api/v1/supply/state"), anyClass()))
        .thenReturn(new SupplyStateDto(supplyOn));
    when(backendApiClient.get(eq("/api/v1/supply/units"), anyTypeRef())).thenReturn(supplyUnits);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef())).thenReturn(List.of());
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get("/inventory/stock-levels" + query).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static @NotNull StockLevelDto level(
      @NotNull String material, QualityTierDto tier, double allocated, boolean low, boolean edit) {
    return new StockLevelDto(
        UUID.randomUUID(),
        3L,
        ARGON,
        new MaterialReferenceDto(UUID.randomUUID(), material, "SCU"),
        null,
        tier,
        10.0,
        20.0,
        allocated,
        20.0 - allocated,
        allocated / 10.0,
        low,
        7.0,
        edit);
  }

  private static @NotNull StockLevelDto itemLevel(@NotNull String item, double allocated) {
    return new StockLevelDto(
        UUID.randomUUID(),
        1L,
        ARGON,
        null,
        new GameItemReferenceDto(UUID.randomUUID(), item, "GENERIC"),
        null,
        4.0,
        4.0,
        allocated,
        4.0 - allocated,
        allocated / 4.0,
        allocated < 4.0,
        2.0,
        true);
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aLogisticianSeesTheUnitListWithFiguresFlagsAndControls() throws Exception {
    String html =
        render(List.of(level("Quantanium", INACTIVE_TIER, 6.0, true, true)), List.of(ARGON), "");

    assertThat(html)
        .containsPattern("<h1>Mindestbest(ä|&auml;)nde</h1>")
        .contains("data-testid=\"stock-level-unit\"")
        .contains(">Spezialkommando ARGON<")
        .contains(">Quantanium<")
        .contains("data-testid=\"stock-level-tier-inactive\"")
        .contains("data-testid=\"stock-level-low\"")
        .contains("6,000 SCU")
        .contains("60 %")
        .contains("data-testid=\"stock-level-edit\"")
        .contains("data-version=\"3\"")
        .contains("data-testid=\"stock-level-create\"")
        .contains("id=\"stock-level-modal\"")
        .contains("aria-current=\"page\"")
        .contains("/js/inventory/stock-levels.js");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aGameItemLevelShowsItsNameWholeUnitsAndTheRequestedColumn() throws Exception {
    String html =
        render(
            List.of(itemLevel("P4-AR Rifle", 1.0), level("Quantanium", null, 6.0, true, true)),
            List.of(ARGON),
            "");

    assertThat(html)
        .contains(">P4-AR Rifle<")
        .contains("data-item=\"true\"")
        .contains("data-item=\"false\"")
        .contains("data-testid=\"stock-level-requested\"")
        .contains("7,000 SCU")
        .containsPattern("data-testid=\"stock-level-requested\"[^>]*>\\s*2 ")
        .contains("id=\"stock-level-item\"")
        .contains("data-krt-combobox=\"remote-game-items\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aPoolViewerSeesTheFiguresWithoutControls() throws Exception {
    String html = render(List.of(level("Laranite", null, 12.0, false, false)), List.of(), "");

    assertThat(html)
        .contains(">Laranite<")
        .contains(">Jede Qualit")
        .doesNotContain("data-testid=\"stock-level-low\"")
        .doesNotContain("data-testid=\"stock-level-edit\"")
        .doesNotContain("data-testid=\"stock-level-create\"")
        .doesNotContain("id=\"stock-level-modal\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theResultsFragmentRendersOnlyTheListsAndAnEmptyStateWithoutLevels() throws Exception {
    String html = render(List.of(), List.of(), "?fragment=results");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("<html")
        .doesNotContain("stock-levels.js");
  }

  private static @NotNull SupplyUnitDto supplyUnit(boolean mayRaiseNeeds, boolean canManage) {
    return new SupplyUnitDto(
        ARGON.id(), ARGON.name(), "ARG", "SPECIAL_COMMAND", mayRaiseNeeds, false, canManage, 0L);
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aLowRowOffersTheOrderShortcutPrefilledWithTheShortfall() throws Exception {
    StockLevelDto low = level("Quantanium", INACTIVE_TIER, 6.0, true, true);

    String html = render(List.of(low), List.of(ARGON), "");

    assertThat(html)
        .contains("data-testid=\"stock-level-order\"")
        .contains("/orders/create?materialId=" + low.material().id())
        .contains("minQuality=500")
        .contains("amount=14.0")
        .contains("requestingOrgUnitId=" + ARGON.id())
        .doesNotContain("data-testid=\"stock-level-need\"");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void theNeedShortcutNeedsSupplyOnAndAUnitThatMayRaiseNeeds() throws Exception {
    StockLevelDto low = level("Quantanium", INACTIVE_TIER, 6.0, true, true);
    supplyOn = true;
    supplyUnits = List.of(supplyUnit(true, true));

    String html = render(List.of(low), List.of(ARGON), "");

    assertThat(html)
        .contains("data-testid=\"stock-level-need\"")
        .contains("/supply/needs?needUnit=" + ARGON.id())
        .contains("needMaterial=" + low.material().id())
        .contains("needTier=" + INACTIVE_TIER.id())
        .contains("needAmount=14.0");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void theNeedShortcutIsHiddenWhileSupplyIsOffOrTheUnitMayNotRaiseNeeds() throws Exception {
    StockLevelDto low = level("Quantanium", INACTIVE_TIER, 6.0, true, true);
    supplyUnits = List.of(supplyUnit(true, true));
    assertThat(render(List.of(low), List.of(ARGON), ""))
        .contains("data-testid=\"stock-level-order\"")
        .doesNotContain("data-testid=\"stock-level-need\"");

    supplyFeatureState.evict();
    supplyOn = true;
    supplyUnits = List.of(supplyUnit(false, true));
    assertThat(render(List.of(low), List.of(ARGON), ""))
        .doesNotContain("data-testid=\"stock-level-need\"");

    supplyFeatureState.evict();
    supplyUnits = List.of(supplyUnit(true, false));
    assertThat(render(List.of(low), List.of(ARGON), ""))
        .doesNotContain("data-testid=\"stock-level-need\"");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aRowThatIsNotLowOrNotEditableOffersNoShortcut() throws Exception {
    supplyOn = true;
    supplyUnits = List.of(supplyUnit(true, true));

    String html =
        render(
            List.of(
                level("Laranite", null, 12.0, false, true),
                level("Quantanium", INACTIVE_TIER, 6.0, true, false)),
            List.of(ARGON),
            "");

    assertThat(html)
        .doesNotContain("data-testid=\"stock-level-order\"")
        .doesNotContain("data-testid=\"stock-level-need\"");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aLogisticianOfOneUnitGetsShortcutsOnlyOnThatUnitsLowRows() throws Exception {
    OrgUnitReferenceDto bravo =
        new OrgUnitReferenceDto(
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"), "Staffel B", "B", "SQUADRON");
    StockLevelDto own = level("Quantanium", null, 6.0, true, true);
    StockLevelDto foreign =
        new StockLevelDto(
            UUID.randomUUID(),
            1L,
            bravo,
            new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU"),
            null,
            null,
            10.0,
            20.0,
            2.0,
            18.0,
            0.2,
            true,
            0.0,
            false);
    supplyOn = true;
    supplyUnits = List.of(supplyUnit(true, true));

    String html = render(List.of(own, foreign), List.of(ARGON), "");

    assertThat(html).contains("requestingOrgUnitId=" + ARGON.id());
    assertThat(html).doesNotContain("requestingOrgUnitId=" + bravo.id());
    assertThat(html).doesNotContain("needUnit=" + bravo.id());
  }
}
