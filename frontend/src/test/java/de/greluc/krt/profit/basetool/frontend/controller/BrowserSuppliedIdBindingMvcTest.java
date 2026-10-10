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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A browser-supplied identifier that is not a UUID is rejected with {@code 400} before any backend
 * call is made, so it can never extend the relayed backend URI with extra path segments or query
 * parameters (REQ-SEC-051).
 */
@SpringBootTest
class BrowserSuppliedIdBindingMvcTest {

  private static final String CRAFTED = "a?b#c/../x";

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void announcementRead_craftedId_is400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(post("/announcement/read").param("id", CRAFTED).with(csrf()).with(oidcLogin()))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("/announcements/");
  }

  @Test
  void announcementReadAjax_craftedId_is400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(
            post("/announcement/read")
                .param("id", CRAFTED)
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .with(oidcLogin()))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("/announcements/");
  }

  @Test
  void promotionManageEligibilityCell_craftedUserId_is400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(
            get("/promotion/manage")
                .param("fragment", "eligibilityCell")
                .param("userId", CRAFTED)
                .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("eligibility/user");
  }

  @Test
  void defaultBlueprintRemove_craftedId_is400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(post("/admin/default-blueprints/{id}/delete", "a?b#c").with(csrf()).with(admin()))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("default");
  }

  @Test
  void orgUnitBankRoleVisibility_craftedRoleCode_is400AndNoBackendCall() throws Exception {
    UUID account = UUID.randomUUID();
    for (String crafted : List.of("a?b#c", "kommandoleiter")) {
      mockMvc
          .perform(
              post(
                      "/api/proxy/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
                      account,
                      crafted)
                  .with(csrf())
                  .with(admin()))
          .andExpect(status().isBadRequest());
      mockMvc
          .perform(
              delete(
                      "/api/proxy/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}",
                      account,
                      crafted)
                  .with(csrf())
                  .with(admin()))
          .andExpect(status().isBadRequest());
    }

    assertNoBackendCallMentions("org-units/bank");
  }

  @Test
  void missionManagers_craftedIds_are400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(
            post("/missions/{id}/managers/{userId}", "a?b#c", UUID.randomUUID())
                .with(csrf())
                .with(admin()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            delete("/missions/{id}/managers/{userId}", UUID.randomUUID(), "a?b#c")
                .with(csrf())
                .with(admin()))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("managers");
  }

  @Test
  void connectedAppsClientId_crafted_is400AndNoBackendCall() throws Exception {
    mockMvc
        .perform(
            delete("/connected-apps/{clientId}", "a?b#c")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .with(admin()))
        .andExpect(status().isBadRequest());

    assertNoBackendCallMentions("exchange");
  }

  @Test
  void auditExportDomain_crafted_is400() throws Exception {
    mockMvc
        .perform(
            get("/api/proxy/audit/{domain}/export", "BANK?x#y")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uexLoadingDockKind_crafted_isRefusedAndNoBackendCall() throws Exception {
    mockMvc.perform(
        post("/admin/uex-data/{kind}/{id}/loading-dock", "a?b#c", UUID.randomUUID())
            .param("action", "set")
            .with(csrf())
            .with(admin()));

    assertNoBackendCallMentions("loading-dock");
    assertNoBackendCallMentions("a?b");
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private void assertNoBackendCallMentions(String fragment) {
    assertThat(Mockito.mockingDetails(backendApiClient).getInvocations())
        .noneMatch(
            invocation ->
                invocation.getArguments().length > 0
                    && invocation.getArguments()[0] instanceof String uri
                    && uri.contains(fragment));
  }
}
