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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationRef;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterises the exchange's stock write path on PostgreSQL, at the level of the rows it leaves
 * behind (REQ-XCH-016): the rows of a lot are read after earlier steps of the same batch changed
 * them in place, and what is booked out, flipped or refused must stay exactly what it is.
 *
 * <p>The test reads and seeds through SQL only, so it describes the behaviour independently of the
 * Lager row's Java type and runs unchanged before and after that type changes.
 */
@SpringBootTest
@Transactional
class ExchangeStockWritePathCharacterisationTest {

  private static final int QUALITY = 3;

  @Autowired private ExchangeStockWriteService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @PersistenceContext private EntityManager entityManager;

  private long seeded;
  private UUID member;
  private UUID material;
  private UUID location;
  private String locationName;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("write-path-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "write-path-" + material);
    locationName = "write-path-" + UUID.randomUUID();
    Location place = new Location();
    place.setName(locationName);
    location = locationRepository.saveAndFlush(place).getId();
  }

  @Test
  void twinLotsFlipTheFirstWholeRowThenTheRestIsBookedOutOfTheRowStillUnstolen() {
    seed(3, false);
    seed(2, false);

    ExchangeChangeResultDto result =
        service.apply(caller(), set(op(false, "5", "1"), op(true, "0", "3")));

    assertThat(result.applied()).isEqualTo(2);
    assertThat(rows()).containsExactly(List.of("1.000", "false"), List.of("3.000", "true"));
  }

  @Test
  void twinLotsFlipThenSplitTheLaterRowAndBookOutOnlyOfRowsStillUnstolen() {
    seed(2, false);
    seed(3, false);

    ExchangeChangeResultDto result =
        service.apply(caller(), set(op(false, "5", "1"), op(true, "0", "3")));

    assertThat(result.applied()).isEqualTo(2);
    assertThat(rows())
        .containsExactly(
            List.of("1.000", "false"), List.of("1.000", "true"), List.of("2.000", "true"));
  }

  @Test
  void aFallThatFlipsPartOfARowSplitsIt() {
    seed(5, false);

    ExchangeChangeResultDto result =
        service.apply(caller(), set(op(false, "5", "2"), op(true, "0", "3")));

    assertThat(result.applied()).isEqualTo(2);
    assertThat(rows()).containsExactly(List.of("2.000", "false"), List.of("3.000", "true"));
  }

  @Test
  void aRowReservedForAJobOrderIsLeftWhileFreeStockMoves() {
    UUID reserved = seed(3, false);
    seed(2, false);
    reserve(reserved, 3);

    ExchangeChangeResultDto result = service.apply(caller(), set(op(false, "5", "3")));

    assertThat(result.applied()).isEqualTo(1);
    assertThat(rows()).containsExactly(List.of("3.000", "false"));
  }

  @Test
  void aFallBeyondTheFreeStockIsRefusedAndLeavesEveryRow() {
    UUID reserved = seed(3, false);
    seed(2, false);
    reserve(reserved, 3);

    ExchangeChangeResultDto result = service.apply(caller(), set(op(false, "5", "1")));

    assertThat(result.applied()).isZero();
    assertThat(result.results())
        .singleElement()
        .satisfies(
            skipped ->
                assertThat(skipped.reason()).isEqualTo(ExchangeStockWriteService.STOCK_EARMARKED));
    assertThat(rows()).containsExactly(List.of("2.000", "false"), List.of("3.000", "false"));
  }

  @Test
  void aRiseBooksInAndTheStaleExpectationIsRefused() {
    seed(4, false);

    ExchangeChangeResultDto rise = service.apply(caller(), set(op(false, "4", "6")));
    ExchangeChangeResultDto stale = service.apply(caller(), set(op(false, "4", "9")));

    assertThat(rise.applied()).isEqualTo(1);
    assertThat(stale.applied()).isZero();
    assertThat(stale.results())
        .singleElement()
        .satisfies(
            skipped ->
                assertThat(skipped.reason()).isEqualTo(ExchangeStockWriteService.VERSION_CONFLICT));
    assertThat(total()).isEqualByComparingTo("6");
  }

  private ExchangeStockChangeSet set(ExchangeStockChangeSet.Op... ops) {
    return new ExchangeStockChangeSet(List.of(ops), false);
  }

  private ExchangeStockChangeSet.Op op(boolean stolen, String expected, String target) {
    return new ExchangeStockChangeSet.Op(
        "op-" + stolen,
        "set-quantity",
        new ExchangeItemRef(material.toString(), null, null, null, null, null, null),
        new ExchangeLocationRef(locationName, null),
        QUALITY,
        stolen,
        new ExchangeStockChangeSet.Quantity(new BigDecimal(target), "SCU"),
        new ExchangeStockChangeSet.Quantity(new BigDecimal(expected), "SCU"),
        null);
  }

  private ExchangeCaller caller() {
    return new ExchangeCaller(member, "versekit-write-path", "W".repeat(43));
  }

  private UUID seed(double amount, boolean stolen) {
    UUID id = new UUID(0L, ++seeded);
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, version, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, true, ?, 0, now(), now())
        """,
        id,
        member,
        material,
        location,
        QUALITY,
        amount,
        stolen);
    return id;
  }

  private void reserve(UUID row, double amount) {
    Squadron unit = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    JobOrder order = new JobOrder();
    order.setResponsibleOrgUnit(unit);
    order.setRequestingOrgUnit(unit);
    order.setHandle("write-path-" + UUID.randomUUID());
    UUID orderId = jobOrderRepository.saveAndFlush(order).getId();
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        row,
        orderId,
        amount);
  }

  private List<List<String>> rows() {
    entityManager.flush();
    List<List<String>> rows = new ArrayList<>();
    jdbc.query(
        """
        SELECT amount, stolen FROM inventory_item
        WHERE user_id = ? AND material_id = ? AND amount > 0
        ORDER BY stolen, amount
        """,
        resultSet -> {
          rows.add(
              List.of(
                  new BigDecimal(resultSet.getDouble("amount"))
                      .setScale(3, java.math.RoundingMode.HALF_UP)
                      .toPlainString(),
                  String.valueOf(resultSet.getBoolean("stolen"))));
        },
        member,
        material);
    return rows;
  }

  private BigDecimal total() {
    entityManager.flush();
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ? AND material_id = ?",
        BigDecimal.class,
        member,
        material);
  }
}
