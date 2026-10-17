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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the bank read pages send a lost session to the login instead of a fallback. */
class BankPageControllerReauthMvcTest {

  private final BankBackendClient bankClient = mock(BankBackendClient.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = ReauthPageMvc.standalone(new BankPageController(bankClient));
    ReauthPageMvc.signInMember("BANK_EMPLOYEE");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheBookingHistoryRedirectsToLogin() throws Exception {
    when(bankClient.accountBookings(any(), anyInt(), anyInt(), any(), any()))
        .thenThrow(expiredSession());

    mockMvc
        .perform(get("/bank/accounts/" + UUID.randomUUID()).param("fragment", "bookings"))
        .andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheHolderHistoryRedirectsToLogin() throws Exception {
    when(bankClient.holderBookings(any(), anyInt())).thenThrow(expiredSession());

    mockMvc
        .perform(get("/bank/holders/" + UUID.randomUUID()).param("fragment", "holderBookings"))
        .andExpect(redirectsToLogin());
  }
}
