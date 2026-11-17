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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pairs a ship's old record with the {@code _GS} record that supersedes it (REQ-P4K-053). */
class P4kShipGoldStandardTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final UUID OLD = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1a001");
  private static final UUID GS = UUID.fromString("ab20545b-6dfd-4508-b35c-ff2500b1a002");

  @Test
  void aGsRecordOfTheSameNameSupersedesTheOldOne() {
    Map<UUID, P4kShipDto> superseded =
        P4kShipGoldStandard.supersededBy(
            List.of(
                ship(OLD, "RSI_Constellation_Phoenix", "player", null),
                ship(GS, "RSI_Constellation_Phoenix_GS", "player", null)));

    assertThat(superseded).containsOnlyKeys(OLD);
    assertThat(superseded.get(OLD).parsedGuid()).isEqualTo(GS);
  }

  @Test
  void aGsRecordOfAnotherNameSupersedesNothing() {
    P4kShipDto renamed = ship(GS, "RSI_Constellation_Phoenix_GS", "player", null);

    assertThat(
            P4kShipGoldStandard.supersededBy(
                List.of(
                    ship(OLD, "RSI_Constellation_Phoenix", "player", null),
                    withName(renamed, "RSI Constellation Phoenix Emerald"))))
        .isEmpty();
  }

  @Test
  void aVariantOrANpcRecordIsNeitherSupersededNorSuperseding() {
    assertThat(
            P4kShipGoldStandard.supersededBy(
                List.of(
                    ship(OLD, "RSI_Constellation_Phoenix", "player", GS.toString()),
                    ship(GS, "RSI_Constellation_Phoenix_GS", "player", null))))
        .isEmpty();
    assertThat(
            P4kShipGoldStandard.supersededBy(
                List.of(
                    ship(OLD, "RSI_Constellation_Phoenix", "player", null),
                    ship(GS, "RSI_Constellation_Phoenix_GS", "ai", null))))
        .isEmpty();
  }

  private static P4kShipDto ship(UUID guid, String className, String playability, String base) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("guid", guid.toString())
        .put("className", className)
        .put("playability", playability)
        .put("variantOf", base);
    node.putObject("name").put("en", "RSI Constellation Mk IV Phoenix");
    return MAPPER.treeToValue(node, P4kShipDto.class);
  }

  private static P4kShipDto withName(P4kShipDto ship, String name) {
    ObjectNode node = MAPPER.valueToTree(ship);
    node.putObject("name").put("en", name);
    return MAPPER.treeToValue(node, P4kShipDto.class);
  }
}
