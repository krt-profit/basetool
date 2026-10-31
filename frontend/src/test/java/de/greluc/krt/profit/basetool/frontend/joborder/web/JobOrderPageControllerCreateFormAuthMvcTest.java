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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who may open the job-order create form ({@code GET /orders/create}) and what it loads: anonymous
 * access is refused (ADR-0149) and the materials catalog is fetched through the member's bearer. It
 * runs over the real reader and typed client on purpose, to pin that wiring end to end.
 */
@SpringBootTest
class JobOrderPageControllerCreateFormAuthMvcTest {

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
  @WithAnonymousUser
  void viewCreateForm_AsAnonymousGuest_IsRefused() throws Exception {
    mockMvc.perform(get("/orders/create")).andExpect(status().is3xxRedirection());

    verify(backendApiClient, never()).getCached(any(CachedCatalog.class), anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewCreateForm_ShouldFetchMaterialsThroughTheMembersBearer() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/orders/create"))
        .andExpect(status().isOk())
        .andExpect(view().name("joborder/orders-create"));

    verify(backendApiClient).getCached(eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef());
  }

  @Test
  @WithMockUser
  void viewCreateForm_WithPrefillParameters_StartsTheFormWithThem() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    UUID material = UUID.randomUUID();
    UUID unit = UUID.randomUUID();

    JobOrderForm form =
        (JobOrderForm)
            mockMvc
                .perform(
                    get("/orders/create")
                        .param("materialId", material.toString())
                        .param("minQuality", "650")
                        .param("amount", "4.5")
                        .param("requestingOrgUnitId", unit.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getModelAndView()
                .getModel()
                .get("jobOrderForm");

    assertThat(form.getMaterials().getFirst().getMaterialId()).isEqualTo(material);
    assertThat(form.getMaterials().getFirst().getMinQuality()).isEqualTo(650);
    assertThat(form.getMaterials().getFirst().getAmount()).isEqualTo(4.5);
    assertThat(form.getRequestingOrgUnitId()).isEqualTo(unit);
  }
}
