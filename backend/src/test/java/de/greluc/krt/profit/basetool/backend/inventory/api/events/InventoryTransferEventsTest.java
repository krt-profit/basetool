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

package de.greluc.krt.profit.basetool.backend.inventory.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the Lager transfer notification events and the wording of their lots (REQ-INV-055).
 */
class InventoryTransferEventsTest {

  private static final UUID RECIPIENT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID ROW = UUID.randomUUID();

  @Test
  void anScuLotNamesAmountMaterialQualityAndLocation() {
    assertThat(new TransferredLot("Quantanium", 12.0, false, 800, "Area18").format())
        .isEqualTo("12 SCU Quantanium (Q800) in Area18");
  }

  @Test
  void aPieceLotCountsPiecesAndKeepsFractionalScuAmounts() {
    assertThat(new TransferredLot("Medpen", 3.0, true, 500, "Lorville").format())
        .isEqualTo("3× Medpen (Q500) in Lorville");
    assertThat(new TransferredLot("Laranite", 12.345, false, 640, "Area18").format())
        .isEqualTo("12.345 SCU Laranite (Q640) in Area18");
  }

  @Test
  void aLotWithoutQualityOrLocationLeavesThemOut() {
    assertThat(new TransferredLot("Medpen", 2.0, true, null, null).format()).isEqualTo("2× Medpen");
    assertThat(new TransferredLot("Medpen", 2.0, true, null, " ").format()).isEqualTo("2× Medpen");
  }

  @Test
  void theListJoinsLotsAndEndsWithAnEllipsisBeyondTheLimit() {
    List<TransferredLot> two =
        List.of(
            new TransferredLot("Agricium", 1.0, false, 100, "A"),
            new TransferredLot("Gold", 2.0, false, 200, "B"));
    assertThat(TransferredLot.formatAll(two))
        .isEqualTo("1 SCU Agricium (Q100) in A; 2 SCU Gold (Q200) in B");

    List<TransferredLot> many = new ArrayList<>();
    IntStream.range(0, TransferredLot.MAX_LISTED + 3)
        .forEach(i -> many.add(new TransferredLot("Ore" + i, 1.0, false, null, null)));
    String joined = TransferredLot.formatAll(many);
    assertThat(joined).startsWith("1 SCU Ore0; ").endsWith("; …");
    assertThat(joined).contains("Ore" + (TransferredLot.MAX_LISTED - 1));
    assertThat(joined).doesNotContain("Ore" + TransferredLot.MAX_LISTED);
  }

  @Test
  void theNewOwnersEventIsDirectedAtThemAndRendersActorCountAndLots() {
    InventoryTransferredToUserEvent event =
        new InventoryTransferredToUserEvent(
            RECIPIENT,
            ACTOR,
            "Alice",
            ROW,
            List.of(new TransferredLot("Gold", 4.0, false, 900, "Lorville")));

    assertThat(event.eventType()).isEqualTo(NotificationEventType.INVENTORY_TRANSFERRED_TO_USER);
    assertThat(event.contextRecipientUserId()).isEqualTo(RECIPIENT);
    assertThat(event.actorSub()).isEqualTo(ACTOR);
    assertThat(event.entityType()).isEqualTo("INVENTORY_ITEM");
    assertThat(event.entityId()).isEqualTo(ROW);
    assertThat(event.contextOrgUnits()).isEmpty();
    assertThat(event.resolvesNotificationTypes()).isEmpty();
    assertThat(event.renderParams())
        .containsExactly(
            Map.entry("actor", "Alice"),
            Map.entry("count", "1"),
            Map.entry("lots", "4 SCU Gold (Q900) in Lorville"));
  }

  @Test
  void thePreviousOwnersEventAlsoNamesTheNewOwner() {
    InventoryTransferredFromUserEvent event =
        new InventoryTransferredFromUserEvent(
            RECIPIENT,
            ACTOR,
            "Carol",
            "Bob",
            ROW,
            List.of(
                new TransferredLot("Gold", 4.0, false, 900, "Area18"),
                new TransferredLot("Medpen", 2.0, true, null, "Area18")));

    assertThat(event.eventType()).isEqualTo(NotificationEventType.INVENTORY_TRANSFERRED_FROM_USER);
    assertThat(event.contextRecipientUserId()).isEqualTo(RECIPIENT);
    assertThat(event.actorSub()).isEqualTo(ACTOR);
    assertThat(event.entityType()).isEqualTo("INVENTORY_ITEM");
    assertThat(event.renderParams())
        .containsEntry("actor", "Carol")
        .containsEntry("newOwner", "Bob")
        .containsEntry("count", "2")
        .containsEntry("lots", "4 SCU Gold (Q900) in Area18; 2× Medpen in Area18");
  }

  @Test
  void theEventsKeepAnImmutableCopyOfTheirLots() {
    List<TransferredLot> lots = new ArrayList<>();
    lots.add(new TransferredLot("Gold", 1.0, false, null, null));
    InventoryTransferredToUserEvent event =
        new InventoryTransferredToUserEvent(RECIPIENT, ACTOR, "Alice", ROW, lots);
    lots.clear();

    assertThat(event.lots()).hasSize(1);
    assertThatThrownBy(() -> event.lots().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
