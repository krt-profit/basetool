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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
 * Binding-level tests for the request parameters the frontend relays into a backend URI
 * (REQ-SEC-051).
 *
 * <p>The unit tests around each controller prove what happens to a value that reaches the method
 * body; these prove that a value carrying URI syntax never gets that far. Periods and identifiers
 * are bound as {@link java.time.Instant} and {@link UUID} — the same types the backend's own
 * controllers declare — so Spring's type conversion rejects them at the seam and {@code
 * GlobalExceptionHandler} answers {@code 400}, with no backend call made.
 */
@SpringBootTest
class RelayParamBindingMvcTest {

  private static final String HOSTILE_PERIOD = "2026-01-01T00:00:00Z&role=ADMIN";

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
  void auditExportProxy_rejectsAPeriodThatIsNotAnInstant() throws Exception {
    mockMvc
        .perform(
            get("/api/proxy/audit/BANK/export")
                .param("from", HOSTILE_PERIOD)
                .param("to", "2026-02-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void auditPurgeProxy_rejectsACutoffThatIsNotAnInstant() throws Exception {
    mockMvc
        .perform(delete("/api/proxy/audit/BANK").param("before", HOSTILE_PERIOD).with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void bankStatementProxy_rejectsAPeriodThatIsNotAnInstant() throws Exception {
    mockMvc
        .perform(
            get("/api/proxy/bank/accounts/" + UUID.randomUUID() + "/statement")
                .param("from", HOSTILE_PERIOD)
                .param("to", "2026-02-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void orgUnitStatementProxy_rejectsAPeriodThatIsNotAnInstant() throws Exception {
    mockMvc
        .perform(
            get("/api/proxy/org-units/bank/accounts/" + UUID.randomUUID() + "/statement")
                .param("from", HOSTILE_PERIOD)
                .param("to", "2026-02-01T00:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminPersonalInventoryPage_ignoresAMemberIdThatIsNotAUuid() throws Exception {
    // The page degrades instead of erroring — an unparseable member selects nobody, which is the
    // empty page the picker starts on — but the raw value must not reach the backend either.
    mockMvc
        .perform(get("/admin/personal-inventory").param("userSub", "../../etc/passwd"))
        .andExpect(status().isOk());

    verify(backendApiClient, never())
        .get(contains("/api/v1/admin/personal-inventory/"), anyTypeRef());
  }
}
