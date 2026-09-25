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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.math.BigDecimal;
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
 * MVC-level test for {@link AdminBankPageController}'s in-place wipe-reset AJAX twin (epic #571 /
 * #582). Proves the {@code X-Requested-With} header routing: the twin is {@code @ResponseBody} and
 * returns the affected counts as JSON ({@code 200}) on a valid {@code WIPE} confirm token, rejects
 * a wrong token with {@code 400}, relays a backend conflict as {@code problem+json} (so {@code
 * krtFetch} can offer the reload-confirm), and the same URL without the header still hits the
 * classic redirect handler. Fails if the header gating, the confirm-token backstop, or the conflict
 * relay breaks.
 */
@SpringBootTest
class AdminBankPageControllerMvcTest {

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

  @Test
  @WithMockUser(roles = "ADMIN")
  void wipeResetAjax_withHeaderAndConfirm_returns200WithCounts() throws Exception {
    when(backendApiClient.post(
            contains("/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenReturn(new BankWipeResetResultDto(5, 12, new BigDecimal("1250")));

    mockMvc
        .perform(
            post("/admin/bank/wipe-reset")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("confirm", "WIPE"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("accountsReset")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void wipeResetAjax_withHeaderWrongConfirm_returns400() throws Exception {
    mockMvc
        .perform(
            post("/admin/bank/wipe-reset")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("confirm", "nope"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void wipeResetAjax_backendConflict_relays409() throws Exception {
    when(backendApiClient.post(
            contains("/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenThrow(
            new BackendServiceException(
                "conflict", null, 409, "PESSIMISTIC_LOCK", null, java.util.List.of(), "conflict"));

    mockMvc
        .perform(
            post("/admin/bank/wipe-reset")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("confirm", "WIPE"))
        .andExpect(status().isConflict())
        .andExpect(content().string(containsString("PESSIMISTIC_LOCK")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void wipeReset_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(
            contains("/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenReturn(new BankWipeResetResultDto(0, 0, BigDecimal.ZERO));

    mockMvc
        .perform(post("/admin/bank/wipe-reset").with(csrf()).param("confirm", "WIPE"))
        .andExpect(status().is3xxRedirection());
  }
}
