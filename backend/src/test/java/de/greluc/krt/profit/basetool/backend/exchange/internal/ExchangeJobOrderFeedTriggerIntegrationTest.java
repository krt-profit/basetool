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

import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderChangeLog;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderSeed;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

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

  @Autowired private ApplicationContext context;
  @Autowired private JdbcTemplate jdbc;

  private ExchangeJobOrderSeed seed;
  private ExchangeJobOrderChangeLog changes;
  private UUID requesting;
  private UUID responsible;
  private UUID supporting;
  private UUID formerSupporter;
  private UUID xena;
  private UUID yuri;

  @BeforeEach
  void setUp() {
    seed = new ExchangeJobOrderSeed(context);
    changes = new ExchangeJobOrderChangeLog(jdbc);
    requesting = seed.squadron("Requesting", null);
    responsible = seed.squadron("Responsible", null);
    supporting = seed.squadron("Supporting", null);
    formerSupporter = seed.squadron("Former supporter", null);
    xena = seed.user("feed-xena", false);
    yuri = seed.user("feed-yuri", false);
  }

  @AfterEach
  void clean() {
    seed.close();
  }

  @Test
  void aFirstFingerprintAnnouncesTheOrderToItsUnitsAndAssignees() {
    UUID order = order(requesting, responsible);
    support(order, supporting, true);
    support(order, formerSupporter, false);
    seed.assignee(order, xena, null);
    seed.assignee(order, yuri, null);
    long before = changes.position();

    fingerprint(order);

    assertThat(changes.unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, responsible, supporting);
    assertThat(changes.membersAnnounced(order, before)).containsExactlyInAnyOrder(xena, yuri);
  }

  @Test
  void aFirstFingerprintOutsideTheClientSetAnnouncesNothing() {
    UUID order = order(requesting, responsible);
    seed.assignee(order, xena, null);
    close(order, "30 days");
    long before = changes.position();

    fingerprint(order);

    assertThat(changes.unitsAnnounced(order, before)).isEmpty();
    assertThat(changes.membersAnnounced(order, before)).isEmpty();
  }

  @Test
  void aChangedFingerprintAnnouncesTheOrderToTheOldAndTheNewUnitsAndAssignees() {
    UUID order = order(requesting, responsible);
    seed.assignee(order, xena, null);
    fingerprint(order);
    UUID newRequester = seed.squadron("New requester", null);
    jdbc.update(
        "UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", newRequester, order);
    support(order, supporting, true);
    jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", order);
    seed.assignee(order, yuri, null);
    long before = changes.position();

    refingerprint(order);

    assertThat(changes.unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, newRequester, responsible, supporting);
    assertThat(changes.membersAnnounced(order, before)).containsExactlyInAnyOrder(xena, yuri);

    long after = changes.position();
    jdbc.update("DELETE FROM job_order_participation WHERE job_order_id = ?", order);
    refingerprint(order);

    assertThat(changes.unitsAnnounced(order, after))
        .containsExactlyInAnyOrder(newRequester, responsible, supporting);
    assertThat(changes.membersAnnounced(order, after)).containsExactly(yuri);
  }

  @Test
  void anUnchangedFingerprintOrASettlementFlagAloneAnnouncesNothing() {
    UUID order = order(requesting, responsible);
    fingerprint(order);
    long before = changes.position();

    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET fingerprint = fingerprint WHERE job_order_id ="
            + " ?",
        order);
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET settlement_open = TRUE WHERE job_order_id = ?",
        order);

    assertThat(changes.unitsAnnounced(order, before)).isEmpty();
  }

  @Test
  void aHardDeleteAnnouncesTheOrderWhereItWasLastAnnounced() {
    UUID order = order(requesting, responsible);
    seed.assignee(order, xena, null);
    fingerprint(order);
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", supporting, order);
    long before = changes.position();

    jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", order);
    jdbc.update("DELETE FROM job_order WHERE id = ?", order);

    assertThat(changes.unitsAnnounced(order, before))
        .containsExactlyInAnyOrder(requesting, responsible);
    assertThat(changes.membersAnnounced(order, before)).containsExactly(xena);
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
    long before = changes.position();

    seed.seat(xena, requesting, "MEMBER", false);

    assertThat(changes.ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(requested.toString(), supported.toString());
    assertThat(changes.unitsAnnounced(unrelated, before)).isEmpty();
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
    seed.seat(xena, requesting, "MEMBER", false);
    long before = changes.position();

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", xena, requesting);

    assertThat(changes.ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(
            requested.toString(), movedAway.toString(), unannounced.toString());
  }

  @Test
  void aMembershipMovingToAnotherUnitAnnouncesTheOrdersOfBoth() {
    UUID requested = printedOrder(requesting, formerSupporter);
    UUID responsibleFor = printedOrder(formerSupporter, responsible);
    seed.seat(xena, requesting, "MEMBER", false);
    long before = changes.position();

    jdbc.update(
        "UPDATE org_unit_membership SET org_unit_id = ? WHERE user_id = ? AND org_unit_id = ?",
        responsible,
        xena,
        requesting);

    assertThat(changes.ordersAnnouncedTo(xena, before))
        .containsExactlyInAnyOrder(requested.toString(), responsibleFor.toString());
  }

  @Test
  void deactivatingOrActivatingAUnitAnnouncesItsOrdersToEveryMember() {
    UUID requested = printedOrder(requesting, responsible);
    seed.seat(xena, requesting, "MEMBER", false);
    seed.seat(yuri, requesting, "MEMBER", false);
    long before = changes.position();

    jdbc.update("UPDATE org_unit SET active = FALSE WHERE id = ?", requesting);

    assertThat(changes.ordersAnnouncedTo(xena, before)).containsExactly(requested.toString());
    assertThat(changes.ordersAnnouncedTo(yuri, before)).containsExactly(requested.toString());

    long deactivated = changes.position();
    jdbc.update("UPDATE org_unit SET active = TRUE WHERE id = ?", requesting);

    assertThat(changes.ordersAnnouncedTo(xena, deactivated)).containsExactly(requested.toString());
  }

  @Test
  void renamingAUnitAnnouncesItsOrdersToEveryoneWhoReadsThem() {
    UUID requested = printedOrder(requesting, responsible);
    UUID supported = order(responsible, formerSupporter);
    support(supported, requesting, true);
    seed.assignee(supported, yuri, null);
    fingerprint(supported);
    UUID unrelated = printedOrder(responsible, formerSupporter);
    long before = changes.position();

    jdbc.update("UPDATE org_unit SET name = name || ' renamed' WHERE id = ?", requesting);

    assertThat(changes.unitsAnnounced(requested, before))
        .containsExactlyInAnyOrder(requesting, responsible);
    assertThat(changes.unitsAnnounced(supported, before))
        .containsExactlyInAnyOrder(requesting, responsible, formerSupporter);
    assertThat(changes.membersAnnounced(supported, before)).containsExactly(yuri);
    assertThat(changes.unitsAnnounced(unrelated, before)).isEmpty();

    long renamed = changes.position();
    jdbc.update("UPDATE org_unit SET shorthand = 'R' || shorthand WHERE id = ?", requesting);

    assertThat(changes.unitsAnnounced(requested, renamed))
        .containsExactlyInAnyOrder(requesting, responsible);
  }

  @Test
  void renamingAnAssigneeOrSwitchingTheirNameWithholdingAnnouncesTheirOrders() {
    UUID worked = order(requesting, responsible);
    seed.assignee(worked, xena, null);
    seed.assignee(worked, yuri, null);
    fingerprint(worked);
    UUID closedLongAgo = order(requesting, responsible);
    seed.assignee(closedLongAgo, xena, null);
    fingerprint(closedLongAgo);
    close(closedLongAgo, "30 days");
    long before = changes.position();

    jdbc.update("UPDATE app_user SET display_name = 'Xena the Miner' WHERE id = ?", xena);

    assertThat(changes.unitsAnnounced(worked, before))
        .containsExactlyInAnyOrder(requesting, responsible);
    assertThat(changes.membersAnnounced(worked, before)).containsExactlyInAnyOrder(xena, yuri);
    assertThat(changes.membersAnnounced(closedLongAgo, before)).isEmpty();

    long renamed = changes.position();
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, true)", xena);

    assertThat(changes.membersAnnounced(worked, renamed)).containsExactlyInAnyOrder(xena, yuri);

    long withheld = changes.position();
    jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", xena);

    assertThat(changes.unitsAnnounced(worked, withheld))
        .containsExactlyInAnyOrder(requesting, responsible);
  }

  @Test
  void anAssigneesOwnNoteIsAnnouncedToThemAlone() {
    UUID worked = order(requesting, responsible);
    seed.assignee(worked, xena, null);
    seed.assignee(worked, yuri, null);
    fingerprint(worked);
    long before = changes.position();

    jdbc.update(
        "UPDATE job_order_assignees SET note = 'my own note' WHERE job_order_id = ? AND user_id ="
            + " ?",
        worked,
        xena);

    assertThat(changes.membersAnnounced(worked, before)).containsExactly(xena);
    assertThat(changes.unitsAnnounced(worked, before)).isEmpty();
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
    return seed.order().requesting(requestingUnit).responsible(responsibleUnit).create();
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
}
