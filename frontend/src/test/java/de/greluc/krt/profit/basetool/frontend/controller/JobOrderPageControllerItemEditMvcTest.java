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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
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
 * MVC tests for the item-order edit wiring (item-edit follow-up): the create form reused in edit
 * mode ({@code GET /orders/{id}/items/edit}, on {@link JobOrderPageController}) and the update
 * relay ({@code POST /orders/{id}/items/update} → backend {@code PUT /api/v1/orders/{id}/items},
 * since the #924 split in {@link JobOrderWriteController}). Covers the two block cases (non-item
 * order, order with deliveries) and the happy edit-page render + relay.
 */
@SpringBootTest
class JobOrderPageControllerItemEditMvcTest {

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

  private JobOrderDto order(UUID id, String type, List<JobOrderItemHandoverDto> itemHandovers) {
    return new JobOrderDto(
        id,
        7,
        null,
        null,
        "Handle",
        null,
        1,
        "OPEN",
        type,
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        itemHandovers,
        Instant.now(),
        1L,
        null,
        false);
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void editForm_itemOrderNoHandovers_rendersCreateFormInEditMode() throws Exception {
    UUID id = UUID.randomUUID();
    doReturn(order(id, "ITEM", List.of()))
        .when(backendApiClient)
        .get(eq("/api/v1/orders/" + id), eq(JobOrderDto.class));

    mockMvc
        .perform(get("/orders/" + id + "/items/edit"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("/orders/" + id + "/items/update")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Auftrag bearbeiten")));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void editForm_nonItemOrder_redirectsWithError() throws Exception {
    UUID id = UUID.randomUUID();
    doReturn(order(id, "MATERIAL", List.of()))
        .when(backendApiClient)
        .get(eq("/api/v1/orders/" + id), eq(JobOrderDto.class));

    mockMvc
        .perform(get("/orders/" + id + "/items/edit"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/" + id))
        .andExpect(flash().attribute("errorToast", "error.joborder.item.edit.notItem"));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void editForm_itemOrderWithHandover_redirectsWithError() throws Exception {
    UUID id = UUID.randomUUID();
    JobOrderItemHandoverDto handover =
        new JobOrderItemHandoverDto(
            UUID.randomUUID(), id, Instant.now(), "R", null, null, List.of(), 1L);
    doReturn(order(id, "ITEM", List.of(handover)))
        .when(backendApiClient)
        .get(eq("/api/v1/orders/" + id), eq(JobOrderDto.class));

    mockMvc
        .perform(get("/orders/" + id + "/items/edit"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/" + id))
        .andExpect(flash().attribute("errorToast", "error.joborder.item.edit.hasHandovers"));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void updateItemOrder_relaysToBackendAndRedirects() throws Exception {
    UUID id = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID blueprintId = UUID.randomUUID();
    doReturn(order(id, "ITEM", List.of()))
        .when(backendApiClient)
        .put(
            eq("/api/v1/orders/" + id + "/items"),
            any(CreateJobOrderItemRequestDto.class),
            eq(JobOrderDto.class));

    mockMvc
        .perform(
            post("/orders/" + id + "/items/update")
                .with(csrf())
                .param("handle", "edited")
                .param("version", "1")
                .param("items[0].gameItemId", gameItemId.toString())
                .param("items[0].blueprintId", blueprintId.toString())
                .param("items[0].amount", "2")
                .param("items[0].clientLineId", "0"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/orders/" + id))
        .andExpect(flash().attribute("successToast", "success.joborder.update"));

    verify(backendApiClient)
        .put(
            eq("/api/v1/orders/" + id + "/items"),
            any(CreateJobOrderItemRequestDto.class),
            eq(JobOrderDto.class));
  }

  @Test
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void editForm_itemOrderWithLine_inlinesSavedItemNameForPicker() throws Exception {
    UUID id = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID blueprintId = UUID.randomUUID();
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(gameItemId, "P8-SC SMG", "WEAPON"),
            new BlueprintReferenceDto(blueprintId, "P8-SC SMG", "BP_CRAFT_behr_smg_ballistic_01"),
            5,
            0,
            0,
            null,
            List.of(),
            false,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            id,
            7,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    doReturn(order).when(backendApiClient).get(eq("/api/v1/orders/" + id), eq(JobOrderDto.class));

    // The saved item's name must reach the page (inlined into window.EDIT_ITEMS) so the now
    // load-on-demand item picker shows it as the selected option without a search round-trip.
    mockMvc
        .perform(get("/orders/" + id + "/items/edit"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("P8-SC SMG")));
  }
}
