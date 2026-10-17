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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the purchasing pages send a lost session to the login instead of their fallbacks. */
class PurchasingPageControllerReauthMvcTest {

  private final PurchasingBackendClient client = mock(PurchasingBackendClient.class);

  private final PurchasingFeatureState featureState = mock(PurchasingFeatureState.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new PurchasingPageController(
                client,
                mock(SupplyBackendClient.class),
                featureState,
                mock(QualityTierCatalog.class)));
    ReauthPageMvc.signInMember();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheCapabilitiesRedirectsToLogin() throws Exception {
    when(featureState.enabled()).thenReturn(true);
    when(client.state()).thenThrow(expiredSession());

    mockMvc.perform(get("/purchasing/delegates")).andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnThePauseStateRedirectsToLogin() throws Exception {
    when(featureState.enabled()).thenReturn(true);
    when(client.pauseState()).thenThrow(expiredSession());

    mockMvc
        .perform(get("/purchasing/deals").param("fragment", "results"))
        .andExpect(redirectsToLogin());
  }
}
