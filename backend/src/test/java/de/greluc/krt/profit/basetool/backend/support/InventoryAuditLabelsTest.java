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
 * Unit tests for {@link InventoryAuditLabels#label(InventoryItem)} — the deletion-proof audit
 * subject snapshot. Since V220 the row is catalog-discriminated (REQ-INV-029), so the label's
 * catalog branch must render the game-item name for an item row; without it every reused audit
 * event on item stock would log the em-dash fallback ({@code — @ <location>}) and the audit trail
 * would lose the affected item's identity.
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

  // covers REQ-INV-029 / REQ-AUDIT-001 (item rows render the game-item name, not the em dash)
  @Test
  void label_gameItemRow_rendersGameItemNameAtLocation() {
    // Given a game-item stock row (material == null)
    GameItem drive = new GameItem();
    drive.setId(UUID.randomUUID());
    drive.setName("Quantum Drive");

    // When / Then
    assertEquals(
        "Quantum Drive @ ARC-L1", InventoryAuditLabels.label(row(null, drive, location("ARC-L1"))));
  }

  // covers REQ-AUDIT-001 (material rows keep their historical label byte-identically)
  @Test
  void label_materialRow_rendersMaterialNameAtLocation() {
    // Given a material stock row
    Material steel = new Material();
    steel.setId(UUID.randomUUID());
    steel.setName("Steel");

    // When / Then
    assertEquals(
        "Steel @ Hurston", InventoryAuditLabels.label(row(steel, null, location("Hurston"))));
  }

  // covers REQ-AUDIT-001 (orphaned rows keep a well-formed label)
  @Test
  void label_missingCatalogReferencesAndLocation_fallsBackToEmDashes() {
    // Given an orphaned row with neither catalog reference and no location
    // When / Then — both parts render as em dashes rather than NPE-ing the audit write
    assertEquals("— @ —", InventoryAuditLabels.label(row(null, null, null)));
  }
}
