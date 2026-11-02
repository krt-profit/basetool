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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * The low-stock badge read marks exactly the subjects of the low levels and computes only the pool
 * fill: it never loads the open orders' requirement graphs (REQ-INV-068).
 */
@SpringBootTest
@Transactional
class StockLevelLowSubjectsTest {

  private static final UUID GOOD_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");

  @Autowired private StockLevelService stockLevelService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private EntityManager entityManager;

  private StockLevelTestData data;
  private UUID unit;
  private UUID holder;
  private UUID location;
  private UUID lowMaterial;
  private UUID sufficientMaterial;
  private UUID lowItem;

  @BeforeEach
  void seed() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("low-subjects")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    data = new StockLevelTestData(jdbc, userRepository);
    unit = data.orgUnit("SQUADRON", null);
    holder = data.user("low-subjects");
    location = data.location();
    lowMaterial = data.material();
    sufficientMaterial = data.material();
    lowItem = data.gameItem();
    data.level(unit, lowMaterial, null, null, 10.0, 10.0);
    data.level(unit, sufficientMaterial, null, null, 2.0, 2.0);
    data.level(unit, null, lowItem, null, 3.0, 3.0);
    data.setMaterialStock(holder, unit, location, lowMaterial, 700, 4.0);
    data.setMaterialStock(holder, unit, location, sufficientMaterial, 700, 5.0);
    requestingOrder(lowMaterial, 6.0);
    requestingOrder(sufficientMaterial, 50.0);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theBadgeSubjectsEqualThoseOfTheLowLevelsOfTheFullRead() {
    List<StockLevelDto> low = stockLevelService.list(null, true, null);

    LowStockSubjects subjects = stockLevelService.lowSubjects();

    assertThat(subjects.materialIds())
        .containsExactly(lowMaterial)
        .isEqualTo(idsOf(low, StockLevelDto::material, MaterialReferenceDto::id));
    assertThat(subjects.gameItemIds())
        .containsExactly(lowItem)
        .isEqualTo(idsOf(low, StockLevelDto::gameItem, GameItemReferenceDto::id));
  }

  @Test
  void theBadgeReadLoadsNoOpenOrderAndIssuesFewerStatementsThanTheFullRead() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    stats.clear();
    entityManager.clear();
    stockLevelService.list(null, true, null);
    long fullStatements = stats.getPrepareStatementCount();
    long fullOrderLoads = orderLoads(stats);

    stats.clear();
    entityManager.clear();
    stockLevelService.lowSubjects();
    long badgeStatements = stats.getPrepareStatementCount();
    long badgeOrderLoads = orderLoads(stats);

    assertThat(fullOrderLoads).as("the full read loads the open orders").isPositive();
    assertThat(badgeOrderLoads).as("the badge read loads no order").isZero();
    assertThat(badgeStatements).isLessThan(fullStatements);
  }

  private static long orderLoads(@NotNull Statistics stats) {
    return stats.getEntityStatistics(JobOrder.class.getName()).getLoadCount();
  }

  private static <T> @NotNull Set<UUID> idsOf(
      @NotNull List<StockLevelDto> levels,
      @NotNull Function<StockLevelDto, T> subject,
      @NotNull Function<T, UUID> id) {
    return levels.stream()
        .map(subject)
        .filter(Objects::nonNull)
        .map(id)
        .collect(Collectors.toUnmodifiableSet());
  }

  private void requestingOrder(@NotNull UUID subject, double amount) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        unit,
        unit);
    jdbc.update(
        """
        INSERT INTO job_order_material (id, job_order_id, material_id, amount, created_at,
                                        updated_at, version, quality_tier_id)
        VALUES (?, ?, ?, ?, now(), now(), 0, ?)
        """,
        UUID.randomUUID(),
        order,
        subject,
        amount,
        GOOD_TIER);
  }
}
