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

/**
 * Pins the requests {@link CatalogueListBackendClient} sends: hidden rows, name sort, the page and
 * size, and the search only when one is given, strictly encoded (REQ-ADMIN-004).
 */
class CatalogueListBackendClientTest {

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":25,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private CatalogueListBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new CatalogueListBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void eachListReadsOnePageWithTheOptionalSearch() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.materials(null, 0, 25);
    client.materials("Alu & ore", 2, 50);
    client.locations("Lor", 1, 100);
    client.shipTypes(null, 3, 25);

    backend.expect("GET", "/api/v1/materials?includeHidden=true&sort=name,asc&page=0&size=25");
    backend.expect(
        "GET",
        "/api/v1/materials?includeHidden=true&sort=name,asc&page=2&size=50&query=Alu%20%26%20ore");
    backend.expect(
        "GET", "/api/v1/locations?includeHidden=true&sort=name,asc&page=1&size=100&query=Lor");
    backend.expect("GET", "/api/v1/ship-types?includeHidden=true&sort=name,asc&page=3&size=25");
  }
}
