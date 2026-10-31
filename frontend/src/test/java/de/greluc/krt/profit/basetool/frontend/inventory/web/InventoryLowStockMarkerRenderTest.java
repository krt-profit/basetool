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

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
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
 * Render coverage of the „Niedrig" marker on a material of the global Lager tree whose minimum
 * stock level is low (REQ-INV-068).
 */
@SpringBootTest
class InventoryLowStockMarkerRenderTest {

  private static final String MARKER = "data-testid=\"inventory-low-stock\"";

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

  private void stubGrouped(boolean lowStock) {
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            new MaterialReferenceDto(UUID.randomUUID(), "Quantanium", "SCU"),
            null,
            20.0,
            700.0,
            700,
            List.of(),
            lowStock);
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(inv -> {
          String url = inv.getArgument(0);
          return url.contains("/inventory/all/grouped") ? List.of(group) : Collections.emptyList();
        });
  }

  private void stubGroupedItem(boolean lowStock) {
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(UUID.randomUUID(), "Gatling S2", null, null),
            4.0,
            null,
            null,
            List.of(),
            lowStock);
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(inv -> {
          String url = inv.getArgument(0);
          return url.contains("/inventory/all/grouped") ? List.of(group) : Collections.emptyList();
        });
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aGameItemWithALowLevelCarriesTheMarkerAndOneWithoutDoesNot() throws Exception {
    stubGroupedItem(true);
    mockMvc
        .perform(get("/inventory/all").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(MARKER)));

    stubGroupedItem(false);
    mockMvc
        .perform(get("/inventory/all").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(MARKER))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMaterialWithALowLevelCarriesTheMarker() throws Exception {
    stubGrouped(true);

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(MARKER)));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMaterialWithoutALowLevelCarriesNoMarker() throws Exception {
    stubGrouped(false);

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(MARKER))));
  }
}
