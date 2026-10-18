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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SubAssemblySuggestionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC tests for the job-order item picker relays: the search maps id, name and option label, the
 * derivation preview labels its sub-assemblies (REQ-INV-064), and a failed search yields an empty
 * list.
 */
@SpringBootTest
class JobOrderItemPickerControllerMvcTest {

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
  @WithMockUser
  void itemSearch_mapsBackendPageToReferenceList() throws Exception {
    UUID id = UUID.randomUUID();
    PageResponse<InventoryGameItemReferenceDto> page =
        new PageResponse<>(
            List.of(new InventoryGameItemReferenceDto(id, "P8-SC SMG", null, "WEAPON")),
            0,
            25,
            1L,
            1,
            List.of());
    doReturn(page).when(backendApiClient).get(any(), anyTypeRef(), any(Object[].class));

    mockMvc
        .perform(get("/orders/item-search").param("q", "p8"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("P8-SC SMG"))
        .andExpect(jsonPath("$[0].label").value("P8-SC SMG"));
  }

  @Test
  @WithMockUser
  void itemSearch_labelsAComponentWithSizeGradeAndClass() throws Exception {
    UUID id = UUID.randomUUID();
    PageResponse<InventoryGameItemReferenceDto> page =
        new PageResponse<>(
            List.of(
                new InventoryGameItemReferenceDto(
                    id, "FR-66", "Gorgon", "SHIP_ITEM", "SHIELD", 1, "A", "MILITARY")),
            0,
            25,
            1L,
            1,
            List.of());
    doReturn(page).when(backendApiClient).get(any(), anyTypeRef(), any(Object[].class));

    mockMvc
        .perform(get("/orders/item-search").param("q", "FR-66").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id.toString()))
        .andExpect(jsonPath("$[0].name").value("FR-66"))
        .andExpect(jsonPath("$[0].label").value("FR-66 · S1 · A · Militär"));
  }

  @Test
  @WithMockUser
  void itemDerivation_labelsTheSubAssemblies() throws Exception {
    UUID blueprintId = UUID.randomUUID();
    GameItemReferenceDto shield =
        new GameItemReferenceDto(
            UUID.randomUUID(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY");
    ItemDerivationDto derivation =
        new ItemDerivationDto(
            new BlueprintReferenceDto(blueprintId, "Rig", "rig"),
            2,
            List.of(),
            List.of(new SubAssemblySuggestionDto(shield, 4, List.of())),
            List.of());
    doReturn(derivation)
        .when(backendApiClient)
        .get(
            eq("/api/v1/blueprints/{blueprintId}/derivation?amount={amount}"),
            eq(ItemDerivationDto.class),
            any(Object[].class));

    mockMvc
        .perform(
            get("/orders/item-derivation/{id}", blueprintId)
                .param("amount", "2")
                .locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(2))
        .andExpect(jsonPath("$.subAssemblies[0].gameItem.name").value("FR-66"))
        .andExpect(jsonPath("$.subAssemblies[0].label").value("FR-66 · S1 · A · Militär"))
        .andExpect(jsonPath("$.subAssemblies[0].quantity").value(4));
  }

  @Test
  @WithMockUser
  void itemSearch_backendFailure_returnsEmptyList() throws Exception {
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .get(any(), anyTypeRef(), any(Object[].class));

    mockMvc
        .perform(get("/orders/item-search").param("q", "x"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
}
