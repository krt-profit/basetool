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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.redirectsToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/** Proves the hangar tabs send a lost session to the login instead of their fallbacks. */
class HangarPageControllerReauthMvcTest {

  private final HangarBackendClient client = mock(HangarBackendClient.class);

  private final CachedCatalogListLoader catalogs = mock(CachedCatalogListLoader.class);

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        ReauthPageMvc.standalone(
            new HangarPageController(client, new ParallelPageLoader(), catalogs));
    ReauthPageMvc.signInMember();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anExpiredSessionOnTheUnitOverviewRedirectsToLogin() throws Exception {
    when(client.squadronOverview(anyInt(), anyInt(), any())).thenThrow(expiredSession());

    mockMvc
        .perform(get("/hangar/squadron").param("fragment", "results"))
        .andExpect(redirectsToLogin());
  }

  @Test
  void anExpiredSessionOnTheOwnerOptionsRedirectsToLogin() throws Exception {
    when(catalogs.loadPageContent(any(), any(), anyString())).thenAnswer(_ -> new ArrayList<>());
    when(client.pickableOrgUnits()).thenThrow(expiredSession());

    mockMvc.perform(get("/hangar").param("fragment", "results")).andExpect(redirectsToLogin());
  }
}
