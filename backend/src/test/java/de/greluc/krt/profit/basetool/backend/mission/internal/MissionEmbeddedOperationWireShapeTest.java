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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionOperationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionOperationSummary;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationDto;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationStatus;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the wire shape of the operation a mission DTO embeds: the field names, their order and their
 * values, whatever Java type carries them (plan §7.6 P4-4).
 */
class MissionEmbeddedOperationWireShapeTest {

  private static final UUID OPERATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private static final UUID UNIT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final String EXPECTED_JSON =
      "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Op\","
          + "\"description\":\"Desc\",\"status\":\"ACTIVE\","
          + "\"owningSquadron\":{\"id\":\"22222222-2222-2222-2222-222222222222\","
          + "\"name\":\"Unit\",\"shorthand\":\"U\"},\"version\":3,"
          + "\"createdAt\":\"2026-10-01T10:00:00Z\",\"updatedAt\":\"2026-10-02T10:00:00Z\","
          + "\"payoutPreliminary\":null}";

  private static final String EXPECTED_ORDER =
      "id,name,description,status,owningSquadron,version,createdAt,updatedAt,payoutPreliminary";

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void theEmbeddedOperationKeepsItsFieldNamesOrderAndValues() {
    MissionListDto row =
        new MissionListDto(
            UUID.randomUUID(),
            "Mission",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            MissionOperationDto.of(summary()),
            null,
            null,
            0L,
            false,
            1L);

    String json = mapper.writeValueAsString(row);

    assertThat(mapper.readTree(json).get("operation").toString()).isEqualTo(EXPECTED_JSON);
  }

  @Test
  void theEmbeddedOperationHasTheComponentsOfTheOperationEndpointInTheSameOrder() {
    RecordComponent list = component(MissionListDto.class, "operation");
    RecordComponent full = component(MissionDto.class, "operation");

    assertThat(full.getType()).isEqualTo(list.getType());
    assertThat(names(list.getType())).isEqualTo(EXPECTED_ORDER);
    assertThat(names(OperationDto.class)).isEqualTo(EXPECTED_ORDER);
  }

  @Test
  void anOwnerlessOperationEmbedsNoSquadron() {
    MissionOperationDto dto =
        MissionOperationDto.of(
            new MissionOperationSummary(
                OPERATION_ID, "Op", null, "PLANNED", null, null, null, 0L, null, null));

    assertThat(dto.owningSquadron()).isNull();
    assertThat(dto.status()).isEqualTo("PLANNED");
    assertThat(dto.payoutPreliminary()).isNull();
  }

  private static MissionOperationSummary summary() {
    return new MissionOperationSummary(
        OPERATION_ID,
        "Op",
        "Desc",
        OperationStatus.ACTIVE.name(),
        UNIT_ID,
        "Unit",
        "U",
        3L,
        Instant.parse("2026-10-01T10:00:00Z"),
        Instant.parse("2026-10-02T10:00:00Z"));
  }

  private static RecordComponent component(Class<?> type, String name) {
    return Arrays.stream(type.getRecordComponents())
        .filter(c -> c.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static String names(Class<?> type) {
    return String.join(
        ",", Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
  }
}
