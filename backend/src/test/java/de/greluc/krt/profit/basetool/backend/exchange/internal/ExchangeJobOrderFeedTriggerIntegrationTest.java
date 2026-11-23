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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job-order change log against PostgreSQL (REQ-OXCH-003, REQ-XCH-013, ADR-0406): a changed
 * fingerprint announces its order per unit and per assignee, old and new, and memberships, unit
 * activation, renames, name withholding and the member's own note announce the orders of the client
 * set they touch. Every statement commits on its own, so the once-per-transaction rule of the log
 * never merges two steps.
 */
@SpringBootTest
class ExchangeJobOrderFeedTriggerIntegrationTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private UserRepository users;
  @Autowired private OrgUnitRepository orgUnits;
  @Autowired private JobOrderRepository orders;

  private final List<UUID> createdOrders = new ArrayList<>();
  private final List<UUID> createdUnits = new ArrayList<>();
  private final List<UUID> createdUsers = new ArrayList<>();

  private UUID requesting;
  private UUID responsible;
  private UUID supporting;
  private UUID formerSupporter;
  private UUID xena;
  private UUID yuri;

  @BeforeEach
  void seed() {
    requesting = unit("Requesting");
    responsible = unit("Responsible");
    supporting = unit("Supporting");
    formerSupporter = unit("Former supporter");
    xena = user("feed-xena");
    yuri = user("feed-yuri");
  }

  @AfterEach
  void clean() {
    createdOrders.forEach(id -> {
      jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    createdUsers.forEach(id -> {
      jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    });
    createdUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
  }

  @Test
  void aFirstFingerprintAnnouncesTheOrderToItsUnitsAndAssignees() {
    UUID order = order(requesting, responsible);
    support(order, supporting, true);
    support(order, formerSupporter, false);
    assignee(order, xena);
    assignee(order, yuri);
    long before = position();

    fingerprint(order);

    assertThat(unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, responsible, supporting);
    assertThat(membersAnnounced(order, before)).containsExactlyInAnyOrder(xena, yuri);
  }

  @Test
  void aFirstFingerprintOutsideTheClientSetAnnouncesNothing() {
    UUID order = order(requesting, responsible);
    assignee(order, xena);
    close(order, "30 days");
    long before = position();

    fingerprint(order);

    assertThat(unitsAnnounced(order, before)).isEmpty();
    assertThat(membersAnnounced(order, before)).isEmpty();
  }

  @Test
  void aChangedFingerprintAnnouncesTheOrderToTheOldAndTheNewUnitsAndAssignees() {
    UUID order = order(requesting, responsible);
    assignee(order, xena);
    fingerprint(order);
    UUID newRequester = unit("New requester");
    jdbc.update(
        "UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", newRequester, order);
    support(order, supporting, true);
    jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", order);
    assignee(order, yuri);
    long before = position();

    refingerprint(order);

    assertThat(unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, newRequester, responsible, supporting);
    assertThat(membersAnnounced(order, before)).containsExactlyInAnyOrder(xena, yuri);

    long after = position();
    jdbc.update("DELETE FROM job_order_participation WHERE job_order_id = ?", order);
    refingerprint(order);

    assertThat(unitsAnnounced(order, after))
        .containsExactlyInAnyOrder(newRequester, responsible, supporting);
    assertThat(membersAnnounced(order, after)).containsExactly(yuri);
  }

  @Test
  void anUnchangedFingerprintOrASettlementFlagAloneAnnouncesNothing() {
    UUID order = order(requesting, responsible);
    fingerprint(order);
    long before = position();

    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET fingerprint = fingerprint WHERE job_order_id ="
            + " ?",
        order);
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET settlement_open = TRUE WHERE job_order_id = ?",
        order);

    assertThat(unitsAnnounced(order, before)).isEmpty();
  }

  @Test
  void aHardDeleteAnnouncesTheOrderWhereItWasLastAnnounced() {
    UUID order = order(requesting, responsible);
    assignee(order, xena);
    fingerprint(order);
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", supporting, order);
    long before = position();

    jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", order);
    jdbc.update("DELETE FROM job_order WHERE id = ?", order);

    assertThat(unitsAnnounced(order, before)).containsExactlyInAnyOrder(requesting, responsible);
    assertThat(membersAnnounced(order, before)).containsExactly(xena);
  }

  @Test
  void joiningAUnitAnnouncesItsOrdersOfTheClientSetToTheMember() {
    UUID requested = printedOrder(requesting, responsible);
    UUID supported = printedOrder(responsible, formerSupporter);
    support(supported, requesting, true);
    close(supported, "3 days");
    UUID closedLongAgo = printedOrder(requesting, responsible);
    close(closedLongAgo, "30 days");
    UUID unrelated = printedOrder(responsible, supporting);
    long before = position();

    join(xena, requesting);

    assertThat(ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(requested.toString(), supported.toString());
    assertThat(unitsAnnounced(unrelated, before)).isEmpty();
  }

  @Test
  void leavingAUnitAnnouncesEveryOrderTheMemberMayHoldThroughIt() {
    UUID requested = printedOrder(requesting, responsible);
    UUID movedAway = printedOrder(requesting, responsible);
    jdbc.update(
        "UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", supporting, movedAway);
    refingerprint(movedAway);
    UUID unannounced = printedOrder(requesting, responsible);
    jdbc.update(
        "UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", supporting, unannounced);
    jdbc.update(
        "DELETE FROM exchange_change WHERE org_unit_id = ? AND entity_key = ?",
        requesting,
        unannounced.toString());
    UUID closedLongAgo = printedOrder(responsible, supporting);
    close(closedLongAgo, "30 days");
    join(xena, requesting);
    long before = position();

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", xena, requesting);

    assertThat(ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(
            requested.toString(), movedAway.toString(), unannounced.toString());
  }

  @Test
  void aMembershipMovingToAnotherUnitAnnouncesTheOrdersOfBoth() {
    UUID requested = printedOrder(requesting, formerSupporter);
    UUID responsibleFor = printedOrder(formerSupporter, responsible);
    join(xena, requesting);
    long before = position();

    jdbc.update(
        "UPDATE org_unit_membership SET org_unit_id = ? WHERE user_id = ? AND org_unit_id = ?",
        responsible,
        xena,
        requesting);

    assertThat(ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(requested.toString(), responsibleFor.toString());
  }

  @Test
  void deactivatingOrActivatingAUnitAnnouncesItsOrdersToEveryMember() {
    UUID requested = printedOrder(requesting, responsible);
    join(xena, requesting);
    join(yuri, requesting);
    long before = position();

    jdbc.update("UPDATE org_unit SET active = FALSE WHERE id = ?", requesting);

    assertThat(ordersAnnouncedTo(xena, before)).containsExactly(requested.toString());
    assertThat(ordersAnnouncedTo(yuri, before)).containsExactly(requested.toString());

    long deactivated = position();
    jdbc.update("UPDATE org_unit SET active = TRUE WHERE id = ?", requesting);

    assertThat(ordersAnnouncedTo(xena, deactivated)).containsExactly(requested.toString());
  }

  @Test
  void renamingAUnitAnnouncesItsOrdersToEveryoneWhoReadsThem() {
    UUID requested = printedOrder(requesting, responsible);
    UUID supported = order(responsible, formerSupporter);
    support(supported, requesting, true);
    assignee(supported, yuri);
    fingerprint(supported);
    UUID unrelated = printedOrder(responsible, formerSupporter);
    long before = position();

    jdbc.update("UPDATE org_unit SET name = name || ' renamed' WHERE id = ?", requesting);

    assertThat(unitsAnnounced(requested, before))
        .containsExactlyInAnyOrder(requesting, responsible);
    assertThat(unitsAnnounced(supported, before))
        .containsExactlyInAnyOrder(requesting, responsible, formerSupporter);
    assertThat(membersAnnounced(supported, before)).containsExactly(yuri);
    assertThat(unitsAnnounced(unrelated, before)).isEmpty();

    long renamed = position();
    jdbc.update("UPDATE org_unit SET shorthand = 'R' || shorthand WHERE id = ?", requesting);

    assertThat(unitsAnnounced(requested, renamed))
        .containsExactlyInAnyOrder(requesting, responsible);
  }

  @Test
  void renamingAnAssigneeOrSwitchingTheirNameWithholdingAnnouncesTheirOrders() {
    UUID worked = order(requesting, responsible);
    assignee(worked, xena);
    assignee(worked, yuri);
    fingerprint(worked);
    UUID closedLongAgo = order(requesting, responsible);
    assignee(closedLongAgo, xena);
    fingerprint(closedLongAgo);
    close(closedLongAgo, "30 days");
    long before = position();

    jdbc.update("UPDATE app_user SET display_name = 'Xena the Miner' WHERE id = ?", xena);

    assertThat(unitsAnnounced(worked, before)).containsExactlyInAnyOrder(requesting, responsible);
    assertThat(membersAnnounced(worked, before)).containsExactlyInAnyOrder(xena, yuri);
    assertThat(membersAnnounced(closedLongAgo, before)).isEmpty();

    long renamed = position();
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, true)", xena);

    assertThat(membersAnnounced(worked, renamed)).containsExactlyInAnyOrder(xena, yuri);

    long withheld = position();
    jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", xena);

    assertThat(unitsAnnounced(worked, withheld)).containsExactlyInAnyOrder(requesting, responsible);
  }

  @Test
  void anAssigneesOwnNoteIsAnnouncedToThemAlone() {
    UUID worked = order(requesting, responsible);
    assignee(worked, xena);
    assignee(worked, yuri);
    fingerprint(worked);
    long before = position();

    jdbc.update(
        "UPDATE job_order_assignees SET note = 'my own note' WHERE job_order_id = ? AND user_id ="
            + " ?",
        worked,
        xena);

    assertThat(membersAnnounced(worked, before)).containsExactly(xena);
    assertThat(unitsAnnounced(worked, before)).isEmpty();
  }

  private long position() {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT COALESCE(max(seq), 0) FROM exchange_change", Long.class));
  }

  private Set<UUID> unitsAnnounced(UUID order, long after) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT org_unit_id FROM exchange_change WHERE seq > ? AND resource = 'JOB_ORDER'"
                + " AND entity_key = ? AND org_unit_id IS NOT NULL",
            UUID.class,
            after,
            order.toString()));
  }

  private Set<UUID> membersAnnounced(UUID order, long after) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT user_id FROM exchange_change WHERE seq > ? AND resource = 'JOB_ORDER'"
                + " AND entity_key = ? AND user_id IS NOT NULL",
            UUID.class,
            after,
            order.toString()));
  }

  private Set<String> ordersAnnouncedTo(UUID member, long after) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT entity_key FROM exchange_change WHERE seq > ? AND resource = 'JOB_ORDER'"
                + " AND user_id = ?",
            String.class,
            after,
            member));
  }

  private void join(UUID member, UUID unit) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', false)",
        member,
        unit);
  }

  private UUID printedOrder(UUID requestingUnit, UUID responsibleUnit) {
    UUID order = order(requestingUnit, responsibleUnit);
    fingerprint(order);
    return order;
  }

  private void fingerprint(UUID order) {
    jdbc.update(
        "INSERT INTO exchange_job_order_fingerprint (job_order_id, fingerprint, changed_at,"
            + " settlement_open) VALUES (?, ?, now(), false)",
        order,
        hash());
  }

  private void refingerprint(UUID order) {
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET fingerprint = ?, changed_at = now()"
            + " WHERE job_order_id = ?",
        hash(),
        order);
  }

  private static byte[] hash() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  private void close(UUID order, String ago) {
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', priority = NULL,"
            + " closed_at = now() - CAST(? AS INTERVAL) WHERE id = ?",
        ago,
        order);
  }

  private UUID order(UUID requestingUnit, UUID responsibleUnit) {
    UUID id =
        transactions.execute(_ ->
            orders
                .saveAndFlush(
                    JobOrder.builder()
                        .requestingOrgUnit(orgUnits.getReferenceById(requestingUnit))
                        .responsibleOrgUnit(orgUnits.getReferenceById(responsibleUnit))
                        .handle("feed contact")
                        .priority(1)
                        .status(JobOrderStatus.OPEN)
                        .build())
                .getId());
    createdOrders.add(Objects.requireNonNull(id));
    return id;
  }

  private void support(UUID order, UUID unit, boolean active) {
    jdbc.update(
        "INSERT INTO job_order_participation (id, job_order_id, claiming_org_unit_id, active)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        order,
        unit,
        active);
  }

  private void assignee(UUID order, UUID user) {
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, version) VALUES (?, ?, ?, 0)",
        UUID.randomUUID(),
        order,
        user);
  }

  private @NotNull UUID unit(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active) VALUES (?, 'SQUADRON', ?, ?,"
            + " TRUE)",
        id,
        name + " " + id,
        "F" + id.toString().substring(0, 6));
    createdUnits.add(id);
    return id;
  }

  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    UUID id = users.saveAndFlush(user).getId();
    createdUsers.add(id);
    return id;
  }
}
