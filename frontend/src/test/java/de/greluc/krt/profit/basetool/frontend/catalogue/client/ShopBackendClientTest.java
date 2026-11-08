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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the shop list request of {@link ShopBackendClient}: an absent filter is left out. */
class ShopBackendClientTest {

  private static final String BASE = "/api/v1/catalog/admin/shops";

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":25,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private ShopBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new ShopBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void theShopList_sendsOnlyTheFiltersGiven() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.shops(null, null, false, 0, 25);
    client.shops("Stanton", "Cubby & Blast", true, 3, 25);

    backend.expect("GET", BASE + "?page=0&size=25&unlinkedOnly=false");
    backend.expect(
        "GET",
        BASE + "?page=3&size=25&unlinkedOnly=true&starSystem=Stanton&query=Cubby%20%26%20Blast");
  }
}
