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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Officer reach is bounded by the leadership seat (REQ-SEC-065, REQ-ORG-004): the Staffelleiter of
 * unit A is refused on unit B's mission, job order and inventory, and the officer member list shows
 * only A's members.
 *
 * <p>{@code test-officer} leads a fresh Staffel A only; the canonical IRIDIUM squadron plays unit
 * B, with {@code test-member} as its ordinary member.
 */
@Tag("e2e")
class OfficerReachCrossUnitE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static BackendSeeder seeder;
  private static String staffelAId;
  private static String missionBId;
  private static String orderBId;
  private static String materialId;
  private static String locationId;
  private static String officerId;
  private static String memberId;

  /** Seats the officer in Staffel A and seeds a mission, a job order and a member in unit B. */
  @BeforeAll
  static void setUp() {
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
    officerId = seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
    memberId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);

    staffelAId = seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Reach A", "ERCA");
    seeder.assignStaffelleiterSeat(ADMIN_USER, ADMIN_PASSWORD, officerId, staffelAId, true);
    seeder.assignStaffelMembership(ADMIN_USER, ADMIN_PASSWORD, memberId, IRIDIUM_ID, false, false);

    missionBId = seeder.createMission(ADMIN_USER, ADMIN_PASSWORD, "E2E Reach B Mission", true);
    materialId = seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Reach Material");
    locationId = seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E Reach Location");
    orderBId =
        seeder.createJobOrder(
            ADMIN_USER, ADMIN_PASSWORD, IRIDIUM_ID, "E2E Reach B Order", materialId, 650, 50);
  }

  /** Changing a mission's owner stays admin-only; a seat in another unit does not lift it. */
  @Test
  void seatHolderOfAIsRefusedTheOwnerChangeOfBMission() {
    assertRefused(
        seeder.putForStatus(
            OFFICER_USER,
            OFFICER_PASSWORD,
            "/api/v1/missions/" + missionBId + "/owner",
            "{\"userId\":\"" + officerId + "\",\"version\":0}"));
  }

  /** B's squadron-private job order is neither readable nor writable for A's seat holder. */
  @Test
  void seatHolderOfAIsRefusedBsJobOrder() {
    assertRefused(
        seeder.attemptGetStatus(OFFICER_USER, OFFICER_PASSWORD, "/api/v1/orders/" + orderBId));
    assertRefused(
        seeder.postForStatus(
            OFFICER_USER,
            OFFICER_PASSWORD,
            "/api/v1/orders/" + orderBId + "/assignees/" + officerId,
            "{}"));
  }

  /** Stocking inventory for unit B is refused; stocking it for the seat's own unit is not. */
  @Test
  void seatHolderOfAMayNotStockInventoryForB() {
    assertEquals(
        400,
        seeder.attemptCreateInventoryStatus(
            OFFICER_USER, OFFICER_PASSWORD, materialId, locationId, 700, 5, IRIDIUM_ID),
        "a foreign owning unit is rejected");
    int own =
        seeder.attemptCreateInventoryStatus(
            OFFICER_USER, OFFICER_PASSWORD, materialId, locationId, 700, 5, staffelAId);
    assertTrue(own >= 200 && own < 300, "the seat's own unit is accepted, was HTTP " + own);
  }

  /** Unpinned, the evaluation member list covers A and leaves B's members out. */
  @Test
  void evaluatableMembersExcludeBMembers() {
    String path = "/api/v1/promotion/evaluations/members?size=200";
    assertEquals(200, seeder.attemptGetStatus(OFFICER_USER, OFFICER_PASSWORD, path));
    String body = seeder.getBody(OFFICER_USER, OFFICER_PASSWORD, path);
    boolean listsB = false;
    for (JsonElement row :
        JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("content")) {
      listsB |= memberId.equals(row.getAsJsonObject().get("id").getAsString());
    }
    assertFalse(listsB, "a member of unit B must not appear in A's evaluation list");
  }

  /**
   * Asserts a refusal.
   *
   * @param status the HTTP status of the attempt
   */
  private static void assertRefused(int status) {
    assertTrue(status == 403 || status == 404, "expected 403 or 404 but was " + status);
  }
}
