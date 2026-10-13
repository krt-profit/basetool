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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the data-transfer statements of {@code V283__create_member_payout_setting_and_blueprint_
 * sharing.sql} against the real schema, over members the old columns describe, and proves that the
 * counts match before and after, that no unrelated member gets a row, that a second run changes
 * nothing, and that the documented rollback statements restore the old columns from the new tables
 * (plan D-17).
 *
 * <p>The statements are read from the migration file itself, so the test cannot drift from what
 * Flyway ran.
 */
@SpringBootTest
@Transactional
class V283MemberSettingsMigrationTest {

  private static final String MIGRATION =
      "db/migration/V283__create_member_payout_setting_and_blueprint_sharing.sql";

  private static final String COUNTS =
      "SELECT (SELECT count(*) FROM app_user WHERE id IN (:ids) AND default_payout_preference IS"
          + " NOT NULL) AS payout_users, (SELECT count(*) FROM member_payout_setting WHERE user_id"
          + " IN (:ids)) AS payout_rows, (SELECT count(*) FROM app_user WHERE id IN (:ids) AND"
          + " share_blueprints_globally) AS sharing_users, (SELECT count(*) FROM"
          + " member_blueprint_sharing WHERE user_id IN (:ids) AND share_globally) AS sharing_rows";

  @Autowired private JdbcTemplate jdbc;

  @Test
  void theDataTransferCopiesExactlyTheMembersTheOldColumnsDescribe() {
    UUID donates = member("DONATE", true);
    UUID payoutsOnly = member("PAYOUT", false);
    UUID sharesOnly = member(null, true);
    UUID untouched = member(null, false);
    List<UUID> all = List.of(donates, payoutsOnly, sharesOnly, untouched);

    runDataTransfer();

    String ids = inList(all);
    assertThat(count("SELECT count(*) FROM member_payout_setting WHERE user_id IN (" + ids + ")"))
        .isEqualTo(
            count(
                "SELECT count(*) FROM app_user WHERE id IN ("
                    + ids
                    + ") AND default_payout_preference IS NOT NULL"))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM member_blueprint_sharing WHERE user_id IN ("
                    + ids
                    + ") AND share_globally"))
        .isEqualTo(
            count(
                "SELECT count(*) FROM app_user WHERE id IN ("
                    + ids
                    + ") AND share_blueprints_globally"))
        .isEqualTo(2);
    assertThat(payoutOf(donates)).isEqualTo("DONATE");
    assertThat(payoutOf(payoutsOnly)).isEqualTo("PAYOUT");
    assertThat(rowCount("member_payout_setting", sharesOnly)).isZero();
    assertThat(rowCount("member_payout_setting", untouched)).isZero();
    assertThat(rowCount("member_blueprint_sharing", untouched)).isZero();
    assertThat(rowCount("member_blueprint_sharing", payoutsOnly)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM member_payout_setting WHERE user_id = ?", Long.class, donates))
        .isZero();
  }

  @Test
  void aSecondRunChangesNothing() {
    UUID member = member("DONATE", true);

    runDataTransfer();
    runDataTransfer();

    assertThat(rowCount("member_payout_setting", member)).isOne();
    assertThat(rowCount("member_blueprint_sharing", member)).isOne();
  }

  @Test
  void theRollbackStatementsRestoreTheOldColumnsFromTheNewTables() {
    UUID member = member("DONATE", false);
    runDataTransfer();
    jdbc.update(
        "UPDATE member_payout_setting SET default_payout_preference = 'PAYOUT' WHERE user_id = ?",
        member);
    jdbc.update(
        "INSERT INTO member_blueprint_sharing (user_id, share_globally) VALUES (?, TRUE)", member);

    jdbc.update(
        "UPDATE app_user u SET default_payout_preference = s.default_payout_preference FROM"
            + " member_payout_setting s WHERE s.user_id = u.id");
    jdbc.update(
        "UPDATE app_user u SET share_blueprints_globally = COALESCE((SELECT b.share_globally FROM"
            + " member_blueprint_sharing b WHERE b.user_id = u.id), FALSE)");

    assertThat(
            jdbc.queryForObject(
                "SELECT default_payout_preference FROM app_user WHERE id = ?",
                String.class,
                member))
        .isEqualTo("PAYOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT share_blueprints_globally FROM app_user WHERE id = ?",
                Boolean.class,
                member))
        .isTrue();
  }

  @Test
  void theCountsQueryOfTheRunbookAgreesAfterTheTransfer() {
    List<UUID> all = List.of(member("DONATE", true), member("PAYOUT", false), member(null, true));

    runDataTransfer();

    var row =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .queryForMap(COUNTS, java.util.Map.of("ids", all));
    assertThat(row.get("payout_users")).isEqualTo(row.get("payout_rows")).isEqualTo(2L);
    assertThat(row.get("sharing_users")).isEqualTo(row.get("sharing_rows")).isEqualTo(2L);
  }

  private void runDataTransfer() {
    for (String statement : migrationStatements()) {
      if (statement.startsWith("INSERT INTO")) {
        jdbc.update(statement);
      }
    }
  }

  private static List<String> migrationStatements() {
    try (var in = new ClassPathResource(MIGRATION).getInputStream()) {
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return Arrays.stream(sql.split(";\\s*\\n"))
          .map(String::strip)
          .filter(s -> !s.isEmpty())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private UUID member(String payout, boolean sharing) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, username, version, default_payout_preference,"
            + " share_blueprints_globally) VALUES (?, ?, 0, ?, ?)",
        id,
        "v283-" + id,
        payout,
        sharing);
    return id;
  }

  private String payoutOf(UUID member) {
    return jdbc.queryForObject(
        "SELECT default_payout_preference FROM member_payout_setting WHERE user_id = ?",
        String.class,
        member);
  }

  private int rowCount(String table, UUID member) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, member);
    return n == null ? 0 : n;
  }

  private int count(String sql) {
    Integer n = jdbc.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

  private static String inList(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));
  }
}
