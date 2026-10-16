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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The change feed's retention purge and its horizon, for the member feeds and the org-stock feed
 * keyed by unit (REQ-XCH-013, REQ-XCH-042). Every test rolls back, so the shared horizon row is
 * left as it was.
 */
@SpringBootTest
@Transactional
class ExchangeChangeRetentionServiceTest {

  @Autowired private ExchangeChangeRetentionService retentionService;
  @Autowired private ExchangeOrgStockService orgStockService;
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
  void orgStockEntriesPastTheRetentionGoWithTheOthersAndMoveTheHorizon() {
    UUID pool = orgUnit();
    UUID location = location();
    UUID old = material();
    UUID fresh = material();
    stock(old, location, pool);
    jdbc.update(
        "UPDATE exchange_change SET changed_at = now() - interval '100 days' WHERE org_unit_id = ?",
        pool);
    stock(fresh, location, pool);
    ExchangeFeedPosition before = retentionService.horizon();
    ExchangeFeedPosition lastOld = lastOrgPosition(pool, old);

    retentionService.purgeOlderThan(Instant.now().minus(Duration.ofDays(90)), Instant.now());

    assertThat(
            jdbc.queryForList(
                "SELECT entity_key FROM exchange_change WHERE org_unit_id = ?", String.class, pool))
        .singleElement()
        .satisfies(key -> assertThat(key).contains("m:" + fresh));
    assertThat(retentionService.horizon()).isGreaterThanOrEqualTo(lastOld).isGreaterThan(before);
  }

  @Test
  void anOrgStockCursorBelowTheHorizonIsExpiredAndOneAtTheHorizonResumes() {
    UUID pool = orgUnit();
    UUID material = material();
    stock(material, location(), pool);
    jdbc.update(
        "UPDATE exchange_change SET changed_at = now() - interval '100 days' WHERE org_unit_id = ?",
        pool);
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, 'MEMBER')",
        member,
        pool);
    retentionService.purgeOlderThan(Instant.now().minus(Duration.ofDays(90)), Instant.now());
    ExchangeFeedPosition horizon = retentionService.horizon();
    ExchangeCaller caller = new ExchangeCaller(member, "retention-client", "K".repeat(43));
    String below =
        ExchangeFeedCursor.feed(new ExchangeFeedPosition(horizon.tx(), horizon.seq() - 1)).format();
    String at = ExchangeFeedCursor.feed(horizon).format();

    assertThatThrownBy(() -> orgStockService.page(caller, below, 10))
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CURSOR_EXPIRED");
              assertThat(e.status()).isEqualTo(HttpStatus.GONE);
            });
    assertThat(orgStockService.page(caller, at, 10).units())
        .extracting(ExchangeOrgUnitDto::id)
        .containsExactly(pool);
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

  /**
   * Returns the feed position of a material's latest org-stock entry in a pool.
   *
   * @param pool the org unit
   * @param material the material
   * @return the position
   */
  private @NotNull ExchangeFeedPosition lastOrgPosition(
      @NotNull UUID pool, @NotNull UUID material) {
    return jdbc.queryForObject(
        "SELECT tx, seq FROM exchange_change WHERE org_unit_id = ? AND entity_key LIKE ?"
            + " ORDER BY tx DESC, seq DESC LIMIT 1",
        (rs, _) -> new ExchangeFeedPosition(rs.getLong(1), rs.getLong(2)),
        pool,
        "%m:" + material + "%");
  }

  /**
   * Inserts a shared stock row of the member in a pool.
   *
   * @param material the material
   * @param location the location
   * @param pool the owning org unit
   */
  private void stock(@NotNull UUID material, @NotNull UUID location, @NotNull UUID pool) {
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id)
        VALUES (?, ?, ?, ?, 1, 1, false, false, ?)
        """,
        UUID.randomUUID(),
        member,
        material,
        location,
        pool);
  }

  /**
   * Seeds an SCU material.
   *
   * @return its id
   */
  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "Retention " + id);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @return its id
   */
  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Retention " + id);
    return id;
  }

  /**
   * Seeds an active squadron.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, FALSE)",
        id,
        "Retention " + id,
        "R" + id.toString().substring(0, 8));
    return id;
  }
}
