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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render regression for the S12 (#918) {@code fragments/modal-wrapper :: modal(...)} extraction on
 * {@code /admin/bank}. The wipe-reset modal exercises the {@code variant} parameter ({@code
 * krt-modal--danger}); this pins that the fragment appends the variant class, renders the unified
 * close trigger, and projects the confirm {@code <form>} body exactly once.
 */
@SpringBootTest
class AdminBankWipeModalRenderMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void wipeModal_appliesDangerVariant_andProjectsFormExactlyOnce() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/bank"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).contains("id=\"bank-wipe-modal\"");
    assertThat(html).contains("class=\"krt-modal krt-modal--danger\"");
    assertThat(html)
        .contains("class=\"krt-modal-close\"")
        .contains("data-trigger=\"close-modal-display\"")
        .contains("data-modal-id=\"bank-wipe-modal\"");
    assertThat(html).contains("data-testid=\"bank-wipe-submit\"");
    assertThat(StringUtils.countOccurrencesOf(html, "data-bank-wipe")).isEqualTo(1);
  }
}
