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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
 * Regression for the Thymeleaf 3.1 JS-inline truncation bug on {@code /ship-data}.
 *
 * <p>Pre-fix, the template called {@code /*[[${shipTypes.![name]}]]*&#47;} immediately followed by
 * {@code /*[[${manufacturers.![name]}]]*&#47;} inside a {@code th:inline="javascript"} script. The
 * first inline expression already truncated the rest of the script, so the second autocomplete
 * never wired, {@code filterTable} was never defined (filter inputs above each table did nothing),
 * and {@code closeModal} was never registered (the "reset all fitted" confirmation modal could not
 * be cancelled). The fix moves both name lists into sibling {@code <datalist>} elements.
 *
 * <p>This test pins that the {@code 'ship-close-modal'} delegated binding string — registered at
 * the very tail of the script — appears in the rendered HTML, and that the response ends with the
 * closing {@code </html>} tag.
 */
@SpringBootTest
class ShipDataPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Asserts the {@code 'ship-close-modal'} delegated-event registration (defined AFTER both
   * datalists in the script) appears in the rendered HTML — proof that the Thymeleaf truncation
   * does not strike again.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listData_ShouldRenderCloseModalBinding_AfterBothDatalists() throws Exception {
    ManufacturerDto manufacturer =
        new ManufacturerDto(
            UUID.randomUUID(),
            "Aegis Dynamics",
            "AEGS",
            "Aegis",
            "https://example/aegis",
            "Mil-style",
            false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", manufacturer, "Titan", 8, false);
    PageResponse<ManufacturerDto> manufacturersPage =
        new PageResponse<>(List.of(manufacturer), 0, 1000, 1, 1, Collections.emptyList());
    PageResponse<ShipTypeDto> shipTypesPage =
        new PageResponse<>(List.of(shipType), 0, 1000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true"), anyTypeRef()))
        .thenReturn(manufacturersPage);
    when(backendApiClient.get(
            eq("/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true"), anyTypeRef()))
        .thenReturn(shipTypesPage);

    mockMvc
        .perform(get("/ship-data"))
        .andExpect(status().isOk())
        .andExpect(view().name("ship-data"))
        .andExpect(content().string(containsString("id=\"shipTypeNames-data\"")))
        .andExpect(content().string(containsString("id=\"mfgNames-data\"")))
        .andExpect(content().string(containsString("value=\"Avenger Titan\"")))
        .andExpect(content().string(containsString("value=\"Aegis Dynamics\"")))
        .andExpect(content().string(containsString("'ship-close-modal'")))
        .andExpect(content().string(containsString("</html>")));
  }
}
