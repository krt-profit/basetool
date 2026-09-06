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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Multi-org-unit tenancy matrix for the squadron Lager (REQ-ORG-002/003/004/008): who may SEE,
 * CREATE and EDIT inventory across every membership profile — a squadron-only member, an SK-only
 * member, a member of both a squadron and an SK, an admin, and a user with no membership at all —
 * plus the unauthenticated guest. The direct Lager-View is <em>strict-staffel</em>: an item is
 * scoped to its {@code owning_org_unit_id} pool and never escapes it (REQ-ORG-003).
 *
 * <p><b>Fixtures.</b> Five real Keycloak users carry distinct membership profiles, assigned via the
 * REST seeder: {@code test-admin} (ADMIN, IRIDIUM = Staffel A), {@code test-member} (Staffel A
 * only), {@code test-both} (Staffel B + SK X), {@code test-sk} (SK X only), {@code test-none} (no
 * membership). One non-personal item is seeded per owner on its own material so a material maps 1:1
 * to an owner — Staffel A, Staffel B, SK X, and an ownerless ({@code owningOrgUnit = null}) item
 * recorded by the membershipless user. Items are created <em>as</em> the user homed in the target
 * unit so the create-time resolver stamps the intended owner.
 *
 * <p><b>Drive via UI, verify via API.</b> Mirroring {@code CrossStaffelJobOrderE2eTest}, the
 * visibility/stamping/edit matrix is asserted by calling the scoped backend endpoints as each user
 * through {@link BackendSeeder} — the established, race-free way to assert tenancy boundaries —
 * with the boundary additionally driven through the real {@code /inventory/all} UI for a
 * representative viewer and for the guest redirect. The admin pin is exercised by sending the
 * {@code X-Active-Org-Unit-Id} header the frontend relays, which the backend honours as the active
 * scope.
 *
 * <p><b>The visibility grid this asserts</b> — for a non-personal item owned by org unit O, in the
 * global Lager-View ({@code /inventory/all}):
 *
 * <ul>
 *   <li>Admin without a pin → sees every unit's stock (incl. ownerless rows).
 *   <li>Admin pinned to O → scoped to O exactly like a member.
 *   <li>Member of O → sees O's stock; a member of another unit does not.
 *   <li>Member of (squadron + SK) → sees the union of both.
 *   <li>Membershipless user / guest → sees no shared stock.
 *   <li>Ownerless stock → only the admin-without-pin and the owning user (in their personal view).
 * </ul>
 *
 * The personal view ({@code /inventory/my}) is purely owner-scoped: a user always sees their own
 * rows regardless of which unit owns them, and never another user's.
 */
