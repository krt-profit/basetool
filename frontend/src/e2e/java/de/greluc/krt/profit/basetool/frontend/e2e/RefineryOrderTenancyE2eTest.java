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

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Multi-org-unit tenancy matrix for refinery orders (UC-21, REQ-ORG-003): who may see, create, edit
 * and store an order across every membership profile.
 *
 * <p>Refinery is strict-staffel, so an order never escapes its owning org unit, not even via a
 * linked public mission; stored Lager rows are stamped onto the assignee's org unit (REQ-ORG-004).
 *
 * <p>Profiles: {@code test-admin} and {@code test-member} (Staffel A), {@code test-both} (Staffel B
 * + SK X) and {@code test-none} (no membership). {@code test-sk} is not touched, because another
 * suite relies on its membership shape. Assertions call the scoped backend endpoints as each user
 * through {@link BackendSeeder}.
 */
@Tag("e2e")
class RefineryOrderTenancyE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String BOTH_USER = "test-both";
  private static final String BOTH_PASSWORD = "test-both-pw";
  private static final String NONE_USER = "test-none";
  private static final String NONE_PASSWORD = "test-none-pw";

  /** Canonical IRIDIUM Squadron — "Staffel A" in this matrix; {@code test-member}'s home. */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The catalog-seeded refinery-hosting location every order in this suite runs at. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String squadronBId;
  private static String skXId;

  private static String hubLocationId;
  private static String materialId;
  private static String storeMaterialId;

  private static String orderA;
  private static String orderB;
  private static String orderSk;
  private static String orderOwnerless;
  private static String orderStore;

  private static String bacMissionId;
  private static String bacOrderId;

  /**
   * Launches the browser and (ephemeral stack only) seeds the four membership profiles, this
   * suite's own Staffel B + SK X, the refinery reference data, and one order per owning scope plus
   * the store-stamping and BAC-004 fixtures.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
    seeder.assignStaffelMembership(
        ADMIN_USER,
        ADMIN_PASSWORD,
        seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
        IRIDIUM_ID,
        false,
        false);

    squadronBId =
        seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Refinery Tenancy B", "ERTB");
    skXId =
        seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Refinery Tenancy SK", "ERTS");

    String bothId = seeder.getUserId(BOTH_USER, BOTH_PASSWORD);
    seeder.assignStaffelMembership(ADMIN_USER, ADMIN_PASSWORD, bothId, squadronBId, false, false);
    seeder.addSpecialCommandMember(ADMIN_USER, ADMIN_PASSWORD, skXId, bothId);
    seeder.getUserId(NONE_USER, NONE_PASSWORD);

    hubLocationId = seeder.findLocationIdByName(ADMIN_USER, ADMIN_PASSWORD, REFINERY_HUB);
    materialId =
        seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Refinery Tenancy Mat");
    storeMaterialId =
        seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Refinery Tenancy Store Mat");

    orderA =
        seeder.createRefineryOrder(
            ADMIN_USER, ADMIN_PASSWORD, hubLocationId, materialId, IRIDIUM_ID, null);
    orderB =
        seeder.createRefineryOrder(
            BOTH_USER, BOTH_PASSWORD, hubLocationId, materialId, squadronBId, null);
    orderSk =
        seeder.createRefineryOrder(
            BOTH_USER, BOTH_PASSWORD, hubLocationId, materialId, skXId, null);
    orderOwnerless =
        seeder.createRefineryOrder(NONE_USER, NONE_PASSWORD, hubLocationId, materialId, null, null);
    orderStore =
        seeder.createRefineryOrder(
            BOTH_USER, BOTH_PASSWORD, hubLocationId, storeMaterialId, squadronBId, null);

    bacMissionId =
        seeder.createMission(MEMBER_USER, MEMBER_PASSWORD, "E2E Refinery BAC Mission", false);
    seeder.addRegisteredParticipant(
        MEMBER_USER, MEMBER_PASSWORD, bacMissionId, seeder.getUserId(ADMIN_USER, ADMIN_PASSWORD));
    bacOrderId =
        seeder.createRefineryOrder(
            ADMIN_USER, ADMIN_PASSWORD, hubLocationId, materialId, IRIDIUM_ID, bacMissionId);
  }

  /** Releases the browser and the Playwright driver process. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** An admin without an active pin sees every scope's orders, the ownerless one included. */
  @Test
  void adminWithoutPinSeesEveryScope() {
    assertTrue(seenInAll(ADMIN_USER, ADMIN_PASSWORD, orderA), "admin sees the Staffel A order");
    assertTrue(seenInAll(ADMIN_USER, ADMIN_PASSWORD, orderB), "admin sees the Staffel B order");
    assertTrue(seenInAll(ADMIN_USER, ADMIN_PASSWORD, orderSk), "admin sees the SK X order");
    assertTrue(
        seenInAll(ADMIN_USER, ADMIN_PASSWORD, orderOwnerless), "admin sees the ownerless order");
  }

  /** A squadron-only member sees only their own squadron's order — never another scope's. */
  @Test
  void squadronMemberSeesOnlyOwnSquadron() {
    assertTrue(
        seenInAll(MEMBER_USER, MEMBER_PASSWORD, orderA), "A-member sees the Staffel A order");
    assertFalse(
        seenInAll(MEMBER_USER, MEMBER_PASSWORD, orderB),
        "A-member must not see the Staffel B order");
    assertFalse(
        seenInAll(MEMBER_USER, MEMBER_PASSWORD, orderSk), "A-member must not see the SK X order");
    assertFalse(
        seenInAll(MEMBER_USER, MEMBER_PASSWORD, orderOwnerless),
        "A-member must not see the ownerless order");
  }

  /**
   * A member of both a squadron and an SK sees the union of both pools' orders, and nothing else —
   * the {@code memberOrgUnitIds = squadron ∪ SK} rule.
   */
  @Test
  void memberOfSquadronAndSkSeesBothPools() {
    assertTrue(seenInAll(BOTH_USER, BOTH_PASSWORD, orderB), "B+SK member sees the Staffel B order");
    assertTrue(seenInAll(BOTH_USER, BOTH_PASSWORD, orderSk), "B+SK member sees the SK X order");
    assertFalse(
        seenInAll(BOTH_USER, BOTH_PASSWORD, orderA),
        "B+SK member must not see the Staffel A order");
    assertFalse(
        seenInAll(BOTH_USER, BOTH_PASSWORD, orderOwnerless),
        "B+SK member must not see the ownerless order");
  }

  /**
   * A membershipless user sees no shared order in the org-scoped list, but still owns (and sees, in
   * their personal list) the ownerless order they created.
   */
  @Test
  void membershiplessSeesNoSharedButOwnsOwnerless() {
    assertFalse(seenInAll(NONE_USER, NONE_PASSWORD, orderA), "membershipless sees no A order");
    assertFalse(seenInAll(NONE_USER, NONE_PASSWORD, orderB), "membershipless sees no B order");
    assertFalse(seenInAll(NONE_USER, NONE_PASSWORD, orderSk), "membershipless sees no SK order");
    assertFalse(
        seenInAll(NONE_USER, NONE_PASSWORD, orderOwnerless),
        "membershipless sees no ownerless order in the org-scoped list");
    assertTrue(
        seenInMy(NONE_USER, NONE_PASSWORD, orderOwnerless),
        "but the creator sees their ownerless order in their personal list");
  }

  /**
   * An ownerless order ({@code owningOrgUnit == null}) is visible in the org-scoped list only to an
   * admin without a pin; its owner still finds it in their personal list, and no member of any unit
   * sees it.
   */
  @Test
  void ownerlessOrderVisibleToAdminAndOwnerOnly() {
    assertTrue(
        seenInAll(ADMIN_USER, ADMIN_PASSWORD, orderOwnerless),
        "admin (no pin) sees the ownerless order in the org-scoped list");
    assertTrue(
        seenInMy(NONE_USER, NONE_PASSWORD, orderOwnerless),
        "the owner sees their ownerless order in their personal list");
    assertFalse(
        seenInAll(MEMBER_USER, MEMBER_PASSWORD, orderOwnerless),
        "a squadron member does not see the ownerless order");
  }

  /** An admin who pins one org unit is scoped to it exactly like a member, not all-seeing. */
  @Test
  void adminPinnedToOneScopeIsScopedLikeAMember() {
    assertTrue(
        seenInAllPinned(ADMIN_USER, ADMIN_PASSWORD, orderA, IRIDIUM_ID),
        "admin pinned to A sees A");
    assertFalse(
        seenInAllPinned(ADMIN_USER, ADMIN_PASSWORD, orderB, IRIDIUM_ID),
        "admin pinned to A must not see B");
    assertTrue(
        seenInAllPinned(ADMIN_USER, ADMIN_PASSWORD, orderB, squadronBId),
        "admin pinned to B sees B");
    assertFalse(
        seenInAllPinned(ADMIN_USER, ADMIN_PASSWORD, orderA, squadronBId),
        "admin pinned to B must not see A");
  }

  /**
   * Create-time OrgUnit stamping follows the membership matrix (REQ-ORG-004): a single-membership
   * user auto-stamps, a multi-membership user must pick (else 400), a membershipless user yields an
   * ownerless order, and any foreign pick is rejected.
   */
  @Test
  void createStampingFollowsTheMembershipMatrix() {
    assertCreated(
        attemptCreate(MEMBER_USER, MEMBER_PASSWORD, null), "A-only member auto-stamps Staffel A");
    assertCreated(
        attemptCreate(NONE_USER, NONE_PASSWORD, null),
        "membershipless user creates an ownerless order");
    assertEquals(
        400,
        attemptCreate(BOTH_USER, BOTH_PASSWORD, null),
        "multi-membership user without a pick must be rejected");
    assertCreated(
        attemptCreate(BOTH_USER, BOTH_PASSWORD, squadronBId),
        "multi-membership user picking an own unit succeeds");
    assertEquals(
        400,
        attemptCreate(MEMBER_USER, MEMBER_PASSWORD, skXId),
        "A-only member picking a foreign SK must be rejected");
    assertEquals(
        400,
        attemptCreate(NONE_USER, NONE_PASSWORD, IRIDIUM_ID),
        "membershipless user picking any unit must be rejected");
  }

  /**
   * The strict-staffel gates reject a foreign-scope viewer on every per-resource endpoint: reading
   * (403), editing (403) and storing (403) an order owned by a unit the caller does not belong to.
   * {@code test-none} has no membership, so the IRIDIUM-owned {@code orderA} is foreign to it.
   */
  @Test
  void foreignScopeViewerIsGatedFromReadEditAndStore() {
    assertEquals(
        403,
        seeder.attemptGetStatus(NONE_USER, NONE_PASSWORD, "/api/v1/refinery-orders/" + orderA),
        "a foreign-scope viewer must not read the order detail");
    assertEquals(
        403,
        seeder.attemptUpdateRefineryOrderStatus(
            NONE_USER, NONE_PASSWORD, orderA, hubLocationId, materialId, 0),
        "a foreign-scope viewer must not edit the order");
    assertEquals(
        403,
        seeder.attemptStoreRefineryOrderStatus(
            NONE_USER, NONE_PASSWORD, orderA, materialId, hubLocationId, 750, 1.0, null, null),
        "a foreign-scope viewer must not store the order");
  }

  /**
   * The per-resource owner gate: a member of the order's own squadron who is neither the owner nor
   * a logistician still cannot edit it (the org gate passes, the service owner check rejects with
   * 403). {@code orderA} is owned by {@code test-admin}; {@code test-member} shares Staffel A but
   * is a plain member.
   */
  @Test
  void sameSquadronNonOwnerCannotEdit() {
    long version = seeder.getRefineryOrderVersion(MEMBER_USER, MEMBER_PASSWORD, orderA);
    assertEquals(
        403,
        seeder.attemptUpdateRefineryOrderStatus(
            MEMBER_USER, MEMBER_PASSWORD, orderA, hubLocationId, materialId, version),
        "a non-owner non-logistician squadron member must not edit another member's order");
  }

  /**
   * Storing an order stamps each resulting Lager row onto the <em>assignee's</em> owning org unit,
   * not the order's: storing a Staffel-B order to an IRIDIUM assignee yields IRIDIUM stock, visible
   * to the IRIDIUM member and invisible to the B+SK member.
   */
  @Test
  void storeStampsTheRowToTheAssigneesOrgUnit() {
    seeder.storeRefineryOrder(
        ADMIN_USER,
        ADMIN_PASSWORD,
        orderStore,
        storeMaterialId,
        hubLocationId,
        750,
        1.0,
        seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
        null);
    assertTrue(
        materialVisibleInLager(MEMBER_USER, MEMBER_PASSWORD, storeMaterialId),
        "the IRIDIUM assignee sees the stored row in their squadron's Lager");
    assertFalse(
        materialVisibleInLager(BOTH_USER, BOTH_PASSWORD, storeMaterialId),
        "the B+SK member must not see the IRIDIUM-stamped row — proving the assignee's scope won");
  }

  /**
   * Storing to a multi-membership assignee is rejected with 400: the store form carries no
   * per-output org-unit picker, so the resolver cannot decide which of the assignee's pools to
   * stamp.
   */
  @Test
  void storeToMultiMembershipAssigneeIsRejected() {
    assertEquals(
        400,
        seeder.attemptStoreRefineryOrderStatus(
            ADMIN_USER,
            ADMIN_PASSWORD,
            orderA,
            materialId,
            hubLocationId,
            750,
            1.0,
            seeder.getUserId(BOTH_USER, BOTH_PASSWORD),
            null),
        "storing to a multi-membership assignee with no picker must be rejected");
  }

  /**
   * The mission refinery roll-up is org-scoped (BAC-004): a viewer scoped to Staffel B sees no
   * Staffel-A refinery order on a public A-mission, even though the mission itself is cross-staffel
   * visible. An admin without a pin (all-scope) still sees it.
   */
  @Test
  void missionRefineryRollupIsOrgScoped() {
    String missionPath = "/api/v1/refinery-orders/mission/" + bacMissionId;
    assertTrue(
        seeder.getBody(ADMIN_USER, ADMIN_PASSWORD, missionPath).contains(bacOrderId),
        "an all-scope admin sees the A-owned refinery order on the public mission");
    assertFalse(
        seeder
            .getBodyWithActiveOrgUnit(ADMIN_USER, ADMIN_PASSWORD, missionPath, squadronBId)
            .contains(bacOrderId),
        "a viewer scoped to Staffel B must not see the A-owned refinery order via the public"
            + " mission");
  }

  /**
   * UI boundary: a squadron-only member loading {@code /refinery-orders} sees their own squadron's
   * order row but not a foreign squadron's.
   */
  @Test
  void squadronMemberUiListShowsOnlyOwnScope() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        page.waitForLoadState();
        assertThat(page.locator("a[href$='/refinery-orders/" + orderA + "']").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("a[href$='/refinery-orders/" + orderB + "']")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-tenancy-member-list");
        throw failure;
      }
    }
  }

  /**
   * Reports whether the given user's org-scoped refinery list ({@code /api/v1/refinery-orders/all})
   * contains the order with the given id.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param orderId the refinery order id to look for
   * @return {@code true} if the viewer's org-scoped list lists that order
   */
  private static boolean seenInAll(String username, String password, String orderId) {
    return seeder
        .getBody(username, password, "/api/v1/refinery-orders/all?size=1000")
        .contains(orderId);
  }

  /**
   * Reports whether the given user — pinned to {@code pinOrgUnitId} via the {@code
   * X-Active-Org-Unit-Id} header — sees the order with the given id in their org-scoped refinery
   * list.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param orderId the refinery order id to look for
   * @param pinOrgUnitId the OrgUnit to pin as the active scope
   * @return {@code true} if the pinned viewer's org-scoped list lists that order
   */
  private static boolean seenInAllPinned(
      String username, String password, String orderId, String pinOrgUnitId) {
    return seeder
        .getBodyWithActiveOrgUnit(
            username, password, "/api/v1/refinery-orders/all?size=1000", pinOrgUnitId)
        .contains(orderId);
  }

  /**
   * Reports whether the given user's personal refinery list ({@code /api/v1/refinery-orders/
   * my-orders}) contains the order with the given id.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param orderId the refinery order id to look for
   * @return {@code true} if the viewer's personal list lists that order
   */
  private static boolean seenInMy(String username, String password, String orderId) {
    return seeder
        .getBody(username, password, "/api/v1/refinery-orders/my-orders?size=1000")
        .contains(orderId);
  }

  /**
   * Attempts to create a refinery order of the shared input material as the given user with the
   * given owner pick, returning the HTTP status.
   *
   * @param username the creating user's Keycloak username
   * @param password the creating user's Keycloak password
   * @param owningOrgUnitId the picked owner OrgUnit id, or {@code null} for the no-pick case
   * @return the create attempt's HTTP status
   */
  private static int attemptCreate(String username, String password, String owningOrgUnitId) {
    return seeder.attemptCreateRefineryOrderStatus(
        username, password, hubLocationId, materialId, owningOrgUnitId);
  }

  /**
   * Asserts a create attempt returned a 2xx success status (the exact code — 200 vs 201 — is
   * irrelevant to the stamping outcome under test).
   *
   * @param status the HTTP status returned by a create attempt
   * @param message the assertion message
   */
  private static void assertCreated(int status, String message) {
    assertTrue(status >= 200 && status < 300, message + " (was HTTP " + status + ")");
  }

  /**
   * Reports whether the given user sees any shared stock of {@code materialId} in the global
   * Lager-View ({@code /inventory/all/grouped}).
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param materialId the material to probe
   * @return {@code true} if the viewer's global view lists a group for that material
   */
  private static boolean materialVisibleInLager(
      String username, String password, String materialId) {
    return !JsonParser.parseString(
            seeder.getBody(
                username, password, "/api/v1/inventory/all/grouped?materialIds=" + materialId))
        .getAsJsonArray()
        .isEmpty();
  }
}
