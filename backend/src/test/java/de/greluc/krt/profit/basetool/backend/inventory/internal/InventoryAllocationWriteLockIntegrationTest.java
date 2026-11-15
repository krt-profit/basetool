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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An allocation write's forced version increment covers the stock entry alone (REQ-INV-027): the
 * entry's material keeps its version, also a material that carries none. Writes commit.
 */
@SpringBootTest
class InventoryAllocationWriteLockIntegrationTest {

  /** The version the material of the first test starts at. */
  private static final long MATERIAL_VERSION = 3L;

  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID holder;
  private UUID material;
  private UUID location;
  private UUID entry;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("allocation-lock-" + UUID.randomUUID());
    holder = userRepository.saveAndFlush(user).getId();
    material = UUID.randomUUID();
    location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "Allocation lock " + location);
    entry = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM inventory_item WHERE id = ?", entry);
    jdbc.update("DELETE FROM app_user WHERE id = ?", holder);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    TestPlaces.deleteLocation(jdbc, location);
  }

  @Test
  void anAllocationWriteBumpsTheEntrysVersionAndLeavesItsMaterialsVersionAlone() {
    seed(MATERIAL_VERSION);

    loadForAllocationWriteAndCommit();

    assertThat(version("inventory_item", entry)).isEqualTo(1L);
    assertThat(version("material", material)).isEqualTo(MATERIAL_VERSION);
  }

  @Test
  void anAllocationWriteOnAnEntryWhoseMaterialCarriesNoVersionCommits() {
    seed(null);

    loadForAllocationWriteAndCommit();

    assertThat(version("inventory_item", entry)).isEqualTo(1L);
    assertThat(version("material", material)).isNull();
  }

  /**
   * Loads the entry the way an allocation write does, reads its material as the write's checks do,
   * and commits.
   */
  private void loadForAllocationWriteAndCommit() {
    String name =
        transactionTemplate.execute(_ -> {
          InventoryItem item =
              inventoryItemRepository.findByIdForAllocationWrite(entry).orElseThrow();
          return Objects.requireNonNull(item.getMaterial()).getName();
        });
    assertThat(name).isEqualTo(materialName());
  }

  /**
   * Seeds the material at a version and an entry of it at version 0.
   *
   * @param materialVersion the material's version, or {@code null} for a row without one
   */
  private void seed(@Nullable Long materialVersion) {
    jdbc.update(
        """
        INSERT INTO material (id, version, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        materialVersion,
        materialName());
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, version)
        VALUES (?, ?, ?, ?, 1, 10, false, false, 0)
        """,
        entry,
        holder,
        material,
        location);
  }

  /**
   * Names the seeded material after its id, so parallel runs never collide on the unique name.
   *
   * @return the material's name
   */
  private @NotNull String materialName() {
    return "Allocation lock " + material;
  }

  /**
   * Reads a row's committed version.
   *
   * @param table the table, a constant of this test
   * @param id the row's id
   * @return the version, or {@code null} when the row carries none
   */
  private @Nullable Long version(@NotNull String table, @NotNull UUID id) {
    return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id = ?", Long.class, id);
  }
}
