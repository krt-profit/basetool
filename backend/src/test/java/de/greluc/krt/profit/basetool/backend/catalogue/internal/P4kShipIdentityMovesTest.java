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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins which ship types the catalogue moves to another GUID by itself (REQ-P4K-051). */
class P4kShipIdentityMovesTest {

  private static final UUID BASE = UUID.fromString("f6d606c9-b324-4efa-814f-15a59047a6a5");
  private static final UUID EDITION = UUID.fromString("1c5ffd9c-92d5-4922-a0dd-826a2bcebf02");
  private static final UUID DEPARTED = UUID.fromString("d46f4de1-8dc1-473b-9739-88c9ba7e75a6");
  private static final UUID SUCCESSOR = UUID.fromString("395f30ae-f930-4929-b881-d711fde7e70f");
  private static final String CARRACK = "Anvil Carrack";
  private static final String HAMMERHEAD = "Aegis Hammerhead";

  private static final UUID NPC = UUID.fromString("ed098d32-8604-42b9-a6ac-dd2df6f570f7");

  private final Map<UUID, List<P4kShipDto>> catalogue =
      byGuid(
          ship(BASE, CARRACK, "player", null),
          ship(EDITION, CARRACK, "player", BASE),
          ship(SUCCESSOR, HAMMERHEAD, "player", null),
          ship(NPC, HAMMERHEAD, "ai", SUCCESSOR));

  @Test
  void aShipTypeHoldingAnNpcVariantNeverMovesToTheBase() {
    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(row(HAMMERHEAD, NPC)), Set.of()))
        .isEmpty();
  }

  @Test
  void aShipTypeStampedWithAnEditionMovesToTheUnclaimedBaseOfTheSameName() {
    ShipType carrack = row(CARRACK, EDITION);

    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(carrack), Set.of()))
        .singleElement()
        .satisfies(move -> {
          assertThat(move.row()).isSameAs(carrack);
          assertThat(move.baseGuid()).isEqualTo(BASE);
          assertThat(move.variant()).isNotNull();
        });
  }

  @Test
  void aShipTypeWhoseGuidLeftTheCatalogueFollowsThePlayerRecordOfItsInGameName() {
    ShipType hammerhead = row("Hammerhead", DEPARTED);
    hammerhead.setNameFull(HAMMERHEAD);

    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(hammerhead), Set.of()))
        .singleElement()
        .satisfies(move -> {
          assertThat(move.baseGuid()).isEqualTo(SUCCESSOR);
          assertThat(move.variant()).isNull();
        });
  }

  @Test
  void aTargetAnotherShipTypeHoldsMovesNothing() {
    ShipType carrack = row(CARRACK, EDITION);
    ShipType base = row("Carrack", null);
    base.setExternalUuid(BASE);

    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(carrack, base), Set.of()))
        .isEmpty();
  }

  @Test
  void aTargetTwoShipTypesWouldTakeMovesNeither() {
    ShipType first = row(HAMMERHEAD, DEPARTED);
    ShipType second = row("Hammerhead", UUID.fromString("d46f4de1-0000-0000-0000-000000000002"));
    second.setGameName(HAMMERHEAD);

    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(first, second), Set.of()))
        .isEmpty();
  }

  @Test
  void aPartialCatalogueAHiddenRowAnAdminRepairAndAnotherNameMoveNothing() {
    ShipType departed = row(HAMMERHEAD, DEPARTED);
    ShipType hidden = row(CARRACK, EDITION);
    hidden.setP4kHiddenAt(Instant.EPOCH);
    ShipType queued = row(CARRACK, EDITION);
    ShipType expedition = row("Anvil Carrack Expedition", EDITION);
    Map<UUID, List<P4kShipDto>> renamed =
        byGuid(
            ship(BASE, CARRACK, "player", null),
            ship(EDITION, "Anvil Carrack Expedition", "player", BASE));

    assertThat(P4kShipIdentityMoves.find(catalogue, false, List.of(departed), Set.of())).isEmpty();
    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(hidden), Set.of())).isEmpty();
    assertThat(P4kShipIdentityMoves.find(catalogue, true, List.of(queued), Set.of(queued.getId())))
        .isEmpty();
    assertThat(P4kShipIdentityMoves.find(renamed, true, List.of(expedition), Set.of())).isEmpty();
  }

  private static ShipType row(String name, UUID p4kUuid) {
    ShipType row = new ShipType();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setP4kUuid(p4kUuid);
    return row;
  }

  private static Map<UUID, List<P4kShipDto>> byGuid(P4kShipDto... ships) {
    Map<UUID, List<P4kShipDto>> byGuid = new HashMap<>();
    for (P4kShipDto ship : ships) {
      byGuid.put(ship.parsedGuid(), List.of(ship));
    }
    return byGuid;
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
