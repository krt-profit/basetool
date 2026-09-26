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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InventoryAuditLabels#label(InventoryItem)}, the audit subject snapshot,
 * including the game-item name for an item-catalog row (REQ-INV-029).
 */
class InventoryAuditLabelsTest {

  /**
   * Builds an inventory row at the given location with the given catalog references; every other
   * field is irrelevant to the label.
   *
   * @param material the material reference, or {@code null}
   * @param gameItem the game-item reference, or {@code null}
   * @param location the storage location, or {@code null}
   * @return the assembled row
   */
  private static InventoryItem row(Material material, GameItem gameItem, Location location) {
    InventoryItem item = new InventoryItem();
    item.setMaterial(material);
    item.setGameItem(gameItem);
    item.setLocation(location);
    return item;
  }

  private static Location location(String name) {
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName(name);
    return location;
  }

  @Test
  void label_gameItemRow_rendersGameItemNameAtLocation() {
    GameItem drive = new GameItem();
    drive.setId(UUID.randomUUID());
    drive.setName("Quantum Drive");

    assertEquals(
        "Quantum Drive @ ARC-L1", InventoryAuditLabels.label(row(null, drive, location("ARC-L1"))));
  }

  @Test
  void label_materialRow_rendersMaterialNameAtLocation() {
    Material steel = new Material();
    steel.setId(UUID.randomUUID());
    steel.setName("Steel");

    assertEquals(
        "Steel @ Hurston", InventoryAuditLabels.label(row(steel, null, location("Hurston"))));
  }

  @Test
  void label_missingCatalogReferencesAndLocation_fallsBackToEmDashes() {
    assertEquals("— @ —", InventoryAuditLabels.label(row(null, null, null)));
  }
}
