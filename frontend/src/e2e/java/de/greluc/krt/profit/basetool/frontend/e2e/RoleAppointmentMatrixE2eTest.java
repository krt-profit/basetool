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

package de.greluc.krt.profit.basetool.frontend.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end appointment matrix for the delegated role ladder (epic #800, issue #809) — the
 * defence-in-depth slice that drives the {@code OrgRoleManagementSecurityService} verdicts through
 * the real stack, complementing the unit matrix ({@code OrgRoleManagementSecurityServiceTest}) and
 * the real-DB silo proof ({@code OrgHierarchyMigrationTest}). It asserts, against authenticated
 * HTTP calls as the appointing principal:
 *
 * <ul>
 *   <li><b>Own-Bereich ladder:</b> a Bereichsleiter may appoint a Koordinator <em>in their own
 *       Bereich</em> (one rung below them).
 *   <li><b>No foreign reach (cross-Bereich silo):</b> the same Bereichsleiter is denied an
 *       appointment in a foreign Bereich.
 *   <li><b>No self / superior tier:</b> a Bereichsleiter may not appoint another Bereichsleiter
 *       (only the OL appoints that tier) — the structurally-impossible self-promotion.
 *   <li><b>Plain members appoint nothing</b> on either the Bereich endpoint or the squadron-rank
 *       endpoint.
 *   <li><b>REQ-ORG-017 silo:</b> even an admin cannot give a Staffel member a silo-leadership rank
 *       (the service guard rejects it before the V165/V187 trigger would).
 *   <li><b>Mirror (REQ-ROLE-006):</b> a successful appointment projects the account-linked seat
 *       onto the descriptive org chart.
 * </ul>
 *
 * <p><b>Fixtures.</b> Two dedicated realm users own their appointment shapes here so no shared-user
 * membership leaks across the sequentially-run stack (the #692 Phase-7 lesson): {@code
 * test-appoint-bl} is made Bereichsleiter of this suite's Bereich A, and {@code test-appoint-tgt}
 * is the Staffel-less appointment target. {@code test-member} is only ever an appointment target
 * that is <em>rejected</em> (the REQ-ORG-017 case), so its shape is never mutated — it is merely
 * ensured to hold an IRIDIUM Staffel membership so the silo guard has something to trip on.
 */
@Tag("e2e")
class RoleAppointmentMatrixE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String BL_USER = "test-appoint-bl";
  private static final String BL_PASSWORD = "test-appoint-bl-pw";
  private static final String TGT_USER = "test-appoint-tgt";
  private static final String TGT_PASSWORD = "test-appoint-tgt-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  /** The canonical seeded squadron, used as the target of the squadron-rank endpoint. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static BackendSeeder seeder;
  private static String bereichAId;
  private static String bereichBId;
  private static String blUserId;
  private static String tgtUserId;
  private static String memberUserId;

  /**
   * Seeds two Bereiche, materialises the dedicated users, ensures {@code test-member} holds a
   * Staffel membership, and grants {@code test-appoint-bl} the Bereichsleiter role on Bereich A.
   */
  @BeforeAll
  static void setUp() {
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    blUserId = seeder.getUserId(BL_USER, BL_PASSWORD);
    tgtUserId = seeder.getUserId(TGT_USER, TGT_PASSWORD);
    seeder.ensureIridiumMembership(MEMBER_USER, MEMBER_PASSWORD);
    memberUserId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);

    bereichAId = seeder.createBereich(ADMIN_USER, ADMIN_PASSWORD, "E2E Appoint Bereich A", "EAPA");
    bereichBId = seeder.createBereich(ADMIN_USER, ADMIN_PASSWORD, "E2E Appoint Bereich B", "EAPB");

    seeder.addBereichLeader(ADMIN_USER, ADMIN_PASSWORD, bereichAId, blUserId, "LEITER");
  }

  /** A Bereichsleiter may appoint a Koordinator one rung below them in their own Bereich. */
  @Test
  void bereichsleiterAppointsKoordinatorInOwnBereich() {
    int status = appointBereichRole(BL_USER, BL_PASSWORD, bereichAId, tgtUserId, "KOORDINATOR");
    assertTrue(
        status >= 200 && status < 300,
        "a Bereichsleiter may appoint a Koordinator in their own Bereich (HTTP " + status + ")");
  }

  /** Strict silo: a Bereichsleiter of A may not appoint anyone in a foreign Bereich B. */
  @Test
  void bereichsleiterCannotAppointInForeignBereich() {
    assertEquals(
        403,
        appointBereichRole(BL_USER, BL_PASSWORD, bereichBId, tgtUserId, "KOORDINATOR"),
        "a Bereichsleiter of A must not appoint into a foreign Bereich B");
  }

  /**
   * No self / superior tier: appointing a Bereichsleiter is the OL's rung, never a Bereichsleiter's
   * — the structurally-impossible self-promotion.
   */
  @Test
  void bereichsleiterCannotAppointAnotherBereichsleiter() {
    assertEquals(
        403,
        appointBereichRole(BL_USER, BL_PASSWORD, bereichAId, tgtUserId, "LEITER"),
        "a Bereichsleiter must not appoint another Bereichsleiter (only the OL may)");
  }

  /**
   * Squadron-rank gate denial: a plain Staffel member holds no rung on the squadron-rank endpoint
   * either (they are neither the parent Bereichsleiter for a Staffelleiter appointment nor the
   * squadron's Staffelleiter for a Kommando rank), so the delegated gate denies with 403 — a
   * topology-independent denial that does not depend on which Bereich a squadron sits under. The
   * body carries a {@code version} because {@code AssignSquadronRankRequest} requires it
   * ({@code @NotNull}); any value reaches the {@code @PreAuthorize} gate, which denies before the
   * service (and thus before the optimistic-lock check) ever runs.
   */
  @Test
  void plainMemberCannotAssignSquadronRank() {
    int status =
        seeder.putForStatus(
            MEMBER_USER,
            MEMBER_PASSWORD,
            "/api/v1/squadrons/" + IRIDIUM_SQUADRON_ID + "/ranks/" + tgtUserId,
            "{\"role\":\"STAFFELLEITER\",\"version\":0}");
    assertEquals(403, status, "a plain Staffel member must not assign a squadron leadership rank");
  }

  /** A plain Staffel member holds no appointment rung at all. */
  @Test
  void plainMemberCannotAppointAnyRole() {
    assertEquals(
        403,
        appointBereichRole(MEMBER_USER, MEMBER_PASSWORD, bereichAId, tgtUserId, "KOORDINATOR"),
        "a plain Staffel member must not appoint any leadership role");
  }

  /**
   * REQ-ORG-017 silo: even an admin (who passes the delegated gate) cannot give a Staffel member a
   * silo-leadership rank — the service guard rejects it with a 400 before the DB trigger fires.
   */
  @Test
  void appointingSiloRankForStaffelMemberIsRejected() {
    assertEquals(
        400,
        appointBereichRole(ADMIN_USER, ADMIN_PASSWORD, bereichAId, memberUserId, "KOORDINATOR"),
        "a Staffel member may not be given a Bereich leadership rank (REQ-ORG-017)");
  }

  /**
   * Mirror (REQ-ROLE-006): the Bereichsleiter appointment seeded in {@link #setUp()} projects
   * {@code test-appoint-bl}'s account-linked seat onto the descriptive org chart, so the chart read
   * carries that user id.
   */
  @Test
  void bereichAppointmentMirrorsAccountSeatOntoOrgChart() {
    String chart = seeder.getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/org-chart");
    assertTrue(
        chart.contains(blUserId),
        "the org chart must mirror the appointed Bereichsleiter's account seat (REQ-ROLE-006)");
  }

  /**
   * Issues {@code POST /api/v1/org-hierarchy/bereiche/{id}/members} as the given principal and
   * returns the HTTP status, so a test can assert the delegated-appointment verdict.
   *
   * @param username the appointing principal's Keycloak username
   * @param password the appointing principal's Keycloak password
   * @param bereichId the Bereich to appoint into
   * @param userId the target user
   * @param role the Bereich leadership role ({@code LEITER} / {@code KOORDINATOR} / {@code
   *     OPERATOR})
   * @return the HTTP status of the appointment attempt
   */
  private static int appointBereichRole(
      String username, String password, String bereichId, String userId, String role) {
    return seeder.postForStatus(
        username,
        password,
        "/api/v1/org-hierarchy/bereiche/" + bereichId + "/members",
        "{\"userId\":\"" + userId + "\",\"role\":\"" + role + "\"}");
  }
}
