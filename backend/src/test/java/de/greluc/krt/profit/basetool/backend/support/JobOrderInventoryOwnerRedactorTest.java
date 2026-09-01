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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobOrderInventoryOwnerRedactor} (REQ-ORDERS-029): the three redaction
 * passes must blank owner identity + location while keeping every non-identity field, and must be
 * null-safe.
 */
class JobOrderInventoryOwnerRedactorTest {

  private final JobOrderInventoryOwnerRedactor redactor = new JobOrderInventoryOwnerRedactor();

  @Test
  void redactItemStockGroups_blanksOwnerAndLocation_keepsAmountsAndContext() {
    // covers REQ-ORDERS-029
    UUID entryId = UUID.randomUUID();
    JobOrderItemStockEntryDto entry =
        new JobOrderItemStockEntryDto(
            entryId, 7L, "Alice", UUID.randomUUID(), "Lorville", UUID.randomUUID(), 4L, 3L, true);
    InventoryGameItemReferenceDto gameItem =
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Rifle", "Behring", "WEAPON");
    JobOrderItemStockGroupDto group =
        new JobOrderItemStockGroupDto(gameItem, 5, 2, 3L, List.of(entry));

    List<JobOrderItemStockGroupDto> result = redactor.redactItemStockGroups(List.of(group));

    JobOrderItemStockEntryDto redacted = result.get(0).entries().get(0);
    // Owner/location blanked.
    assertNull(redacted.ownerName(), "ownerName blanked");
    assertNull(redacted.ownerId(), "ownerId blanked");
    assertNull(redacted.location(), "location blanked");
    assertNull(redacted.locationId(), "locationId blanked");
    // Everything else kept.
    assertEquals(entryId, redacted.inventoryEntryId());
    assertEquals(7L, redacted.version());
    assertEquals(4L, redacted.quantity());
    assertEquals(3L, redacted.allocatedQuantity());
    assertTrue(redacted.delivered());
    // Group context untouched.
    assertEquals(gameItem, result.get(0).gameItem());
    assertEquals(5, result.get(0).orderedAmount());
    assertEquals(2, result.get(0).manufacturedAmount());
    assertEquals(3L, result.get(0).allocatedTotal());
  }

  @Test
  void redactMaterialCollection_blanksOwnerAndLocation_keepsMaterialAndQuantities() {
    // covers REQ-ORDERS-029
    UUID entryId = UUID.randomUUID();
    MaterialCollectionEntryDto entry =
        new MaterialCollectionEntryDto(
            entryId,
            3L,
            "Bob",
            UUID.randomUUID(),
            "Area18",
            UUID.randomUUID(),
            "Agricium",
            800.0,
            10.0,
            4.0,
            false);

    List<MaterialCollectionEntryDto> result = redactor.redactMaterialCollection(List.of(entry));

    MaterialCollectionEntryDto redacted = result.get(0);
    assertNull(redacted.ownerName(), "ownerName blanked");
    assertNull(redacted.ownerId(), "ownerId blanked");
    assertNull(redacted.location(), "location blanked");
    assertNull(redacted.locationId(), "locationId blanked");
    assertEquals(entryId, redacted.inventoryEntryId());
    assertEquals(3L, redacted.version());
    assertEquals("Agricium", redacted.materialName());
    assertEquals(800.0, redacted.quality());
    assertEquals(10.0, redacted.quantity());
    assertEquals(4.0, redacted.allocatedQuantity());
    assertFalse(redacted.delivered());
  }

  @Test
  void redactInventoryItems_blanksUserLocationAndOwningSquadron_keepsRest() {
    // covers REQ-ORDERS-029
    UUID id = UUID.randomUUID();
    InventoryGameItemReferenceDto gameItem =
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Rifle", "Behring", "WEAPON");
    InventoryItemDto item =
        new InventoryItemDto(
            id,
            new UserReferenceDto(UUID.randomUUID(), "alice", "Alice", "Alice", 1),
            null,
            gameItem,
            new LocationReferenceDto(UUID.randomUUID(), "Lorville"),
            null,
            4.0,
            false,
            List.of(),
            0.0,
            List.of(),
            0.0,
            "note",
            new SquadronReferenceDto(UUID.randomUUID(), "Alpha", "A"),
            9L,
            null,
            Instant.EPOCH);

    List<InventoryItemDto> result = redactor.redactInventoryItems(List.of(item));

    InventoryItemDto redacted = result.get(0);
    assertNull(redacted.user(), "user (owner) blanked");
    assertNull(redacted.location(), "location blanked");
    assertNull(redacted.owningSquadron(), "owningSquadron blanked");
    // Non-identity fields kept.
    assertEquals(id, redacted.id());
    assertEquals(gameItem, redacted.gameItem());
    assertEquals(4.0, redacted.amount());
    assertEquals("note", redacted.note());
    assertEquals(9L, redacted.version());
    assertEquals(Instant.EPOCH, redacted.createdAt());
  }

  @Test
  void nullAndEmptyInputs_areHandled() {
    // covers REQ-ORDERS-029
    assertNull(redactor.redactItemStockGroups(null));
    assertNull(redactor.redactMaterialCollection(null));
    assertNull(redactor.redactInventoryItems(null));
    assertTrue(redactor.redactItemStockGroups(List.of()).isEmpty());
    assertTrue(redactor.redactMaterialCollection(List.of()).isEmpty());
    assertTrue(redactor.redactInventoryItems(List.of()).isEmpty());
  }
}
