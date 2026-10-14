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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.inventory.model.DonationAllocationDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * Render coverage of the donation controls of the personal Lager (REQ-SUP-007, REQ-SUP-008): the
 * donate action appears only while the supply surface is on, and a pledged slice shows as a chip.
 */
@SpringBootTest
class InventorySupplyRenderTest {

  private static final String DONATE_ACTION = "data-trigger=\"supply-lager-donate\"";

  private static final String PLEDGE_CHIP = "data-testid=\"donation-chip\"";

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState supplyFeatureState;

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
    supplyFeatureState.evict();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    supplyFeatureState.evict();
  }

  private void supplySwitch(boolean enabled) {
    when(backendApiClient.get("/api/v1/supply/state", SupplyStateDto.class))
        .thenReturn(new SupplyStateDto(enabled));
  }

  private InventoryItemDto entry(List<DonationAllocationDto> donations) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
        new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
        null,
        new LocationReferenceDto(locationId, "Port Olisar"),
        700,
        10.0,
        false,
        false,
        List.of(),
        10.0,
        List.of(),
        10.0,
        donations,
        null,
        null,
        3L,
        true,
        Instant.parse("2026-09-01T10:00:00Z"));
  }

  private void stubEntries(InventoryItemDto item) {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
  }

  private org.springframework.test.web.servlet.ResultActions renderEntries() throws Exception {
    return mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "700")
                .param("personal", "false")
                .param("stolen", "false"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void switchOn_offersTheDonateActionAndShowsThePledgedSlice() throws Exception {
    supplySwitch(true);
    stubEntries(
        entry(List.of(new DonationAllocationDto(UUID.randomUUID(), "Bereich Profit", 4.0))));

    renderEntries()
        .andExpect(content().string(containsString(DONATE_ACTION)))
        .andExpect(content().string(containsString(PLEDGE_CHIP)))
        .andExpect(content().string(containsString("Bereich Profit")))
        .andExpect(content().string(containsString("data-reserved=\"4.0\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void switchOn_aRowWithoutPledgeHasNoChip() throws Exception {
    supplySwitch(true);
    stubEntries(entry(List.of()));

    renderEntries()
        .andExpect(content().string(containsString(DONATE_ACTION)))
        .andExpect(content().string(not(containsString(PLEDGE_CHIP))))
        .andExpect(content().string(containsString("data-reserved=\"0\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void switchOff_hidesTheDonateAction() throws Exception {
    supplySwitch(false);
    stubEntries(entry(List.of()));

    renderEntries().andExpect(content().string(not(containsString(DONATE_ACTION))));
  }
}
