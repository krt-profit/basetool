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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeChange;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A running writer holds the feed's watermark back, so an entry it commits later can never land
 * behind a position a reader has already passed (REQ-XCH-013, ADR-0224).
 */
@SpringBootTest
class ExchangeChangeWatermarkIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeChangeRepository changeRepository;

  private UUID member;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("watermark-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
  }

  @Test
  void anEarlierWriterThatCommitsLateStaysAheadOfTheWatermark() throws Exception {
    try (Connection slow = dataSource.getConnection();
        Connection fast = dataSource.getConnection()) {
      slow.setAutoCommit(false);
      fast.setAutoCommit(false);
      long slowTx = insert(slow, "slow");
      long fastTx = insert(fast, "fast");
      fast.commit();

      long watermark = changeRepository.watermark();
      ExchangeChange fastEntry = only("fast");

      assertThat(slowTx).isLessThan(fastTx);
      assertThat(watermark).isLessThanOrEqualTo(slowTx);
      assertThat(fastEntry.getTx()).isEqualTo(fastTx).isGreaterThanOrEqualTo(watermark);
      assertThat(changeRepository.findAllByUserIdOrderBySeqAsc(member))
          .extracting(ExchangeChange::getEntityKey)
          .containsExactly("fast");

      slow.commit();
    }

    assertThat(changeRepository.watermark()).isGreaterThan(only("fast").getTx());
    assertThat(only("slow").getSeq()).isLessThan(only("fast").getSeq());
  }

  /**
   * Inserts a blueprint on a connection and returns that connection's transaction id.
   *
   * @param connection the connection, in a transaction
   * @param productKey the product key
   * @return the transaction id
   * @throws Exception if a statement fails
   */
  private long insert(@NotNull Connection connection, @NotNull String productKey) throws Exception {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
                + " VALUES (?, ?, ?, ?)")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, member);
      insert.setString(3, productKey);
      insert.setString(4, productKey);
      insert.executeUpdate();
    }
    try (PreparedStatement tx =
            connection.prepareStatement(
                "SELECT CAST(CAST(pg_current_xact_id() AS text) AS bigint)");
        ResultSet row = tx.executeQuery()) {
      row.next();
      return row.getLong(1);
    }
  }

  /**
   * Returns the member's single entry for a key.
   *
   * @param key the entity key
   * @return the entry
   */
  private @NotNull ExchangeChange only(@NotNull String key) {
    return changeRepository.findAllByUserIdOrderBySeqAsc(member).stream()
        .filter(c -> c.getEntityKey().equals(key))
        .findFirst()
        .orElseThrow();
  }
}
