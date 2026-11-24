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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the wire of the MATERIAL and ITEM create and edit payloads: the observed versions travel as
 * the flat {@code version} and {@code commentVersion} fields and land in their own slots of the
 * pair (REQ-ORDERS-046, REQ-ORDERS-066).
 */
class JobOrderObservedVersionsWireTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void aMaterialPayloadReadsEachFlatVersionIntoItsOwnSlot() {
    CreateJobOrderDto payload =
        json.readValue(
            "{\"materials\":[],\"version\":3,\"commentVersion\":7}", CreateJobOrderDto.class);

    assertThat(payload.observed()).isEqualTo(new JobOrderObservedVersions(3L, 7L));
  }

  @Test
  void anItemPayloadReadsEachFlatVersionIntoItsOwnSlot() {
    CreateJobOrderItemRequestDto payload =
        json.readValue(
            "{\"items\":[],\"commentVersion\":7,\"version\":3}",
            CreateJobOrderItemRequestDto.class);

    assertThat(payload.observed()).isEqualTo(new JobOrderObservedVersions(3L, 7L));
  }

  @Test
  void aCreateWithoutVersionsReadsAnEmptyPair() {
    CreateJobOrderDto payload = json.readValue("{\"materials\":[]}", CreateJobOrderDto.class);

    assertThat(payload.observed()).isEqualTo(new JobOrderObservedVersions(null, null));
  }

  @Test
  void aPayloadWritesThePairAsTheTwoFlatFields() {
    JsonNode written =
        json.valueToTree(
            new CreateJobOrderDto(
                null, null, "Pilot", null, List.of(), new JobOrderObservedVersions(3L, 7L)));

    assertThat(written.get("version").asLong()).isEqualTo(3L);
    assertThat(written.get("commentVersion").asLong()).isEqualTo(7L);
    assertThat(written.has("observed")).isFalse();
  }
}
