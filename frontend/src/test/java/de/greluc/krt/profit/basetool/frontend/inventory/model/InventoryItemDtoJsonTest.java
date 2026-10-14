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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the backend's Lager row JSON into the mirror record, which carries a second, shorter
 * constructor for rows without a pledge: Jackson must bind through the canonical one (REQ-INV-058).
 */
class InventoryItemDtoJsonTest {

  private static final UUID DONATION = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");

  private static final String ROW_START =
      "{\"id\":\"3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b\",\"amount\":10.0,\"personal\":false,"
          + "\"stolen\":false,\"jobOrderAllocations\":[],\"jobOrderRest\":10.0,"
          + "\"missionAllocations\":[],\"missionRest\":10.0,";

  private static final String ROW_END =
      "\"version\":3,\"canEdit\":true,\"createdAt\":\"2026-09-01T10:00:00Z\"}";

  @Test
  void aRowWithPledgedSlicesBindsThem() {
    String json =
        ROW_START
            + "\"donationAllocations\":[{\"donationId\":\""
            + DONATION
            + "\",\"receivingUnitName\":\"Bereich Profit\",\"amount\":4.0}],"
            + ROW_END;

    InventoryItemDto row = JsonMapper.builder().build().readValue(json, InventoryItemDto.class);

    assertEquals(1, row.donationAllocations().size());
    assertEquals(DONATION, row.donationAllocations().getFirst().donationId());
    assertEquals("Bereich Profit", row.donationAllocations().getFirst().receivingUnitName());
    assertEquals(4.0, row.donationAllocations().getFirst().amount());
    assertEquals(3L, row.version());
  }

  @Test
  void aRowFromAnOlderBackendHasNoSlices() {
    InventoryItemDto row =
        JsonMapper.builder().build().readValue(ROW_START + ROW_END, InventoryItemDto.class);

    assertNull(row.donationAllocations());
    assertTrue(row.canEdit());
  }
}
