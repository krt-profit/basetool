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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportDueDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The "P4K import due" banner of {@code /admin/p4k-import} (REQ-P4K-047): the page carries the
 * hidden banner and its script, and the proxy relays the backend's due signal for the in-place
 * update.
 */
@SpringBootTest
class AdminP4kImportDueMvcTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void thePage_carriesTheHiddenDueBannerAndItsLabels() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/p4k-import").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("id=\"krt-p4k-due\"", "P4K-Import fällig", "letzter Import mit bekannter");
    assertThat(html).containsPattern("id=\"krt-p4k-due\"[^>]*hidden");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void theDueProxy_relaysTheBackendsSignal() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/catalog/admin/import/p4k/due"), eq(P4kImportDueDto.class)))
        .thenReturn(new P4kImportDueDto(true, "4.10.3", null, "4.10.2", "PUBLIC_PATCH"));

    mockMvc
        .perform(get("/admin/p4k-import/due"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.due").value(true))
        .andExpect(jsonPath("$.uexVersion").value("4.10.3"))
        .andExpect(jsonPath("$.basisVersion").value("4.10.2"))
        .andExpect(jsonPath("$.basisOrigin").value("PUBLIC_PATCH"));
  }

  @Test
  @WithMockUser(roles = "MEMBER")
  void aMember_cannotReadTheDueSignal() throws Exception {
    mockMvc.perform(get("/admin/p4k-import/due")).andExpect(status().isForbidden());
  }
}
