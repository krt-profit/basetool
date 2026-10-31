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

package de.greluc.krt.profit.basetool.frontend.inventory.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link StockLevelBackendClient} sends to the stock-level endpoints. */
class StockLevelBackendClientTest {

  private static final UUID ID = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");
  private static final UUID UNIT = UUID.fromString("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
  private static final UUID MATERIAL = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

  private BackendClientHarness backend;
  private StockLevelBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new StockLevelBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void readsTheLevelsAndTheMaintainableUnits() {
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.levels()).isEmpty();
    assertThat(client.maintainableUnits()).isEmpty();

    backend.expect("GET", "/api/v1/inventory/stock-levels");
    backend.expect("GET", "/api/v1/inventory/stock-levels/units");
  }

  @Test
  void writesCarryTheBodyAndTheEchoedVersion() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();

    client.create(new StockLevelCreateRequest(UNIT, MATERIAL, null, 5.0, null));
    client.update(ID, new StockLevelUpdateRequest(null, 6.0, 8.0, 2L));
    client.delete(ID, 3L);

    backend.expect(
        "POST",
        "/api/v1/inventory/stock-levels",
        "{\"orgUnitId\":\""
            + UNIT
            + "\",\"materialId\":\""
            + MATERIAL
            + "\",\"qualityTierId\":null,\"minimum\":5.0,\"target\":null}");
    backend.expect(
        "PUT",
        "/api/v1/inventory/stock-levels/" + ID,
        "{\"qualityTierId\":null,\"minimum\":6.0,\"target\":8.0,\"version\":2}");
    backend.expect("DELETE", "/api/v1/inventory/stock-levels/" + ID + "?version=3");
  }
}
