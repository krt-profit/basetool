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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins which stored ship type stands for which catalogue ship (REQ-P4K-050). */
class P4kShipCoverageTest {

  private static final UUID BASE = UUID.fromString("f6d606c9-b324-4efa-814f-15a59047a6a5");
  private static final UUID EDITION = UUID.fromString("1c5ffd9c-92d5-4922-a0dd-826a2bcebf02");
  private static final UUID NPC = UUID.fromString("2c6403a5-0000-0000-0000-000000000001");
  private static final UUID LONE_NPC = UUID.fromString("2c6403a5-0000-0000-0000-000000000002");
  private static final UUID UNCLEAR = UUID.fromString("2c6403a5-0000-0000-0000-000000000003");
  private static final String NAME = "Anvil Carrack";

  private final P4kShipCoverage coverage =
      P4kShipCoverage.of(
          List.of(
              ship(BASE, NAME, "player", null),
              ship(EDITION, NAME, "player", BASE),
              ship(NPC, NAME, "ai", BASE),
              ship(LONE_NPC, "Anvil HM6 Centurion", "ai", null),
              ship(UNCLEAR, NAME, null, null)));

  @Test
  void aVariantIsCoveredByTheShipTypeOfItsGroupBaseOfTheSameName() {
    assertThat(coverage.covers(EDITION, row(NAME, BASE))).isTrue();
  }

  @Test
  void aVariantIsCoveredByAShipTypeHoldingASiblingOfTheSameName() {
    assertThat(coverage.covers(NPC, row(NAME, EDITION))).isTrue();
  }

  @Test
  void theGroupBaseIsNeverCoveredByAShipTypeHoldingOneOfItsVariants() {
    assertThat(coverage.covers(BASE, row(NAME, EDITION))).isFalse();
  }

  @Test
  void aShipNoPlayerOwnsIsCoveredByAnyShipTypeOfItsName() {
    assertThat(coverage.covers(LONE_NPC, row("Anvil HM6 Centurion", null))).isTrue();
  }

  @Test
  void anotherNameUnclearPlayabilityAndAnUnknownGuidAreNeverCovered() {
    assertThat(coverage.covers(EDITION, row("Anvil Carrack Expedition", BASE))).isFalse();
    assertThat(coverage.covers(UNCLEAR, row(NAME, null))).isFalse();
    assertThat(coverage.covers(UUID.randomUUID(), row(NAME, BASE))).isFalse();
  }

  private static ShipType row(String name, UUID p4kUuid) {
    ShipType row = new ShipType();
    row.setName(name);
    row.setP4kUuid(p4kUuid);
    return row;
  }

  private static P4kShipDto ship(UUID guid, String name, String playability, UUID variantOf) {
    return new P4kShipDto(
        guid.toString(),
        "CLASS_" + guid,
        null,
        new P4kLocalisedText(null, name, null),
        null,
        null,
        playability,
        variantOf == null ? null : variantOf.toString(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
