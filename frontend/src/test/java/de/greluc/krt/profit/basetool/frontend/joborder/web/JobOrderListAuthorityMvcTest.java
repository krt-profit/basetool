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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.support.CapabilityStubs;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Renders queue reorder controls only for an order's current processing writer. */
class JobOrderListAuthorityMvcTest extends JobOrderPageMvcTestBase {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    CapabilityStubs.report(
        backendApiClient,
        new CapabilitiesResponse(
            false, true, false, false, true, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false));
  }

  @ParameterizedTest
  @CsvSource({"true,false,1,true", "false,false,1,false", "true,true,1,false", "true,false,,false"})
  @WithMockUser(roles = {"KRT_MEMBER", "LOGISTICIAN"})
  void queueFragmentOffersReorderingOnlyForAPrioritizedValidOrderTheCallerMayProcess(
      boolean canEdit, boolean invalid, Integer priority, boolean reorderable) throws Exception {
    when(reader.readOrderList(any()))
        .thenReturn(
            new JobOrderPageReader.OrderListPage(
                true,
                List.of(order(canEdit, invalid, priority)),
                Optional.empty(),
                30,
                90,
                List.of()));

    String html =
        mockMvc
            .perform(get("/orders").param("scope", "TO_PROCESS").param("fragment", "results"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html.contains("class=\"orders-drag-handle\""))
        .as("reorder handle for the current order authority")
        .isEqualTo(reorderable);
    assertThat(html.contains("draggable=\"true\""))
        .as("drag affordance for the current order authority")
        .isEqualTo(reorderable);
  }

  private static JobOrderDto order(boolean canEdit, boolean invalid, Integer priority) {
    return new JobOrderDto(
        UUID.randomUUID(),
        7,
        null,
        null,
        "Handle",
        null,
        priority,
        "OPEN",
        "MATERIAL",
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        1L,
        canEdit,
        false,
        false,
        invalid,
        false,
        false,
        false);
  }
}
