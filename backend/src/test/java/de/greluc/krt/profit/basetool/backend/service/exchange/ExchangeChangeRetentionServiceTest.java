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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The change feed's retention purge and its horizon (REQ-XCH-013). */
@SpringBootTest
@Transactional
class ExchangeChangeRetentionServiceTest {

  @Autowired private ExchangeChangeRetentionService retentionService;
  @Autowired private ExchangeChangeRepository changeRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID member;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("retention-member");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
    for (String key : List.of("old-1", "old-2", "fresh")) {
      jdbc.update(
          """
          INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
          VALUES (?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          member,
          key,
          key);
    }
    jdbc.update(
        """
        UPDATE exchange_change SET changed_at = now() - interval '100 days'
        WHERE user_id = ? AND entity_key LIKE 'old-%'
        """,
        member);
  }

  @Test
  void entriesPastTheRetentionGoAndTheHorizonMovesToTheLastOfThem() {
    ExchangeFeedPosition before = retentionService.horizon();
    ExchangeFeedPosition lastOld =
        changeRepository.findAllByUserIdOrderBySeqAsc(member).stream()
            .filter(c -> c.getEntityKey().startsWith("old-"))
            .map(c -> new ExchangeFeedPosition(c.getTx(), c.getSeq()))
            .max(ExchangeFeedPosition::compareTo)
            .orElseThrow();

    int deleted =
        retentionService.purgeOlderThan(Instant.now().minus(Duration.ofDays(90)), Instant.now());

    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(changeRepository.findAllByUserIdOrderBySeqAsc(member))
        .extracting(ExchangeChange::getEntityKey)
        .containsExactly("fresh");
    assertThat(retentionService.horizon()).isGreaterThanOrEqualTo(lastOld).isGreaterThan(before);
    assertThat(lastOld.tx()).isPositive();
  }

  @Test
  void aPurgeWithNothingToDeleteLeavesTheHorizonAlone() {
    retentionService.purgeOlderThan(Instant.now().minus(Duration.ofDays(90)), Instant.now());
    ExchangeFeedPosition horizon = retentionService.horizon();

    int deleted =
        retentionService.purgeOlderThan(Instant.now().minus(Duration.ofDays(90)), Instant.now());

    assertThat(deleted).isZero();
    assertThat(retentionService.horizon()).isEqualTo(horizon);
  }
}
