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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.SalePriceBasis;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render coverage of the estimated sale value in the "Mein Lager" material tree (REQ-INV-072): the
 * dimmed value line on a priced group row with a tooltip that names the profile's price basis, the
 * sum row over the shown groups with its basis hint, notes and filter label, no sum row without a
 * valued group, and the row of the marked entries the page swaps in.
 */
@SpringBootTest
class InventorySaleValueRenderTest {

  private static final String VALUE_LINE = "data-testid=\"lager-sale-value\"";
  private static final String SUM_ROW = "data-testid=\"lager-sale-value-total\"";
  private static final String SELECTION_SLOT = "data-lager-sale-value-slot";
  private static final String SELECTION_SALE_VALUE = "/api/v1/inventory/my-inventory/sale-value";
  private static final String TERMINAL = "ARC-L1 Wide Forest Station";
  private static final String SWITCH_FROM_AVERAGE =
      "Im Profil kannst du auf Best- oder Minimalpreis umstellen.";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void averageValuesShowTheirTerminalCountAndTheSumRowTotalsTheShownGroups() throws Exception {
    stubMyGroups(
        group("Agricium", "SCU", 120.25, average("2700", 3, "324675", 0.0)),
        group("Hadanit", "PIECE", 14.0, null),
        group("Titan", "SCU", 210.0, average("820", 1, "172200", 0.0)));

    String html = render(myLager());

    assertThat(StringUtils.countOccurrencesOf(html, VALUE_LINE)).isEqualTo(2);
    assertThat(html)
        .contains("324.675", "172.200", "≈ 496.875")
        .contains(
            "title=\"Durchschnittlicher Verkaufspreis 2.700 aUEC/SCU über 3 Terminals. "
                + SWITCH_FROM_AVERAGE
                + "\"")
        .contains("Durchschnittlicher Verkaufspreis 820 aUEC/SCU über ein Terminal.")
        .contains(SUM_ROW, "Geschätzter Verkaufswert", "1 Material ohne Terminalpreis")
        .contains("Menge × durchschnittlicher aktueller Verkaufspreis je Material")
        .doesNotContain("(gefiltert)", "Gestohlene Bestände nicht bewertet");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theBestPriceNamesItsTerminalAndTheProfileAlternatives() throws Exception {
    stubMyGroups(
        group(
            "Agricium",
            "SCU",
            10.0,
            new InventorySaleValueDto(
                SalePriceBasis.HIGHEST,
                new BigDecimal("2700"),
                TERMINAL,
                3,
                new BigDecimal("27000"),
                0.0)));

    String html = render(myLager());

    assertThat(html)
        .contains(
            "title=\"Bester Verkaufspreis 2.700 aUEC/SCU bei "
                + TERMINAL
                + ". Im Profil kannst du auf Durchschnitts- oder Minimalpreis umstellen.\"")
        .contains("Menge × höchster aktueller Verkaufspreis je Material");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void stolenStockIsNamedInTheTooltipAndInTheSumRow() throws Exception {
    stubMyGroups(group("Agricium", "SCU", 120.25, average("2700", 3, "216000", 40.25)));

    String html = render(myLager());

    assertThat(html)
        .contains(
            SWITCH_FROM_AVERAGE + " 40,250 SCU gestohlene Ware sind nicht bewertet.\"",
            "Gestohlene Bestände nicht bewertet")
        .doesNotContain("ohne Terminalpreis");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void anActiveFilterMarksTheSumRowAsFiltered() throws Exception {
    stubMyGroups(group("Agricium", "SCU", 10.0, average("2700", 3, "27000", 0.0)));

    String html = render(myLager().param("materialIds", UUID.randomUUID().toString()));

    assertThat(html).contains(SUM_ROW, "Geschätzter Verkaufswert (gefiltert)");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void withoutAValuedGroupThereIsNoValueAndNoSumRow() throws Exception {
    stubMyGroups(
        group("Hadanit", "PIECE", 14.0, null),
        group("Agricium", "SCU", 5.0, average("2700", 3, "0", 5.0)));

    String html = render(myLager());

    assertThat(html).doesNotContain(VALUE_LINE, SUM_ROW, SELECTION_SLOT);
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theSumRowComesWithAHiddenSlotForTheSelectionsRow() throws Exception {
    stubMyGroups(group("Agricium", "SCU", 10.0, average("2700", 3, "27000", 0.0)));

    String html = render(myLager());

    assertThat(html)
        .contains(SELECTION_SLOT + " hidden", "data-url=\"/inventory/my/sale-value\"")
        .contains("data-error=\"Der Verkaufswert der Auswahl konnte nicht berechnet werden.\"")
        .contains("data-lager-sale-value=\"total\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theMarkedEntriesRenderTheSelectionsRow() throws Exception {
    when(backendApiClient.post(
            eq(SELECTION_SALE_VALUE), any(), eq(InventorySelectionSaleValueDto.class)))
        .thenReturn(
            new InventorySelectionSaleValueDto(
                SalePriceBasis.AVERAGE, new BigDecimal("54000"), 1, true));

    String html = render(selectionRow());

    assertThat(html)
        .contains("data-testid=\"lager-sale-value-selection\"")
        .contains("Geschätzter Verkaufswert (Auswahl)", "≈ 54.000", "1 Material ohne Terminalpreis")
        .contains("Gestohlene Bestände nicht bewertet", "Sind Einträge markiert, zählen nur diese.")
        .doesNotContain(SUM_ROW, "(gefiltert)");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void anUnreachableBackendRendersTheSelectionsRowWithoutATotal() throws Exception {
    when(backendApiClient.post(
            eq(SELECTION_SALE_VALUE), any(), eq(InventorySelectionSaleValueDto.class)))
        .thenThrow(new BackendServiceException("down", null, 503));

    String html = render(selectionRow());

    assertThat(html)
        .contains("Geschätzter Verkaufswert (Auswahl)", "Wert der Auswahl gerade nicht verfügbar")
        .doesNotContain("≈");
  }

  private MockHttpServletRequestBuilder selectionRow() {
    return post("/inventory/my/sale-value")
        .with(csrf())
        .header("X-Requested-With", "XMLHttpRequest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"itemIds\":[\"" + UUID.randomUUID() + "\"]}")
        .locale(Locale.GERMAN);
  }

  private MockHttpServletRequestBuilder myLager() {
    return get("/inventory").param("storage", "my").locale(Locale.GERMAN);
  }

  private String render(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void stubMyGroups(GroupedInventoryDto... groups) {
    List<GroupedInventoryDto> listing = List.of(groups);
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(inv -> {
          String url = inv.getArgument(0);
          return url.contains("/inventory/my-inventory/grouped")
              ? listing
              : Collections.emptyList();
        });
  }

  private static GroupedInventoryDto group(
      String name, String quantityType, double amount, InventorySaleValueDto saleValue) {
    return new GroupedInventoryDto(
        new MaterialReferenceDto(UUID.randomUUID(), name, quantityType),
        null,
        amount,
        500.0,
        500,
        List.of(),
        false,
        saleValue);
  }

  private static InventorySaleValueDto average(
      String price, int terminals, String value, double stolen) {
    return new InventorySaleValueDto(
        SalePriceBasis.AVERAGE,
        new BigDecimal(price),
        null,
        terminals,
        new BigDecimal(value),
        stolen);
  }
}
