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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the V168 bank-cascade migration against the real schema: the {@code
 * chk_bank_account_owner_ref} CHECK lets an {@code AREA} account reference its Bereich and the
 * {@code CARTEL} account the Organisationsleitung, while the free-form {@code area_name} form stays
 * valid (REQ-BANK-021).
 *
 * <p>Also asserts one account per org unit. Throwaway rows are removed in a finally block.
 */
@SpringBootTest
class V168BankAreaCartelLinkageMigrationTest {

  @MockitoBean private RoleRepository roleRepository;
  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  @Test
  void v168LinksAreaToBereichAndCartelToOlViaTheFkAndPreservesCardinality() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID olId = UUID.fromString("ffffff68-0000-0000-0000-0000000000a1");
    UUID bereichId = UUID.fromString("ffffff68-0000-0000-0000-0000000000a2");
    UUID areaAcctId = UUID.fromString("ffffff68-0000-0000-0000-0000000000b1");
    UUID cartelAcctId = UUID.fromString("ffffff68-0000-0000-0000-0000000000b2");
    UUID dupAreaAcctId = UUID.fromString("ffffff68-0000-0000-0000-0000000000b3");
    UUID legacyAreaAcctId = UUID.fromString("ffffff68-0000-0000-0000-0000000000b4");

    cleanupAccounts(jdbc, areaAcctId, cartelAcctId, dupAreaAcctId, legacyAreaAcctId);
    cleanup(jdbc, bereichId, olId);
    try {
      insertOrgUnit(jdbc, olId, "ORGANISATIONSLEITUNG", "TEST_V168_OL", "TV8OL", null);
      insertOrgUnit(jdbc, bereichId, "BEREICH", "TEST_V168_BER", "TV8B", olId);

      insertAccount(jdbc, areaAcctId, "KB-V168-1", "Area Profit", "AREA", bereichId, null);
      assertThat(accountCount(jdbc, areaAcctId)).isOne();
      assertThat(orgUnitIdOf(jdbc, areaAcctId)).isEqualTo(bereichId);

      insertAccount(jdbc, cartelAcctId, "KB-V168-2", "Kartell", "CARTEL", olId, null);
      assertThat(orgUnitIdOf(jdbc, cartelAcctId)).isEqualTo(olId);

      assertThatThrownBy(
              () -> insertAccount(jdbc, dupAreaAcctId, "KB-V168-3", "Dup", "AREA", bereichId, null))
          .isInstanceOf(DataAccessException.class);

      insertAccount(jdbc, legacyAreaAcctId, "KB-V168-4", "Legacy", "AREA", null, "Legacy-Bereich");
      assertThat(accountCount(jdbc, legacyAreaAcctId)).isOne();

      assertThatThrownBy(
              () ->
                  insertAccount(
                      jdbc,
                      UUID.fromString("ffffff68-0000-0000-0000-0000000000b9"),
                      "KB-V168-5",
                      "Neither",
                      "AREA",
                      null,
                      null))
          .isInstanceOf(DataAccessException.class);
    } finally {
      cleanupAccounts(jdbc, areaAcctId, cartelAcctId, dupAreaAcctId, legacyAreaAcctId);
      cleanup(jdbc, bereichId, olId);
    }
  }

  private static void insertOrgUnit(
      JdbcTemplate jdbc, UUID id, String kind, String name, String shorthand, UUID parentId) {
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, parent_org_unit_id) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE, ?)",
        id,
        kind,
        name,
        shorthand,
        parentId);
  }

  private static void insertAccount(
      JdbcTemplate jdbc,
      UUID id,
      String accountNo,
      String name,
      String type,
      UUID orgUnitId,
      String areaName) {
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type, status, org_unit_id, area_name)"
            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
        id,
        accountNo,
        name,
        type,
        orgUnitId,
        areaName);
  }

  private static int accountCount(JdbcTemplate jdbc, UUID id) {
    Integer count =
        jdbc.queryForObject("SELECT COUNT(*) FROM bank_account WHERE id = ?", Integer.class, id);
    return count == null ? 0 : count;
  }

  private static UUID orgUnitIdOf(JdbcTemplate jdbc, UUID accountId) {
    return jdbc.queryForObject(
        "SELECT org_unit_id FROM bank_account WHERE id = ?", UUID.class, accountId);
  }

  private static void cleanupAccounts(JdbcTemplate jdbc, UUID... ids) {
    for (UUID id : ids) {
      jdbc.update("DELETE FROM bank_account WHERE id = ?", id);
    }
  }

  /**
   * Deletes the throwaway org units child-first so the self-referential FK never blocks cleanup.
   */
  private static void cleanup(JdbcTemplate jdbc, UUID... idsChildFirst) {
    for (UUID id : idsChildFirst) {
      jdbc.update("DELETE FROM org_unit WHERE id = ?", id);
    }
  }
}