@Tag("e2e")
class InventoryTenancyE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String BOTH_USER = "test-both";
  private static final String BOTH_PASSWORD = "test-both-pw";
  private static final String SK_USER = "test-sk";
  private static final String SK_PASSWORD = "test-sk-pw";
  private static final String NONE_USER = "test-none";
  private static final String NONE_PASSWORD = "test-none-pw";

  /** Canonical IRIDIUM Squadron — "Staffel A" in this matrix; {@code test-member}'s home. */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final int SEED_QUALITY = 750;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  // Seeded org units.
  private static String squadronBId;
  private static String skXId;

  // Per-owner materials (one item each, so a material maps 1:1 to an owner in the grouped view).
  private static String matAId; // owned by Staffel A (IRIDIUM)
  private static String matBId; // owned by Staffel B
  private static String matSkId; // owned by SK X
  private static String matOwnerlessId; // ownerless (owningOrgUnit == null)
  private static String matStampId; // used only by the create-stamping probes
  private static String matEditId; // used only by the edit-gate probe

  // The B-owned item the edit-gate test targets (kept separate so its mutation isolates).
  private static String editItemId;

  // REQ-ORG-011 owner-escape fixture: an item owned by test-member but stamped to an SK the member
  // no longer belongs to (they joined the SK, recorded the item, then left — Staffel A stays, so
  // the
  // reconciler keeps the stamp). Kept on its own material so no other probe touches it.
  private static String ownerMoveItemId;

  // Shared storage location for all seeded rows and the create-stamping probes.
  private static String locId;

  /**
   * Launches the browser and (ephemeral stack only) seeds the five membership profiles, two
   * squadrons + one SK, and one non-personal item per owner (A, B, SK X, ownerless) on its own
   * material, plus a separate B-owned item for the edit-gate probe.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();

    // --- Memberships -------------------------------------------------------------------------
    seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD); // admin in Staffel A
    seeder.assignStaffelMembership(
        ADMIN_USER,
        ADMIN_PASSWORD,
        seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
        IRIDIUM_ID,
        false,
        false); // test-member: Staffel A only

    squadronBId =
        seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Staffel B", "ETSB");
    skXId = seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy SK X", "ETSX");

    // test-both gets a dedicated profile (Staffel B + SK X). A dedicated user — not the shared
    // test-officer — so the extra SK membership never leaks into sibling suites that rely on
    // test-officer being single-membership (e.g. mission auto-stamping in
    // PublicMissionCrossStaffel).
    String bothId = seeder.getUserId(BOTH_USER, BOTH_PASSWORD);
    seeder.assignStaffelMembership(ADMIN_USER, ADMIN_PASSWORD, bothId, squadronBId, false, false);
    seeder.addSpecialCommandMember(ADMIN_USER, ADMIN_PASSWORD, skXId, bothId);

    seeder.addSpecialCommandMember(
        ADMIN_USER,
        ADMIN_PASSWORD,
        skXId,
        seeder.getUserId(SK_USER, SK_PASSWORD)); // test-sk: SK X only
    seeder.getUserId(NONE_USER, NONE_PASSWORD); // materialise test-none; leave it membershipless

    // --- Reference data + one item per owner -------------------------------------------------
    locId = seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Loc");
    matAId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat A");
    matBId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat B");
    matSkId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat SK");
    matOwnerlessId =
        seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat Ownerless");
    matStampId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat Stamp");
    matEditId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat Edit");

    // Created AS the user homed in the target unit so the resolver stamps that owner.
    seeder.createInventoryItem(MEMBER_USER, MEMBER_PASSWORD, matAId, locId, SEED_QUALITY, 100);
    seeder.createInventoryItemOwnedBy(
        BOTH_USER, BOTH_PASSWORD, matBId, locId, SEED_QUALITY, 100, squadronBId);
    seeder.createInventoryItem(SK_USER, SK_PASSWORD, matSkId, locId, SEED_QUALITY, 100);
    seeder.createInventoryItem(NONE_USER, NONE_PASSWORD, matOwnerlessId, locId, SEED_QUALITY, 100);
    editItemId =
        seeder.createInventoryItemOwnedBy(
            BOTH_USER, BOTH_PASSWORD, matEditId, locId, SEED_QUALITY, 100, squadronBId);

    // --- REQ-ORG-011 owner-escape fixture ----------------------------------------------------
    // test-member (Staffel A) briefly joins a fresh SK Y, records an item stamped to SK Y, then
    // leaves SK Y. Because Staffel A remains, the reconciler keeps the SK stamp (REQ-INV-004), so
    // test-member ends up owning an SK-Y-stamped item without being an SK Y member — the "owner
    // switched / left the owning org unit while the entry stays booked to it" case.
    String skYId =
        seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy SK Y", "ETSY");
    String matMoveId =
        seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Mat Move");
    String memberId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
    seeder.addSpecialCommandMember(ADMIN_USER, ADMIN_PASSWORD, skYId, memberId);
    ownerMoveItemId =
        seeder.createInventoryItemOwnedBy(
            MEMBER_USER, MEMBER_PASSWORD, matMoveId, locId, SEED_QUALITY, 100, skYId);
    seeder.removeSpecialCommandMember(ADMIN_USER, ADMIN_PASSWORD, skYId, memberId);
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

  /** An admin without an active pin sees every org unit's shared stock, ownerless rows included. */
  @Test
  void adminWithoutPinSeesEveryOrgUnitStock() {
    assertTrue(visibleInAll(ADMIN_USER, ADMIN_PASSWORD, matAId), "admin sees Staffel A stock");
    assertTrue(visibleInAll(ADMIN_USER, ADMIN_PASSWORD, matBId), "admin sees Staffel B stock");
    assertTrue(visibleInAll(ADMIN_USER, ADMIN_PASSWORD, matSkId), "admin sees SK X stock");
    assertTrue(
        visibleInAll(ADMIN_USER, ADMIN_PASSWORD, matOwnerlessId), "admin sees ownerless stock");
  }

  /** A squadron-only member sees only their own squadron's stock — never another unit's. */
  @Test
  void squadronMemberSeesOnlyTheirSquadronStock() {
    assertTrue(visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matAId), "A-member sees Staffel A stock");
    assertFalse(
        visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matBId),
        "A-member must not see Staffel B stock");
    assertFalse(
        visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matSkId), "A-member must not see SK X stock");
    assertFalse(
        visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matOwnerlessId),
        "A-member must not see ownerless stock");
  }

  /** A member of both a squadron and an SK sees the union of both pools, and nothing else. */
  @Test
  void memberOfSquadronAndSkSeesUnionOfBoth() {
    assertTrue(visibleInAll(BOTH_USER, BOTH_PASSWORD, matBId), "B+SK member sees Staffel B stock");
    assertTrue(visibleInAll(BOTH_USER, BOTH_PASSWORD, matSkId), "B+SK member sees SK X stock");
    assertFalse(
        visibleInAll(BOTH_USER, BOTH_PASSWORD, matAId), "B+SK member must not see Staffel A stock");
    assertFalse(
        visibleInAll(BOTH_USER, BOTH_PASSWORD, matOwnerlessId),
        "B+SK member must not see ownerless stock");
  }

  /** An SK-only member (no squadron) sees only that SK's stock. */
  @Test
  void skOnlyMemberSeesOnlySkStock() {
    assertTrue(visibleInAll(SK_USER, SK_PASSWORD, matSkId), "SK-only member sees SK X stock");
    assertFalse(
        visibleInAll(SK_USER, SK_PASSWORD, matAId), "SK-only member must not see Staffel A stock");
    assertFalse(
        visibleInAll(SK_USER, SK_PASSWORD, matBId), "SK-only member must not see Staffel B stock");
    assertFalse(
        visibleInAll(SK_USER, SK_PASSWORD, matOwnerlessId),
        "SK-only member must not see ownerless stock");
  }

  /** A user with no membership sees no shared stock at all in the global Lager-View. */
  @Test
  void membershiplessUserSeesNoSharedStock() {
    assertFalse(visibleInAll(NONE_USER, NONE_PASSWORD, matAId), "membershipless sees no A stock");
    assertFalse(visibleInAll(NONE_USER, NONE_PASSWORD, matBId), "membershipless sees no B stock");
    assertFalse(visibleInAll(NONE_USER, NONE_PASSWORD, matSkId), "membershipless sees no SK stock");
    assertFalse(
        visibleInAll(NONE_USER, NONE_PASSWORD, matOwnerlessId),
        "membershipless sees no ownerless stock in the global view");
  }

  /**
   * Ownerless stock ({@code owningOrgUnit == null}) is visible in the global view only to an admin
   * without a pin; its owning user still finds it in their personal view, and no one else sees it.
   */
  @Test
  void ownerlessStockVisibleToAdminAndOwnerOnly() {
    assertTrue(
        visibleInAll(ADMIN_USER, ADMIN_PASSWORD, matOwnerlessId),
        "admin (no pin) sees ownerless stock globally");
    assertFalse(
        visibleInAll(NONE_USER, NONE_PASSWORD, matOwnerlessId),
        "the ownerless item's own (non-admin) creator does not see it in the global view");
    assertTrue(
        visibleInMy(NONE_USER, NONE_PASSWORD, matOwnerlessId),
        "but the creator does see their ownerless item in their personal view");
  }

  /** The personal Lager-View is scoped to the owning user — never another user's rows. */
  @Test
  void personalLagerIsScopedToTheOwningUser() {
    assertTrue(
        visibleInMy(MEMBER_USER, MEMBER_PASSWORD, matAId),
        "the A item's owner sees it in their personal view");
    assertFalse(
        visibleInMy(BOTH_USER, BOTH_PASSWORD, matAId),
        "another user must not see the A item in their personal view");
    assertFalse(
        visibleInMy(SK_USER, SK_PASSWORD, matAId),
        "an unrelated user must not see the A item in their personal view");
  }

  /** An admin who pins one org unit is scoped to it exactly like a member, not all-seeing. */
  @Test
  void adminPinnedToOneOrgUnitIsScopedLikeAMember() {
    assertTrue(
        visibleInAllPinned(ADMIN_USER, ADMIN_PASSWORD, matBId, squadronBId),
        "admin pinned to B sees B stock");
    assertFalse(
        visibleInAllPinned(ADMIN_USER, ADMIN_PASSWORD, matAId, squadronBId),
        "admin pinned to B must not see A stock");
    assertTrue(
        visibleInAllPinned(ADMIN_USER, ADMIN_PASSWORD, matAId, IRIDIUM_ID),
        "admin pinned to A sees A stock");
    assertFalse(
        visibleInAllPinned(ADMIN_USER, ADMIN_PASSWORD, matBId, IRIDIUM_ID),
        "admin pinned to A must not see B stock");
  }

  /**
   * Create-time OrgUnit stamping follows the membership matrix (REQ-ORG-004): single-membership
   * users auto-stamp, a multi-membership user must pick (else 400), a membershipless user yields an
   * ownerless item, and any foreign pick is rejected.
   */
  @Test
  void createStampingFollowsTheMembershipMatrix() {
    // Single membership → auto-stamp, no pick needed.
    assertCreated(
        attemptCreate(MEMBER_USER, MEMBER_PASSWORD, null), "A-only member auto-stamps Staffel A");
    assertCreated(attemptCreate(SK_USER, SK_PASSWORD, null), "SK-only member auto-stamps SK X");
    // Membershipless → ownerless item is allowed.
    assertCreated(
        attemptCreate(NONE_USER, NONE_PASSWORD, null),
        "membershipless user creates ownerless stock");
    // Multi-membership without a pick → forced choice → 400.
    assertEquals(
        400,
        attemptCreate(BOTH_USER, BOTH_PASSWORD, null),
        "multi-membership user without a pick must be rejected");
    // Valid pick of an own membership → stamped.
    assertCreated(
        attemptCreate(BOTH_USER, BOTH_PASSWORD, squadronBId),
        "multi-membership user picking an own unit succeeds");
    // Foreign picks → 400.
    assertEquals(
        400,
        attemptCreate(MEMBER_USER, MEMBER_PASSWORD, skXId),
        "A-only member picking a foreign SK must be rejected");
    assertEquals(
        400,
        attemptCreate(BOTH_USER, BOTH_PASSWORD, IRIDIUM_ID),
        "B+SK member picking a foreign squadron must be rejected");
    assertEquals(
        400,
        attemptCreate(NONE_USER, NONE_PASSWORD, IRIDIUM_ID),
        "membershipless user picking any unit must be rejected");
  }

  /**
   * The edit gate ({@code canEditInventoryItem}) respects org-unit scope: a viewer outside the
   * item's owning unit cannot book it out (403), while a member of the owning unit can.
   */
  @Test
  void editGateRespectsOrgUnitScope() {
    // editItemId is owned by Staffel B (version 0, freshly seeded). Out-of-scope viewers: 403.
    assertEquals(
        403,
        seeder.attemptBookOutStatus(MEMBER_USER, MEMBER_PASSWORD, editItemId, 1, 0),
        "an A-only member must not book out a B-owned item");
    assertEquals(
        403,
        seeder.attemptBookOutStatus(NONE_USER, NONE_PASSWORD, editItemId, 1, 0),
        "a membershipless user must not book out a B-owned item");
    // The owning unit's member (and item owner) passes the org-scope gate.
    assertNotEquals(
        403,
        seeder.attemptBookOutStatus(BOTH_USER, BOTH_PASSWORD, editItemId, 1, 0),
        "a member of the owning unit must pass the org-scope edit gate");
  }

  /**
   * REQ-ORG-011 owner escape: the per-user owner of an inventory item may still book it out after
   * leaving the item's owning org unit while the row stays stamped to it. {@code test-member} owns
   * {@code ownerMoveItemId} (stamped to the SK they left), so they pass the edit gate; non-owners
   * outside that SK stay rejected (403).
   */
  @Test
  void ownerRetainsEditAfterLeavingOwningOrgUnit() {
    // Non-owners outside the item's owning SK are still blocked — the escape is per-owner.
    assertEquals(
        403,
        seeder.attemptBookOutStatus(BOTH_USER, BOTH_PASSWORD, ownerMoveItemId, 1, 0),
        "a non-owner outside the owning SK must not book out the item");
    assertEquals(
        403,
        seeder.attemptBookOutStatus(NONE_USER, NONE_PASSWORD, ownerMoveItemId, 1, 0),
        "a membershipless non-owner must not book out the item");
    // The owner — no longer a member of the item's owning SK — still passes the edit gate.
    assertNotEquals(
        403,
        seeder.attemptBookOutStatus(MEMBER_USER, MEMBER_PASSWORD, ownerMoveItemId, 1, 0),
        "the owner retains edit access after leaving the item's owning org unit");
  }

  /**
   * UI boundary: a squadron-only member loading {@code /inventory/all} sees a group row for their
   * own squadron's material but none for a foreign squadron's.
   */
  @Test
  void squadronMemberUiLagerShowsOnlyOwnSquadronGroup() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/inventory/all");
        page.waitForLoadState();
        assertThat(page.locator("div.tree-row--group[data-material-id='" + matAId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("div.tree-row--group[data-material-id='" + matBId + "']"))
            .hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "inventory-tenancy-member-lager");
        throw failure;
      }
    }
  }

  // The anonymous-visitor case that stood here — /inventory/all lands on the landing page and never
  // renders the Lager table — moved to AnonymousSurfaceE2eTest, which sweeps that same shape across
  // every page rather than one. This class is about what a MEMBER sees; who may reach the page at
  // all is REQ-SEC-052's question, and answering it in one place is what stops the two drifting.

  // --------------------------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------------------------

  /**
   * Reports whether the given user sees any shared stock of {@code materialId} in the global Lager
   * -View ({@code /inventory/all/grouped}) — i.e. the grouped result for that material is
   * non-empty.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param materialId the material to probe
   * @return {@code true} if the viewer's global view lists a group for that material
   */
  private static boolean visibleInAll(String username, String password, String materialId) {
    return nonEmpty(
        seeder.getBody(
            username, password, "/api/v1/inventory/all/grouped?materialIds=" + materialId));
  }

  /**
   * Reports whether the given user sees stock of {@code materialId} in their personal Lager-View
   * ({@code /inventory/my-inventory/grouped}).
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param materialId the material to probe
   * @return {@code true} if the viewer's personal view lists a group for that material
   */
  private static boolean visibleInMy(String username, String password, String materialId) {
    return nonEmpty(
        seeder.getBody(
            username,
            password,
            "/api/v1/inventory/my-inventory/grouped?materialIds=" + materialId));
  }

  /**
   * Reports whether the given user — pinned to {@code pinOrgUnitId} via the {@code
   * X-Active-Org-Unit-Id} header — sees shared stock of {@code materialId} in the global
   * Lager-View.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param materialId the material to probe
   * @param pinOrgUnitId the OrgUnit to pin as the active scope
   * @return {@code true} if the pinned viewer's global view lists a group for that material
   */
  private static boolean visibleInAllPinned(
      String username, String password, String materialId, String pinOrgUnitId) {
    return nonEmpty(
        seeder.getBodyWithActiveOrgUnit(
            username,
            password,
            "/api/v1/inventory/all/grouped?materialIds=" + materialId,
            pinOrgUnitId));
  }

  /**
   * Attempts to create a non-personal inventory item of the create-stamping probe material as the
   * given user with the given owner pick, returning the HTTP status.
   *
   * @param username the creating user's Keycloak username
   * @param password the creating user's Keycloak password
   * @param owningOrgUnitId the picked owner OrgUnit id, or {@code null} for the no-pick case
   * @return the create attempt's HTTP status
   */
  private static int attemptCreate(String username, String password, String owningOrgUnitId) {
    return seeder.attemptCreateInventoryStatus(
        username, password, matStampId, locId, SEED_QUALITY, 5, owningOrgUnitId);
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
   * Reports whether a grouped-inventory JSON response body contains at least one group.
   *
   * @param groupedJsonBody the raw JSON array body from a {@code .../grouped} endpoint
   * @return {@code true} if the array is non-empty
   */
  private static boolean nonEmpty(String groupedJsonBody) {
    return !JsonParser.parseString(groupedJsonBody).getAsJsonArray().isEmpty();
  }
}
