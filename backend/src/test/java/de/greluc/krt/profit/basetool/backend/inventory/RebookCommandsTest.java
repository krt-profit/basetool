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

package de.greluc.krt.profit.basetool.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.Rebook;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookDirection;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookOutcome;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookedRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rebooking of a member's own lot between personal and one pool through the inventory module's
 * api, against PostgreSQL: the row order, each refusal, partial and whole moves in both directions,
 * the reported changes and the audit attributed to the calling client (REQ-INV-060).
 */
@SpringBootTest
class RebookCommandsTest {

  @Autowired private RebookCommands rebookCommands;
  @Autowired private StockCommands stockCommands;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID member;
  private UUID otherMember;
  private UUID target;
  private UUID otherUnit;
  private UUID material;
  private UUID location;
  private String client;
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> items = new ArrayList<>();

  @BeforeEach
  void setUp() {
    member = user("rebook-member-");
    otherMember = user("rebook-other-");
    target = orgUnit();
    otherUnit = orgUnit();
    joins(member, target);
    joins(member, otherUnit);
    joins(otherMember, target);
    material = material("SCU");
    location = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)",
        location,
        "Rebook place " + location);
    client = "rb-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        "INSERT INTO exchange_client (id, client_id, display_name, status, created_at)"
            + " VALUES (?, ?, 'Verse Kit', 'ACTIVE', now())",
        UUID.randomUUID(),
        client);
    knownClients.invalidate();
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .claim("sub", member.toString())
            .claim("azp", client)
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    for (UUID user : List.of(member, otherMember)) {
      jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", user);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", user);
    }
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    jdbc.update("DELETE FROM location WHERE id = ?", location);
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    items.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    knownClients.invalidate();
    userRepository.deleteById(member);
    userRepository.deleteById(otherMember);
  }

  @Test
  void aShareTakesRowsOfTheTargetUnitThenRowsWithoutAUnitThenTheOldest() {
    UUID otherStamped = row(member, 5.0, true, otherUnit, 9000);
    UUID earmarked = row(member, 3.0, true, null, 8000);
    reserveForMission(earmarked, 3.0);
    UUID olderUnstamped = row(member, 3.0, true, null, 7000);
    UUID newerUnstamped = row(member, 4.0, true, null, 6000);
    UUID stamped = row(member, 2.0, true, target, 10);

    RebookOutcome outcome = rebook(RebookDirection.TO_POOL, 7.0, false);

    assertThat(outcome.refusal()).isNull();
    assertThat(amountOf(stamped)).isNull();
    assertThat(amountOf(olderUnstamped)).isNull();
    assertThat(amountOf(newerUnstamped)).isEqualTo(2.0);
    assertThat(amountOf(earmarked)).isEqualTo(3.0);
    assertThat(amountOf(otherStamped)).isEqualTo(5.0);
    assertThat(poolAmounts(member, target)).containsExactlyInAnyOrder(2.0, 3.0, 2.0);
    assertThat(outcome.changes())
        .filteredOn(change -> change.after() == null)
        .extracting(change -> Objects.requireNonNull(change.before()).id())
        .containsExactlyInAnyOrder(stamped, olderUnstamped);
    assertThat(outcome.changes())
        .filteredOn(change -> change.before() == null)
        .extracting(RebookedRow::after)
        .allSatisfy(
            after -> {
              assertThat(Objects.requireNonNull(after).personal()).isFalse();
              assertThat(after.orgUnitId()).isEqualTo(target);
            })
        .extracting(after -> Objects.requireNonNull(after).amount())
        .containsExactlyInAnyOrder(2.0, 3.0, 2.0);
  }

  @Test
  void aWholeShareMovesEveryPersonalRowAndKeepsHolderQualityAndStolenMarker() {
    UUID material = material("SCU");
    UUID first = row(member, material, 4.0, true, null, 600, true, 300);
    UUID second = row(member, material, 1.5, true, otherUnit, 600, true, 200);

    RebookOutcome outcome =
        transaction(
            () -> {
              StockLot lot = lot(material, 600, true);
              stockCommands.lockLots(member, List.of(lot.key()));
              return rebookCommands.rebook(
                  new Rebook(member, lot, target, RebookDirection.TO_POOL, 5.5), false);
            });

    assertThat(outcome.refusal()).isNull();
    assertThat(amountOf(first)).isNull();
    assertThat(amountOf(second)).isNull();
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND NOT personal"
                    + " AND owning_org_unit_id = ? AND quality = 600 AND stolen",
                Double.class,
                member,
                target))
        .containsExactlyInAnyOrder(4.0, 1.5);
    assertThat(personalAmount()).isZero();
  }

  @Test
  void anUnshareTakesOnlyTheMembersOwnRowsInThatPool() {
    UUID own = row(member, 6.0, false, target, 100);
    UUID otherPool = row(member, 2.0, false, otherUnit, 200);
    UUID foreign = row(otherMember, 9.0, false, target, 300);

    RebookOutcome outcome = rebook(RebookDirection.TO_PERSONAL, 4.0, false);

    assertThat(outcome.refusal()).isNull();
    assertThat(amountOf(own)).isEqualTo(2.0);
    assertThat(amountOf(otherPool)).isEqualTo(2.0);
    assertThat(amountOf(foreign)).isEqualTo(9.0);
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND personal"
                    + " AND owning_org_unit_id = ?",
                Double.class,
                member,
                target))
        .containsExactly(4.0);
  }

  @Test
  void aWholeUnshareWorksAfterTheMemberLeftTheUnit() {
    UUID own = row(member, 3.0, false, target, 100);
    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, target);

    RebookOutcome outcome = rebook(RebookDirection.TO_PERSONAL, 3.0, false);

    assertThat(outcome.refusal()).isNull();
    assertThat(amountOf(own)).isNull();
    assertThat(personalAmount()).isEqualTo(3.0);
  }

  @Test
  void aShareIntoAUnitTheMemberIsNotADirectMemberOfIsRefused() {
    UUID foreignUnit = orgUnit();
    UUID personal = row(member, 3.0, true, null, 100);

    RebookOutcome outcome =
        transaction(
            () -> {
              StockLot lot = lot(material, 500, false);
              stockCommands.lockLots(member, List.of(lot.key()));
              return rebookCommands.rebook(
                  new Rebook(member, lot, foreignUnit, RebookDirection.TO_POOL, 1.0), false);
            });

    assertThat(outcome.refusal()).isEqualTo(RebookRefusal.NOT_A_MEMBER);
    assertThat(outcome.changes()).isEmpty();
    assertThat(amountOf(personal)).isEqualTo(3.0);
  }

  @Test
  void aShareOfMoreThanThePersonalStockIsRefused() {
    row(member, 3.0, true, null, 100);
    row(member, 5.0, false, target, 100);

    assertThat(rebook(RebookDirection.TO_POOL, 3.5, false).refusal())
        .isEqualTo(RebookRefusal.INSUFFICIENT_STOCK);
    assertThat(rebook(RebookDirection.TO_PERSONAL, 5.5, false).refusal())
        .isEqualTo(RebookRefusal.INSUFFICIENT_STOCK);
    assertThat(personalAmount()).isEqualTo(3.0);
  }

  @Test
  void aRebookingThatWouldMoveReservedStockIsRefused() {
    UUID personal = row(member, 4.0, true, null, 100);
    reserveForMission(personal, 3.0);
    UUID shared = row(member, 4.0, false, target, 100);
    reserveForMission(shared, 1.0);

    assertThat(rebook(RebookDirection.TO_POOL, 2.0, false).refusal())
        .isEqualTo(RebookRefusal.STOCK_EARMARKED);
    assertThat(rebook(RebookDirection.TO_PERSONAL, 1.0, false).refusal())
        .isEqualTo(RebookRefusal.STOCK_EARMARKED);
    assertThat(rebook(RebookDirection.TO_POOL, 1.0, false).refusal()).isNull();
    assertThat(amountOf(personal)).isEqualTo(3.0);
    assertThat(amountOf(shared)).isEqualTo(4.0);
  }

  @Test
  void aShareThatWouldLeaveARowBelowItsOfferIsRefused() {
    UUID offered = row(member, 5.0, true, null, 100);
    offer(offered, 4.0);

    assertThat(rebook(RebookDirection.TO_POOL, 2.0, false).refusal())
        .isEqualTo(RebookRefusal.STOCK_OFFERED);
    assertThat(rebook(RebookDirection.TO_POOL, 1.0, false).refusal()).isNull();
    assertThat(amountOf(offered)).isEqualTo(4.0);
  }

  @Test
  void aFractionOfAnItemLotIsRefused() {
    UUID item = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        item,
        "Rebook helmet " + item);
    items.add(item);
    UUID row = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount, personal,"
            + " stolen, created_at, version) VALUES (?, ?, ?, ?, 3, true, false, now(), 0)",
        row,
        member,
        item,
        location);

    RebookOutcome outcome =
        transaction(
            () -> {
              StockLot lot =
                  new StockLot(
                      null,
                      gameItemRepository.findById(item).orElseThrow(),
                      locationRepository.findById(location).orElseThrow(),
                      null,
                      false);
              stockCommands.lockLots(member, List.of(lot.key()));
              return rebookCommands.rebook(
                  new Rebook(member, lot, target, RebookDirection.TO_POOL, 1.5), false);
            });

    assertThat(outcome.refusal()).isEqualTo(RebookRefusal.UNIT_MISMATCH);
    assertThat(amountOf(row)).isEqualTo(3.0);
  }

  @Test
  void aDryRunDecidesAndWritesNothing() {
    UUID personal = row(member, 3.0, true, null, 100);

    RebookOutcome outcome = rebook(RebookDirection.TO_POOL, 2.0, true);

    assertThat(outcome.refusal()).isNull();
    assertThat(outcome.changes()).isEmpty();
    assertThat(amountOf(personal)).isEqualTo(3.0);
    assertThat(auditEvents()).isEmpty();
  }

  @Test
  void eachDirectionIsAuditedUnderTheCallingClient() {
    row(member, 3.0, true, null, 100);

    rebook(RebookDirection.TO_POOL, 2.0, false);
    rebook(RebookDirection.TO_PERSONAL, 1.0, false);

    assertThat(auditEvents())
        .containsExactlyInAnyOrder(
            "INVENTORY_ITEM_DEPERSONALIZED:" + client, "INVENTORY_ITEM_PERSONALIZED:" + client);
  }

  @Test
  void theReportedChangesCarryTheStatesBeforeAndAfter() {
    UUID personal = row(member, 3.0, true, null, 100);

    RebookOutcome outcome = rebook(RebookDirection.TO_POOL, 1.0, false);

    assertThat(outcome.changes())
        .filteredOn(change -> change.before() != null && change.after() != null)
        .singleElement()
        .satisfies(
            (RebookedRow change) -> {
              assertThat(Objects.requireNonNull(change.before()).id()).isEqualTo(personal);
              assertThat(change.before().amount()).isEqualTo(3.0);
              assertThat(Objects.requireNonNull(change.after()).amount()).isEqualTo(2.0);
              assertThat(change.after().version()).isGreaterThan(change.before().version());
            });
    assertThat(outcome.changes()).hasSize(2);
  }

  private RebookOutcome rebook(RebookDirection direction, double amount, boolean dryRun) {
    return transaction(
        () -> {
          StockLot lot = lot(material, 500, false);
          stockCommands.lockLots(member, List.of(lot.key()));
          return rebookCommands.rebook(new Rebook(member, lot, target, direction, amount), dryRun);
        });
  }

  private <T> T transaction(@NotNull Supplier<T> work) {
    return Objects.requireNonNull(
        new TransactionTemplate(transactionManager).execute(_ -> work.get()));
  }

  private @NotNull StockLot lot(@NotNull UUID materialId, int quality, boolean stolen) {
    return new StockLot(
        materialRepository.findById(materialId).orElseThrow(),
        null,
        locationRepository.findById(location).orElseThrow(),
        quality,
        stolen);
  }

  private @NotNull UUID user(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, FALSE)",
        id,
        "Unit " + id,
        "R" + id.toString().substring(0, 6));
    orgUnits.add(id);
    return id;
  }

  private void joins(@NotNull UUID user, @NotNull UUID orgUnit) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id) VALUES (?, ?)", user, orgUnit);
  }

  private @NotNull UUID material(@NotNull String unit) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', ?, false, false, true, 'UEX_ONLY')",
        id,
        "Rebook material " + id,
        unit);
    materials.add(id);
    return id;
  }

  private @NotNull UUID row(
      @NotNull UUID owner,
      double amount,
      boolean personal,
      @Nullable UUID orgUnit,
      long ageSeconds) {
    return row(owner, material, amount, personal, orgUnit, 500, false, ageSeconds);
  }

  private @NotNull UUID row(
      @NotNull UUID owner,
      @NotNull UUID materialId,
      double amount,
      boolean personal,
      @Nullable UUID orgUnit,
      int quality,
      boolean stolen,
      long ageSeconds) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,"
            + " personal, stolen, owning_org_unit_id, created_at, version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
        id,
        owner,
        materialId,
        location,
        quality,
        amount,
        personal,
        stolen,
        orgUnit,
        Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
    return id;
  }

  private void reserveForMission(@NotNull UUID row, double amount) {
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
    missions.add(mission);
    jdbc.update(
        "INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,"
            + " version, created_at, updated_at) VALUES (?, ?, ?, ?, 0, now(), now())",
        UUID.randomUUID(),
        row,
        mission,
        amount);
  }

  private void offer(@NotNull UUID row, double amount) {
    jdbc.update(
        "INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, status,"
            + " released_at, version, created_at, updated_at, offered_amount, offer_kind)"
            + " VALUES (?, ?, ?, 'ACTIVE', now(), 0, now(), now(), ?, 'MATERIAL')",
        UUID.randomUUID(),
        row,
        member,
        amount);
  }

  private @Nullable Double amountOf(@NotNull UUID row) {
    List<Double> amounts =
        jdbc.queryForList("SELECT amount FROM inventory_item WHERE id = ?", Double.class, row);
    return amounts.isEmpty() ? null : amounts.getFirst();
  }

  private @NotNull List<Double> poolAmounts(@NotNull UUID owner, @NotNull UUID orgUnit) {
    return jdbc.queryForList(
        "SELECT amount FROM inventory_item WHERE user_id = ? AND NOT personal"
            + " AND owning_org_unit_id = ?",
        Double.class,
        owner,
        orgUnit);
  }

  private double personalAmount() {
    Map<String, Object> sum =
        jdbc.queryForMap(
            "SELECT COALESCE(SUM(amount), 0) AS total FROM inventory_item"
                + " WHERE user_id = ? AND personal",
            member);
    return ((Number) sum.get("total")).doubleValue();
  }

  private @NotNull List<String> auditEvents() {
    return jdbc.queryForList(
        "SELECT event_type || ':' || client_id FROM audit_event WHERE target_user_id = ?"
            + " AND event_type IN ('INVENTORY_ITEM_DEPERSONALIZED', 'INVENTORY_ITEM_PERSONALIZED')",
        String.class,
        member);
  }
}
