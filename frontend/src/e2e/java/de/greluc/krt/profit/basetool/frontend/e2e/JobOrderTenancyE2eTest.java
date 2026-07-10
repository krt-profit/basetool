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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Multi-tenancy visibility flow for Job Orders (REQ-ORG-003): a Job Order's visibility is driven by
 * its <em>responsible</em> (processing) OrgUnit's kind, gated behind the profit-eligibility check
 * ({@code canViewJobOrders}). This test pins down three rules through the real UI:
 *
 * <ul>
 *   <li><b>SK-public queue</b> — an order whose responsible unit is a Spezialkommando is visible to
 *       every profit-eligible member, including one of a different squadron (the SK-public escape
 *       {@code TYPE(responsibleOrgUnit) = SpecialCommand}).
 *   <li><b>Squadron-private</b> — an order whose responsible unit is a squadron is visible only to
 *       that squadron's members + admins; a member of another squadron does NOT see it.
 *   <li><b>Requester escape</b> — a member whose only membership is a non-profit squadron may not
 *       browse the general queue, but sees the orders their own unit <em>requested</em> under
 *       "Meine Aufträge" (REQ-ORDERS-023) and is denied a foreign order they did not request.
 * </ul>
 *
 * <p>Two non-admin actors carry the matrix: {@code test-officer} is homed in a fresh
 * profit-eligible squadron B (its Officer realm role grants edit capability but no cross-squadron
 * <em>visibility</em> — scope stays membership-based), and {@code test-member} is homed in a fresh
 * non-profit squadron C. The SK-responsible and IRIDIUM-responsible orders are seeded by an admin.
 * Assertions key on each order's own {@code data-id}, so the shared ephemeral DB's accumulated
 * orders from sibling tests cannot perturb them.
 */
@Tag("e2e")
class JobOrderTenancyE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;

  /** An order whose responsible unit is a profit-eligible SK — the SK-public-queue case. */
  private static String skOrderId;

  /** An order whose responsible unit is the IRIDIUM squadron — the squadron-private case. */
  private static String iridiumOrderId;

  /**
   * An order <em>requested</em> by the non-profit squadron C (processed by the profit SK) — the
   * requesting-owner escape case (REQ-ORDERS-023): squadron C's member may view it under "Meine
   * Aufträge" even though C is non-profit.
   */
  private static String cRequestedOrderId;

  /**
   * Seeds the tenancy fixture: a profit-eligible SK, a profit-eligible squadron B with {@code
   * test-officer} homed in it, a non-profit squadron C with {@code test-member} homed in it, and
   * two orders — one responsible to the SK (public) and one responsible to IRIDIUM
   * (squadron-private).
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);

      String profitSkId =
          seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy SK", "ETSK");
      seeder.setSpecialCommandProfitEligible(ADMIN_USER, ADMIN_PASSWORD, profitSkId, true);

      String squadronBId =
          seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy B", "ETNB");
      seeder.setSquadronProfitEligible(ADMIN_USER, ADMIN_PASSWORD, squadronBId, true);
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD),
          squadronBId,
          false,
          false);

      // Squadron C is left non-profit (the default), so its member is outside the order workflow.
      String squadronCId =
          seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy C", "ETNC");
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
          squadronCId,
          false,
          false);

      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Tenancy Material");
      skOrderId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, profitSkId, "E2E Tenancy SK Order", materialId, 650, 50);
      iridiumOrderId =
          seeder.createJobOrder(
              ADMIN_USER, ADMIN_PASSWORD, IRIDIUM_ID, "E2E Tenancy IRI Order", materialId, 650, 50);

      // An order the non-profit squadron C REQUESTED (processed by the profit SK): squadron C's
      // member is its requester and may view it under "Meine Aufträge" (REQ-ORDERS-023), even
      // though squadron C is non-profit.
      cRequestedOrderId =
          seeder.createJobOrder(
              ADMIN_USER,
              ADMIN_PASSWORD,
              profitSkId,
              squadronCId,
              "E2E Tenancy C-Requested Order",
              materialId,
              650,
              50);
    }
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
   * An officer of squadron B sees the SK-responsible order (SK-public queue) but not the
   * IRIDIUM-responsible order (squadron-private to a squadron B is not a member of).
   */
  @Test
  void officerSeesSkPublicOrderButNotForeignSquadronPrivateOrder() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded SK / squadrons / orders");
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        // scope=all returns the caller's natural cross-staffel union (own squadron + SK-public),
        // unfiltered by the "mine" active-squadron narrowing.
        E2eSupport.navigate(page, baseUrl + "/orders?scope=all&status=OPEN");
        page.waitForLoadState();
        assertThat(page.getByTestId("nav-logout")).isVisible();

        // The SK-public order surfaces for the foreign-squadron officer (slow WebKit list render).
        assertThat(page.locator("[data-testid='order-row'][data-id='" + skOrderId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        // The IRIDIUM-private order does not.
        assertThat(page.locator("[data-testid='order-row'][data-id='" + iridiumOrderId + "']"))
            .hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "tenancy-officer-list");
        throw failure;
      }
    }
  }

  /**
   * A member whose only membership is a non-profit squadron does not browse the general queue, but
   * sees the orders their own unit <em>requested</em> under "Meine Aufträge" (REQ-ORDERS-023): the
   * C-requested order surfaces, the foreign SK-public order does not, and they are not bounced to
   * the create form. A direct link to a foreign order they did not request is denied (403 at the
   * backend requester gate) and bounced back to their own-orders list.
   */
  @Test
  void nonProfitMemberSeesOwnRequestedOrdersNotTheForeignQueue() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral-seeded non-profit squadron membership");
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);

        // "Meine Aufträge": the order squadron C requested surfaces; the general queue (the
        // SK-public order, requested by another unit) does not; and the member is NOT bounced to
        // the
        // create form.
        E2eSupport.navigate(page, baseUrl + "/orders");
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='order-row'][data-id='" + cRequestedOrderId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("[data-testid='order-row'][data-id='" + skOrderId + "']"))
            .hasCount(0);
        assertThat(page.getByTestId("order-mode-material")).hasCount(0);

        // A direct link to a foreign order the member did not request is denied and bounced back to
        // their own-orders list — not opened, and not the create form.
        E2eSupport.navigate(page, baseUrl + "/orders/" + skOrderId);
        page.waitForLoadState();
        assertThat(page.locator("[data-testid='order-row'][data-id='" + cRequestedOrderId + "']"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.getByTestId("order-mode-material")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "tenancy-nonprofit-requester");
        throw failure;
      }
    }
  }
}
