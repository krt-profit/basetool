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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies creation enters the created order's common workspace through every form transport. */
class JobOrderCreationWorkspaceMvcTest extends JobOrderPageMvcTestBase {

  @Autowired private WebApplicationContext context;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  @WithMockUser
  void successfulCreationUsesReturnedIdentityForItsCentralDetail(boolean item, boolean ajax)
      throws Exception {
    UUID createdId = UUID.fromString("00000000-0000-0000-0000-000000000053");
    JobOrderDto created =
        new JobOrderDto(
            createdId,
            53,
            null,
            null,
            "Creator",
            null,
            null,
            "OPEN",
            item ? "ITEM" : "MATERIAL",
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.parse("2026-11-09T10:00:00Z"),
            0L,
            false,
            false,
            false,
            false,
            false,
            false);
    when(backendApiClient.post(
            eq(item ? "/api/v1/orders/items" : "/api/v1/orders"), any(), eq(JobOrderDto.class)))
        .thenReturn(created);
    MockHttpServletRequestBuilder request =
        post(item ? "/orders/items" : "/orders/create")
            .with(csrf())
            .param("responsibleOrgUnitId", UUID.randomUUID().toString())
            .param("requestingOrgUnitId", UUID.randomUUID().toString())
            .param("handle", "Creator")
            .param("source", "inventory");
    if (item) {
      request
          .param("items[0].gameItemId", UUID.randomUUID().toString())
          .param("items[0].blueprintId", UUID.randomUUID().toString())
          .param("items[0].amount", "2");
    } else {
      request
          .param("materials[0].materialId", UUID.randomUUID().toString())
          .param("materials[0].amount", "2");
    }
    if (ajax) {
      mvc.perform(request.header("X-Requested-With", "XMLHttpRequest"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.targetUrl").value("/orders/" + createdId));
    } else {
      mvc.perform(request)
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/orders/" + createdId));
    }
  }
}
