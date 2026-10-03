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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
 * Render coverage of the „gestohlen" marker in the Lager (REQ-INV-053): the danger chip and the
 * stack identity on stolen stacks and rows, the three-state filter relayed to the backend, the
 * marker forwarded by the stack drill-down, and the book-in checkbox and mark/unmark actions that
 * appear only while the server switch {@code canMarkStolen} is on.
 */
@SpringBootTest
class InventoryStolenMarkerRenderTest {

  private static final String STOLEN_CHIP = "data-testid=\"stolen-chip\"";

  private static final String MARK_ACTION = "data-trigger=\"inv-my-stolen\"";

  private static final String ADMIN_MARK_ACTION = "data-trigger=\"inv-admin-stolen\"";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private final UUID materialId = UUID.randomUUID();
  private final UUID locationId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
  }

  private void stubMarking(boolean canMarkStolen) {
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.stolenMarking(canMarkStolen));
  }

  private InventoryStackDto stack(boolean stolen) {
    return new InventoryStackDto(
        new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
        new LocationReferenceDto(locationId, "Port Olisar"),
        700,
        false,
        stolen,
        null,
        10.0,
        700.0,
        700,
        1);
  }

  private GroupedInventoryDto group(InventoryStackDto... stacks) {
    return new GroupedInventoryDto(
        new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
        null,
        20.0,
        700.0,
        700,
        List.of(stacks));
  }

  private InventoryItemDto entry(UUID id, boolean stolen, boolean canEdit) {
    return new InventoryItemDto(
        id,
        new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
        new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
        null,
        new LocationReferenceDto(locationId, "Port Olisar"),
        700,
        10.0,
        false,
        stolen,
        List.of(),
        10.0,
        List.of(),
        10.0,
        null,
        null,
        3L,
        canEdit,
        Instant.parse("2026-09-01T10:00:00Z"));
  }

  private void stubGrouped(String pathFragment, GroupedInventoryDto group) {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains(pathFragment)) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
  }

  private void stubEntries(String pathFragment, InventoryItemDto item) {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains(pathFragment)) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myInventory_stolenStackCarriesItsIdentityAndTheChip() throws Exception {
    stubGrouped("/inventory/my-inventory/grouped", group(stack(true), stack(false)));

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-stolen=\"true\"")))
        .andExpect(content().string(containsString("data-stolen=\"false\"")))
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(containsString("id=\"stolenFilter\"")))
        .andExpect(content().string(containsString("value=\"non\"")))
        .andExpect(content().string(containsString("value=\"only\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myInventory_legitimateStackCarriesNoChip() throws Exception {
    stubGrouped("/inventory/my-inventory/grouped", group(stack(false)));

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(STOLEN_CHIP))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myInventory_stolenOnlyFilterIsRelayedAndPreselected() throws Exception {
    stubGrouped("/inventory/my-inventory/grouped", group(stack(true)));

    mockMvc
        .perform(get("/inventory/my").param("stolenOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"only\" selected=\"selected\"")));

    verify(backendApiClient, atLeastOnce())
        .get(contains("/my-inventory/grouped?stolenOnly=true"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myEntryIds_relayNonStolenOnly() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    mockMvc
        .perform(get("/inventory/my/entry-ids").param("nonStolenOnly", "true"))
        .andExpect(status().isOk());

    verify(backendApiClient).get(contains("nonStolenOnly=true"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void allInventory_rendersChipAndRelaysNonStolenOnly() throws Exception {
    stubGrouped("/inventory/all/grouped", group(stack(true)));

    mockMvc
        .perform(get("/inventory/all").param("nonStolenOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-stolen=\"true\"")))
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(containsString("value=\"non\" selected=\"selected\"")));

    verify(backendApiClient, atLeastOnce())
        .get(contains("/all/grouped?nonStolenOnly=true"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myInventory_markingOn_rendersTheDialogAndBulkButtons() throws Exception {
    stubMarking(true);
    stubGrouped("/inventory/my-inventory/grouped", group(stack(false)));

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"stolenMarkModal\"")))
        .andExpect(content().string(containsString("id=\"stolenMarkForm\"")))
        .andExpect(content().string(containsString("id=\"bulkStolenMarkBtn\"")))
        .andExpect(content().string(containsString("id=\"bulkStolenUnmarkBtn\"")))
        .andExpect(content().string(containsString("var stolenMarkI18n")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myInventory_markingOff_hidesTheDialogAndBulkButtons() throws Exception {
    stubMarking(false);
    stubGrouped("/inventory/my-inventory/grouped", group(stack(true)));

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(containsString("id=\"stolenFilter\"")))
        .andExpect(content().string(not(containsString("id=\"stolenMarkModal\""))))
        .andExpect(content().string(not(containsString("id=\"bulkStolenMarkBtn\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void myStackEntries_forwardTheMarkerAndOfferUnmarkWhileMarkingIsOn() throws Exception {
    stubMarking(true);
    UUID itemId = UUID.randomUUID();
    stubEntries("/inventory/my-inventory/stack/entries", entry(itemId, true, true));

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "700")
                .param("personal", "false")
                .param("stolen", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(containsString(MARK_ACTION)))
        .andExpect(content().string(containsString("data-testid=\"stolen-mark-open\"")));

    verify(backendApiClient).get(contains("personal=false&stolen=true"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void myStackEntries_defaultToTheLegitimateStack() throws Exception {
    stubMarking(true);
    stubEntries("/inventory/my-inventory/stack/entries", entry(UUID.randomUUID(), false, true));

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(STOLEN_CHIP))))
        .andExpect(content().string(containsString(MARK_ACTION)));

    verify(backendApiClient).get(contains("/my-inventory/stack/entries?"), anyTypeRef());
    verify(backendApiClient, never()).get(contains("stolen="), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void myStackEntries_hideTheActionWhileMarkingIsOff() throws Exception {
    stubMarking(false);
    stubEntries("/inventory/my-inventory/stack/entries", entry(UUID.randomUUID(), true, true));

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("stolen", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(not(containsString(MARK_ACTION))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void myStackEntries_hideTheActionOnARowTheCallerMayNotEdit() throws Exception {
    stubMarking(true);
    stubEntries("/inventory/my-inventory/stack/entries", entry(UUID.randomUUID(), false, false));

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(MARK_ACTION))));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void allStackEntries_forwardTheMarkerAndRenderTheChip() throws Exception {
    stubEntries("/inventory/all/stack/entries", entry(UUID.randomUUID(), true, true));

    mockMvc
        .perform(
            get("/inventory/all/stack/entries")
                .param("materialId", materialId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString())
                .param("stolen", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)));

    verify(backendApiClient).get(contains("stolen=true"), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void allStackEntries_offerUnmarkOnAnEditableRowWhileMarkingIsOn() throws Exception {
    stubMarking(true);
    UUID itemId = UUID.randomUUID();
    stubEntries("/inventory/all/stack/entries", entry(itemId, true, true));

    mockMvc
        .perform(allStackEntriesRequest(true))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(ADMIN_MARK_ACTION)))
        .andExpect(content().string(containsString("data-id=\"" + itemId + "\"")))
        .andExpect(content().string(containsString("data-stolen=\"true\"")));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void allStackEntries_hideTheActionWhileMarkingIsOff() throws Exception {
    stubMarking(false);
    stubEntries("/inventory/all/stack/entries", entry(UUID.randomUUID(), true, true));

    mockMvc
        .perform(allStackEntriesRequest(true))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(not(containsString(ADMIN_MARK_ACTION))));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void allStackEntries_hideTheActionOnARowTheCallerMayNotEdit() throws Exception {
    stubMarking(true);
    stubEntries("/inventory/all/stack/entries", entry(UUID.randomUUID(), false, false));

    mockMvc
        .perform(allStackEntriesRequest(false))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(ADMIN_MARK_ACTION))));

    verify(backendApiClient, never()).get(contains("stolen="), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void allInventory_markingOn_rendersTheDialogWithoutBulkButtons() throws Exception {
    stubMarking(true);
    stubGrouped("/inventory/all/grouped", group(stack(true)));

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"stolenMarkModal\"")))
        .andExpect(content().string(containsString("id=\"stolenMarkForm\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-close-stolen\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-stolen-all\"")))
        .andExpect(content().string(containsString("var stolenMarkI18n")))
        .andExpect(content().string(not(containsString("id=\"bulkStolenMarkBtn\""))));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void allInventory_markingOff_hidesTheDialog() throws Exception {
    stubMarking(false);
    stubGrouped("/inventory/all/grouped", group(stack(true)));

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(STOLEN_CHIP)))
        .andExpect(content().string(not(containsString("id=\"stolenMarkModal\""))))
        .andExpect(content().string(not(containsString("var stolenMarkI18n"))));
  }

  private org.springframework.test.web.servlet.RequestBuilder allStackEntriesRequest(
      boolean stolen) {
    var request =
        get("/inventory/all/stack/entries")
            .param("materialId", materialId.toString())
            .param("userId", userId.toString())
            .param("locationId", locationId.toString());
    return stolen ? request.param("stolen", "true") : request;
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void inputPage_offersTheStolenCheckboxWhileMarkingIsOn() throws Exception {
    stubMarking(true);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"stolen\"")))
        .andExpect(content().string(containsString("name=\"stolen\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void inputPage_hidesTheStolenCheckboxWhileMarkingIsOff() throws Exception {
    stubMarking(false);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("id=\"stolen\""))));
  }
}
