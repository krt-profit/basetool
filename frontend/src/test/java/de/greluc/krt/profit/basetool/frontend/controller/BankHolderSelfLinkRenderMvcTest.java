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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankTransferFeeRateDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the holder self-link rendering on the Bank management "Halter" tab (REQ-BANK-032): a plain
 * bank employee must see the link to <strong>their own</strong> holder row (so they can open their
 * own custody history) and must <strong>not</strong> see a link to any other holder.
 *
 * <p>The holder row keys the link on the OIDC {@code sub} (which equals {@code app_user.id} ==
 * {@link BankHolderDto#userId()}). The frontend deliberately exposes the {@code preferred_username}
 * as {@code Authentication#getName()} (user-name-attribute), so the {@code preferred_username} here
 * is set to a value that is NOT the {@code sub}: if the controller ever regresses to keying the
 * self-link off the authentication name instead of {@code principal.getSubject()}, the own-row link
 * disappears and this test fails. Mirrors {@code MissionSecurityRenderingTest} for the mission
 * participant self-edit carve-out.
 */
@SpringBootTest
class BankHolderSelfLinkRenderMvcTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean
  private de.greluc.krt.profit.basetool.frontend.service.BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void halterTab_asPlainEmployee_linksOwnHolderOnly() throws Exception {
    String sub = "44444444-4444-4444-4444-444444444444";
    UUID ownHolderId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID foreignHolderId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    // Own holder: its userId equals the caller's sub. Foreign holder: a different userId.
    BankHolderDto own =
        new BankHolderDto(
            ownHolderId, UUID.fromString(sub), "self", true, BigDecimal.ZERO, false, 0L);
    BankHolderDto foreign =
        new BankHolderDto(
            foreignHolderId, UUID.randomUUID(), "other", true, BigDecimal.ZERO, false, 0L);

    // Default any unmatched backend read (sidebar / controller-advice lookups) to null.
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq("/api/v1/bank/holders"), anyTypeRef()))
        .thenReturn(List.of(own, foreign));
    when(backendApiClient.get(
            eq("/api/v1/bank/transfer-fee-rate"), eq(BankTransferFeeRateDto.class)))
        .thenReturn(new BankTransferFeeRateDto(BigDecimal.ZERO));

    mockMvc
        .perform(
            get("/bank/manage")
                .param("tab", "halter")
                .with(
                    oidcLogin()
                        .authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))
                        .idToken(
                            token ->
                                token.subject(sub).claim("preferred_username", "self-username"))))
        .andExpect(status().isOk())
        // The caller's own holder row is a link to its custody history.
        .andExpect(content().string(containsString("/bank/holders/" + ownHolderId)))
        .andExpect(content().string(containsString("data-testid=\"bank-holder-history-link\"")))
        // The foreign holder row is plain text — never a link to its detail page.
        .andExpect(content().string(not(containsString("/bank/holders/" + foreignHolderId))));
  }
}
