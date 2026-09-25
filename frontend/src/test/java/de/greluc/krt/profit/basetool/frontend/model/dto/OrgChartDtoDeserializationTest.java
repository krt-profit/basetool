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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the org-chart contract by deserializing a {@code GET /api/v1/org-chart} payload into the
 * nested {@link OrgChartDto} tree with the same Jackson 3 {@link JsonMapper} the {@code
 * BackendApiClient} uses.
 */
class OrgChartDtoDeserializationTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final String SAMPLE_JSON =
      """
      {
        "areaLeadership": {
          "lead": {"positionId":"00000000-0000-0000-0000-0000000000a1","positionType":"AREA_LEAD",
                   "userId":"00000000-0000-0000-0000-0000000000b1","userName":"Boss","sortIndex":0,"version":3},
          "commanders": [],
          "coordinators": [
            {"positionId":"00000000-0000-0000-0000-0000000000a2","positionType":"AREA_COORDINATOR",
             "userId":"00000000-0000-0000-0000-0000000000b2","userName":"Coordinator","sortIndex":0,"version":0}
          ],
          "operators": []
        },
        "squadrons": [
          {
            "orgUnitId":"00000000-0000-0000-0000-000000000001","name":"IRIDIUM","shorthand":"IRI",
            "lead":{"positionId":"00000000-0000-0000-0000-0000000000a3","positionType":"SQUADRON_LEAD",
                    "userId":"00000000-0000-0000-0000-0000000000b3","userName":"Lead","sortIndex":0,"version":1},
            "commands":[
              {
                "positionId":"00000000-0000-0000-0000-0000000000a4","name":"Alpha","version":2,"sortIndex":0,
                "kommandoGroupId":"00000000-0000-0000-0000-0000000000c4",
                "leaderUserId":"00000000-0000-0000-0000-0000000000b4","leaderUserName":"Cmd",
                "deputy":null,
                "ensigns":[
                  {"positionId":"00000000-0000-0000-0000-0000000000a5","positionType":"ENSIGN",
                   "userId":"00000000-0000-0000-0000-0000000000b5","userName":"Ensign","sortIndex":0,"version":0}
                ]
              },
              {
                "positionId":"00000000-0000-0000-0000-0000000000a7","name":null,"version":0,"sortIndex":1,
                "leaderUserId":null,"leaderUserName":null,"deputy":null,"ensigns":[]
              }
            ],
            "directEnsigns":[],
            "canAddCommand":true,
            "canAddEnsign":false
          }
        ],
        "specialCommands":[
          {
            "orgUnitId":"00000000-0000-0000-0000-000000000002","name":"Alpha SK","shorthand":"ASK",
            "commanders":[
              {"positionId":"00000000-0000-0000-0000-0000000000a6","positionType":"SK_COMMANDER",
               "userId":"00000000-0000-0000-0000-0000000000b6","userName":"SkLead","sortIndex":0,"version":0}
            ],
            "canAddCommander":true
          }
        ]
      }
      """;

  @Test
  void deserializesNestedRecordTree() {
    OrgChartDto chart = MAPPER.readValue(SAMPLE_JSON, OrgChartDto.class);

    assertNotNull(chart.areaLeadership().lead());
    assertEquals("AREA_LEAD", chart.areaLeadership().lead().positionType());
    assertEquals(3L, chart.areaLeadership().lead().version());
    assertEquals(1, chart.areaLeadership().coordinators().size());
    assertTrue(chart.areaLeadership().commanders().isEmpty());

    assertEquals(1, chart.squadrons().size());
    SquadronChartDto squadron = chart.squadrons().getFirst();
    assertEquals("IRIDIUM", squadron.name());
    assertEquals("SQUADRON_LEAD", squadron.lead().positionType());
    assertTrue(squadron.canAddCommand());
    assertFalse(squadron.canAddEnsign());

    assertEquals(2, squadron.commands().size());
    CommandChartDto command = squadron.commands().getFirst();
    assertEquals("Alpha", command.name());
    assertEquals("Cmd", command.leaderUserName());
    assertEquals(
        java.util.UUID.fromString("00000000-0000-0000-0000-0000000000c4"),
        command.kommandoGroupId(),
        "a group-linked Kommando decodes its kommando_group id (drives the read-only chart"
            + " render)");
    assertNull(command.deputy());
    assertEquals(1, command.ensigns().size());
    assertEquals("ENSIGN", command.ensigns().getFirst().positionType());
    CommandChartDto leaderless = squadron.commands().get(1);
    assertNull(leaderless.name(), "an unnamed Kommando decodes a null name");
    assertNull(leaderless.leaderUserId(), "a leaderless Kommando decodes a null holder");
    assertNull(
        leaderless.kommandoGroupId(), "a legacy chart-only Kommando decodes a null group link");

    assertEquals(1, chart.specialCommands().size());
    assertEquals(
        "SK_COMMANDER", chart.specialCommands().getFirst().commanders().getFirst().positionType());
    assertTrue(chart.specialCommands().getFirst().canAddCommander());
  }
}
