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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that after V429 the org-stock feed names a shared row for the row's own write and for
 * the write of its earmarks, names no row for a lot-level re-announce, and keeps one signature of
 * {@code exchange_record_org_change} (REQ-PATCH-057).
 */
@SpringBootTest
class V429ExchangeOrgEntryMigrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void theOrgStockFeedNamesTheRowForItsOwnWritesOnly() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> {
      UUID unit = orgUnit();
      UUID row = sharedRow(holder(), unit);
      assertThat(entriesNamed(unit)).containsExactly(row.toString());

      UUID mission = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
      jdbc.update(
          """
          INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,
                                                         version, created_at, updated_at)
          VALUES (?, ?, ?, 1, 0, now(), now())
          """,
          UUID.randomUUID(),
          row,
          mission);
      assertThat(entriesNamed(unit)).containsExactly(row.toString(), row.toString());

      jdbc.update("UPDATE mission SET name = name || ' renamed' WHERE id = ?", mission);
      jdbc.update("UPDATE org_unit SET name = name || ' renamed' WHERE id = ?", unit);
      assertThat(entriesNamed(unit)).containsExactly(row.toString(), row.toString(), null, null);

      status.setRollbackOnly();
    });
  }

  @Test
  void theOrgChangeRecorderHasOneSignature() {
    assertThat(
            jdbc.queryForList(
                "SELECT pg_get_function_identity_arguments(oid) FROM pg_proc"
                    + " WHERE proname = 'exchange_record_org_change'",
                String.class))
        .containsExactly("p_unit uuid, p_key character varying, p_entry uuid");
  }

  /**
   * Lists the row each org-stock feed entry of a unit names, in feed order.
   *
   * @param unit the unit
   * @return the named rows, {@code null} for an entry that names none
   */
  private @NotNull List<String> entriesNamed(@NotNull UUID unit) {
    return jdbc.queryForList(
        "SELECT CAST(org_entry_id AS text) FROM exchange_change WHERE org_unit_id = ?"
            + " ORDER BY tx, seq",
        String.class,
        unit);
  }

  /**
   * Inserts an active special command.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE)",
        id,
        "V429 pool " + id,
        "V" + id.toString().substring(0, 8));
    return id;
  }

  /**
   * Persists an active member.
   *
   * @return the member's id
   */
  private @NotNull UUID holder() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("v429-holder-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Inserts one SCU of a holder's shared stock in a unit's pool.
   *
   * @param holder the holder
   * @param unit the unit
   * @return the row's id
   */
  private @NotNull UUID sharedRow(@NotNull UUID holder, @NotNull UUID unit) {
    UUID material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "V429 ore " + material);
    UUID location = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, location, "V429 place " + location);
    UUID row = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, version, user_id, material_id, location_id, quality,
                                    amount, personal, stolen, owning_org_unit_id)
        VALUES (?, 0, ?, ?, ?, 1, 2, false, false, ?)
        """,
        row,
        holder,
        material,
        location,
        unit);
    return row;
  }
}
