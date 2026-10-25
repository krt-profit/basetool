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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A Logistician of both units moves a member's shared row, or a part of it, into another pool of
 * the holder, with the Lager's audit and the holder's notification; a caller who is no Logistician
 * of both units, a holder outside the target unit and a part the rest cannot carry are refused
 * (REQ-INV-059).
 */
@SpringBootTest
class InventoryPoolChangeIntegrationTest {

  private static final long WAIT_MILLIS = 20_000;
  private static final long POLL_MILLIS = 100;

  @Autowired private InventoryPoolChangeService poolChangeService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();

  private UUID logistician;
  private UUID holder;
  private UUID alpha;
  private UUID bravo;

  @BeforeEach
  void setUp() {
    logistician = user("Logistician");
    holder = user("Holder");
    alpha = orgUnit("Alpha");
    bravo = orgUnit("Bravo");
    joins(holder, alpha);
    joins(holder, bravo);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    for (UUID id : users) {
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
    }
    jobOrders.forEach(id -> jdbc.update("DELETE FROM job_order WHERE id = ?", id));
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
  }

  @Test
  void aLogisticianOfBothUnitsMovesTheWholeRowAndTheHolderIsTold() {
    actsAsLogisticianOf(alpha, bravo);
    UUID row = stock(material(), location(), 10, alpha);

    UUID result = move(row, bravo, null);

    assertThat(result).isEqualTo(row);
    assertThat(column(row, "owning_org_unit_id")).isEqualTo(bravo);
    assertThat(((Number) column(row, "amount")).doubleValue()).isEqualTo(10);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT * FROM audit_event WHERE subject_id = ? AND event_type ="
                + " 'INVENTORY_POOL_CHANGED'",
            row);
    assertThat(audit.get("target_user_id")).isEqualTo(holder);
    assertThat((String) audit.get("details")).contains("split=false");
    List<Map<String, Object>> told = awaitNotifications(holder);
    assertThat(told.getFirst().get("type")).isEqualTo("INVENTORY_POOL_CHANGED_BY_OTHER");
    assertThat((String) told.getFirst().get("params"))
        .contains("\"actor\":\"Logistician\"")
        .contains("\"fromUnit\":\"Alpha ")
        .contains("\"toUnit\":\"Bravo ");
  }

  @Test
  void aPartIsSplitOffTheFreeStockAndTheRowKeepsItsEarmark() {
    actsAsLogisticianOf(alpha, bravo);
    UUID row = stock(material(), location(), 10, alpha);
    earmarkToJobOrder(row, 6);

    UUID part = move(row, bravo, 4.0);

    assertThat(part).isNotEqualTo(row);
    assertThat(column(part, "owning_org_unit_id")).isEqualTo(bravo);
    assertThat(column(part, "personal")).isEqualTo(false);
    assertThat(((Number) column(part, "amount")).doubleValue()).isEqualTo(4);
    assertThat(((Number) column(row, "amount")).doubleValue()).isEqualTo(6);
    assertThat(column(row, "owning_org_unit_id")).isEqualTo(alpha);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM inventory_item_job_order_allocation"
                    + " WHERE inventory_item_id = ?",
                Long.class,
                part))
        .isZero();
  }

  @Test
  void aPartTheRestCannotCarryTheEarmarksForIsRefused() {
    actsAsLogisticianOf(alpha, bravo);
    UUID row = stock(material(), location(), 10, alpha);
    earmarkToJobOrder(row, 8);

    assertThatThrownBy(() -> move(row, bravo, 3.0)).isInstanceOf(OverAllocationException.class);
    assertThat(((Number) column(row, "amount")).doubleValue()).isEqualTo(10);
  }

  @Test
  void aCallerWhoIsNoLogisticianOfTheTargetUnitIsRefused() {
    actsAsLogisticianOf(alpha);
    UUID row = stock(material(), location(), 10, alpha);

    assertThatThrownBy(() -> move(row, bravo, null)).isInstanceOf(AccessDeniedException.class);
    assertThat(column(row, "owning_org_unit_id")).isEqualTo(alpha);
  }

  @Test
  void aTargetTheHolderDoesNotBelongToIsRefused() {
    UUID charlie = orgUnit("Charlie");
    actsAsLogisticianOf(alpha, charlie);
    UUID row = stock(material(), location(), 10, alpha);

    assertThatThrownBy(() -> move(row, charlie, null)).isInstanceOf(BadRequestException.class);
    assertThat(column(row, "owning_org_unit_id")).isEqualTo(alpha);
  }

  @Test
  void theHolderMovingTheirOwnRowIsNotTold() throws InterruptedException {
    actsAs(holder, alpha, bravo);
    UUID row = stock(material(), location(), 5, alpha);

    move(row, bravo, null);

    assertThat(column(row, "owning_org_unit_id")).isEqualTo(bravo);
    Thread.sleep(1_000);
    assertThat(notifications(holder)).isEmpty();
  }

  @Test
  void theTargetsAreTheHoldersOtherUnitsInWhichTheCallerIsALogistician() {
    UUID charlie = orgUnit("Charlie");
    joins(holder, charlie);
    actsAsLogisticianOf(alpha, bravo);
    UUID row = stock(material(), location(), 5, alpha);

    List<OrgUnitReferenceDto> targets = poolChangeService.poolTargets(row);

    assertThat(targets).extracting(OrgUnitReferenceDto::id).containsExactly(bravo);
  }

  /**
   * Moves a row as the current caller in its own transaction.
   *
   * @param row the row
   * @param target the receiving unit
   * @param amount the part, or {@code null} for the whole row
   * @return the row that holds the moved stock
   */
  private @NotNull UUID move(@NotNull UUID row, @NotNull UUID target, Double amount) {
    UUID caller = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    long version = ((Number) column(row, "version")).longValue();
    return transactionTemplate.execute(
        _ ->
            poolChangeService
                .changePool(
                    row, new InventoryItemPoolChangeDto(version, target, amount, null), caller)
                .id());
  }

  /**
   * Makes the Logistician the caller, with the per-unit Logistician authority of each unit.
   *
   * @param units the units
   */
  private void actsAsLogisticianOf(@NotNull UUID... units) {
    actsAs(logistician, units);
  }

  /**
   * Makes a member the caller, with the per-unit Logistician authority of each unit.
   *
   * @param member the member
   * @param units the units
   */
  private static void actsAs(@NotNull UUID member, @NotNull UUID... units) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"));
    for (UUID unit : units) {
      authorities.add(new OrgUnitContextualAuthority("LOGISTICIAN", unit));
    }
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(member, null, authorities));
  }

  /**
   * Waits until the member has a notification.
   *
   * @param recipient the member
   * @return the member's notifications
   */
  private List<Map<String, Object>> awaitNotifications(@NotNull UUID recipient) {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    List<Map<String, Object>> found = notifications(recipient);
    while (found.isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for notifications", e);
      }
      found = notifications(recipient);
    }
    assertThat(found).as("notifications of %s", recipient).isNotEmpty();
    return found;
  }

  /**
   * Reads a member's notifications.
   *
   * @param recipient the member
   * @return type and params of each notification
   */
  private List<Map<String, Object>> notifications(@NotNull UUID recipient) {
    return jdbc.queryForList(
        "SELECT type, params FROM notification WHERE recipient_user_id = ?", recipient);
  }

  /**
   * Reads one column of a row.
   *
   * @param row the row
   * @param name the column
   * @return its value
   */
  private Object column(@NotNull UUID row, @NotNull String name) {
    return jdbc.queryForMap("SELECT * FROM inventory_item WHERE id = ?", row).get(name);
  }

  /**
   * Inserts a shared material row of the holder at quality 1.
   *
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @param pool the owning org unit
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID material, @NotNull UUID location, double amount, @NotNull UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, 1, ?, false, false, ?, 0)
        """,
        id,
        holder,
        material,
        location,
        amount,
        pool);
    return id;
  }

  /**
   * Earmarks part of a row to a new job order of Alpha.
   *
   * @param row the row
   * @param amount the earmarked amount
   */
  private void earmarkToJobOrder(@NotNull UUID row, double amount) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        alpha,
        alpha);
    jobOrders.add(order);
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        row,
        order,
        amount);
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
        "Pool " + id);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @return its id
   */
  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Pool " + id);
    locations.add(id);
    return id;
  }

  /**
   * Seeds an active Spezialkommando named {@code <name> <id>}.
   *
   * @param name the visible part of the name
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE, FALSE)",
        id,
        name + " " + id,
        "P" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes a member a direct member of an org unit.
   *
   * @param member the member
   * @param orgUnit the org unit
   */
  private void joins(@NotNull UUID member, @NotNull UUID orgUnit) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        member,
        orgUnit);
  }

  /**
   * Seeds a member with a display name.
   *
   * @param displayName the display name
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String displayName) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("pool-" + UUID.randomUUID());
    user.setDisplayName(displayName);
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }
}
