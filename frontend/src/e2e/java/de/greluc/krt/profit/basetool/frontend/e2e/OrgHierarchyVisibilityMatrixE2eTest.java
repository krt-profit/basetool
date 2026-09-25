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
 * Phase 7 (#700) end-to-end visibility matrix for the new Bereich tier of the org hierarchy (epic
 * #692) — the security/regression gate's cross-Bereich slice, asserting the three invariants the
 * restructure must hold for a {@code BEREICH}-leadership principal:
 *
 * <ul>
 *   <li><b>Own-Bereich reach:</b> a Bereichsleiter sees their own Bereich's data (the cascade
 *       includes the Bereich's own id), via the strict-staffel Lager-View.
 *   <li><b>Strict silo (no cross-Bereich leak):</b> the same Bereichsleiter is denied a
 *       <em>foreign</em> Bereich's data — and a plain Staffel member never sees Bereich-owned data
 *       at all.
 *   <li><b>No admin escalation (REQ-ORG-015 HARD INVARIANT):</b> a Bereichsleiter's
 *       officer-equivalent reach grants <b>no</b> admin rights — an {@code ADMIN}-gated endpoint
 *       stays 403.
 * </ul>
 *
 * <p>The descendant cascade (Bereichsleiter → child Staffel/SK) and the OL total-reach are pinned
 * at the unit level ({@code OwnerScopeServiceTest.CascadingScopeTests}); this e2e drives the new
 * Bereich-leadership path through the real stack end-to-end.
 *
 * <p><b>Fixtures.</b> {@code test-bereich} is a dedicated realm user carrying only the {@code KRT
 * Member} base role and <em>no</em> Staffel membership, so it can be made a Bereichsleiter without
 * tripping the leader-excludes-Staffel invariant (REQ-ORG-017). It is this suite's own
 * membership-shape-owned profile: making a user a Bereich leader gives it a Bereich membership for
 * the rest of the sequentially-run shared stack, so we must <em>not</em> reuse {@code test-none} —
 * the dedicated membershipless user that {@code RefineryOrderTenancyE2eTest} and {@code
 * InventoryTenancyE2eTest} rely on staying membershipless (a single membership would auto-stamp
 * their "ownerless" fixtures and break their tenancy assertions). The admin seeds two Bereiche, one
 * inventory item owned by each Bereich (create-on-behalf stamping, REQ-ORG-016, on its own material
 * so a material maps 1:1 to a Bereich), then grants {@code test-bereich} the {@code LEITER} role on
 * Bereich A only.
 */
@Tag("e2e")
class OrgHierarchyVisibilityMatrixE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String BEREICH_USER = "test-bereich";
  private static final String BEREICH_PASSWORD = "test-bereich-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  private static final int SEED_QUALITY = 750;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  private static String matAId;
  private static String matBId;

  /**
   * Seeds two Bereiche, one inventory item owned by each (admin create-on-behalf), and grants
   * {@code test-bereich} the Bereichsleiter role on Bereich A.
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
    seeder.getUserId(BEREICH_USER, BEREICH_PASSWORD);

    String bereichAId =
        seeder.createBereich(ADMIN_USER, ADMIN_PASSWORD, "E2E Vis Bereich A", "EVBA");
    String bereichBId =
        seeder.createBereich(ADMIN_USER, ADMIN_PASSWORD, "E2E Vis Bereich B", "EVBB");

    String locId = seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E Vis Loc");
    matAId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Vis Mat A");
    matBId = seeder.createRefineryMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Vis Mat B");

    seeder.createInventoryItemOwnedBy(
        ADMIN_USER, ADMIN_PASSWORD, matAId, locId, SEED_QUALITY, 100, bereichAId);
    seeder.createInventoryItemOwnedBy(
        ADMIN_USER, ADMIN_PASSWORD, matBId, locId, SEED_QUALITY, 100, bereichBId);

    seeder.addBereichLeader(
        ADMIN_USER,
        ADMIN_PASSWORD,
        bereichAId,
        seeder.getUserId(BEREICH_USER, BEREICH_PASSWORD),
        "LEITER");
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

  /**
   * A Bereichsleiter sees their own Bereich's stock but never a foreign Bereich's (strict silo).
   */
  @Test
  void bereichsleiterSeesOwnBereichStockNotForeign() {
    assertTrue(
        visibleInAll(BEREICH_USER, BEREICH_PASSWORD, matAId),
        "Bereichsleiter of A sees Bereich A's own stock");
    assertFalse(
        visibleInAll(BEREICH_USER, BEREICH_PASSWORD, matBId),
        "Bereichsleiter of A must not see foreign Bereich B's stock (strict silo)");
  }

  /** Bereich-owned stock is siloed from an ordinary Staffel member, who oversees no Bereich. */
  @Test
  void plainSquadronMemberSeesNoBereichStock() {
    assertFalse(
        visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matAId),
        "a plain Staffel member must not see Bereich A's stock");
    assertFalse(
        visibleInAll(MEMBER_USER, MEMBER_PASSWORD, matBId),
        "a plain Staffel member must not see Bereich B's stock");
  }

  /**
   * The HARD INVARIANT (REQ-ORG-015): a Bereichsleiter's officer-equivalent reach grants no admin
   * rights, so an {@code ADMIN}-gated endpoint (the org-hierarchy admin list) stays 403.
   */
  @Test
  void bereichsleiterIsDeniedAdminEndpoints() {
    assertEquals(
        403,
        seeder.attemptGetStatus(BEREICH_USER, BEREICH_PASSWORD, "/api/v1/org-hierarchy/bereiche"),
        "a Bereichsleiter must not reach the ADMIN-gated org-hierarchy admin list");
  }

  /**
   * UI boundary: a Bereichsleiter loading {@code /inventory/all} sees a group row for their own
   * Bereich's material but none for a foreign Bereich's.
   */
  @Test
  void bereichsleiterUiLagerShowsOnlyOwnBereichGroup() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, BEREICH_USER, BEREICH_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/inventory/all");
        page.waitForLoadState();
        assertThat(page.locator("div.tree-row--group[data-material-id='" + matAId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("div.tree-row--group[data-material-id='" + matBId + "']"))
            .hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "org-hierarchy-visibility-bereichsleiter");
        throw failure;
      }
    }
  }

  /**
   * Reports whether the given user sees any shared stock of {@code materialId} in the global
   * Lager-View ({@code /inventory/all/grouped}) — i.e. the grouped result for that material is
   * non-empty.
   *
   * @param username the viewer's Keycloak username
   * @param password the viewer's Keycloak password
   * @param materialId the material to probe
   * @return {@code true} if the viewer's global view lists a group for that material
   */
  private static boolean visibleInAll(String username, String password, String materialId) {
    return !JsonParser.parseString(
            seeder.getBody(
                username, password, "/api/v1/inventory/all/grouped?materialIds=" + materialId))
        .getAsJsonArray()
        .isEmpty();
  }
}
