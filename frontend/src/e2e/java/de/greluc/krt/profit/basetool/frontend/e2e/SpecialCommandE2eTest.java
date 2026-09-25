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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Spezialkommando (SK) flow (UC-11): an admin manages an SK as an OrgUnit, and a
 * non-profit-eligible SK cannot be the responsible unit of a job order.
 *
 * <ul>
 *   <li>Lifecycle (UI): the admin creates an SK via {@code /admin/special-commands} and it appears
 *       in the list.
 *   <li>Member page (UI): {@code /admin/special-commands/{id}} redirects to the SK member page at
 *       {@code /organisation/special-commands/{id}}.
 *   <li>Limitation (API): naming a non-profit-eligible SK as a job order's responsible OrgUnit
 *       returns 400.
 * </ul>
 */
@Tag("e2e")
class SpecialCommandE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";
  private static final String SK_UI_NAME = "E2E SK Created In UI";
  private static final String SK_DELETE_NAME = "E2E SK Delete Target";

  private static Playwright playwright;
  private static Browser browser;
  private static String skApiId;
  private static String materialId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the admin's IRIDIUM membership, an SK
   * (for the ownership-limitation check) and a job-order material.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      skApiId = seeder.createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, "E2E SK Alpha", "ESKA");
      materialId = seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E SK Material");
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

  /** The admin creates a Spezialkommando through the admin UI and it appears in the list. */
  @Test
  void adminCreatesSpecialCommandThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/admin/special-commands");
        page.waitForLoadState();
        page.evaluate("window.__krtNoReload = true;");
        page.locator("#add-sc-btn").click();
        page.locator("#sc-name").fill(SK_UI_NAME);
        page.locator("#sc-shorthand").fill("ESKU");
        submitInPlace(page.locator("#sc-form button[type='submit']"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "creating an SK must save in place without reloading the page");
        E2eSupport.navigate(page, baseUrl + "/admin/special-commands");
        page.waitForLoadState();
        assertThat(
                page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(SK_UI_NAME)))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "special-command-create");
        throw failure;
      }
    }
  }

  /**
   * Verifies that the admin can deactivate an SK from the list page: the trash button opens the KRT
   * confirmation modal, confirming POSTs the deactivation, and the row leaves the default list but
   * reappears as inactive under {@code includeInactive=true}.
   */
  @Test
  void adminDeactivatesSpecialCommandFromTheList() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral stack to seed a throwaway SK to delete");
    String baseUrl = STACK.baseUrl();
    new BackendSeeder().createSpecialCommand(ADMIN_USER, ADMIN_PASSWORD, SK_DELETE_NAME, "ESKDL");
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/admin/special-commands");
        page.waitForLoadState();

        Locator row =
            page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(SK_DELETE_NAME));
        assertThat(row).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        page.evaluate("window.__krtNoReload = true;");
        row.locator(".delete-btn").click();
        assertThat(page.locator("#sc-delete-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        submitInPlace(page.locator("#sc-delete-form button[type='submit']"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "deactivating an SK must save in place without reloading the page");

        E2eSupport.navigate(page, baseUrl + "/admin/special-commands");
        page.waitForLoadState();
        assertThat(
                page.locator("tbody tr")
                    .filter(new Locator.FilterOptions().setHasText(SK_DELETE_NAME)))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(20_000));

        E2eSupport.navigate(page, baseUrl + "/admin/special-commands?includeInactive=true");
        page.waitForLoadState();
        Locator inactiveRow =
            page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(SK_DELETE_NAME));
        assertThat(inactiveRow)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(inactiveRow.locator(".badge-inactive"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "special-command-delete");
        throw failure;
      }
    }
  }

  /**
   * Verifies that {@code /admin/special-commands/{id}} redirects to the SK member page at {@code
   * /organisation/special-commands/{id}}, which renders the member roster with its add-member
   * action.
   */
  @Test
  void oldAdminDetailUrlRedirectsToTheMemberPage() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral stack's seeded SK");
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, ADMIN_USER, ADMIN_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/admin/special-commands/" + skApiId);
        page.waitForLoadState();
        String landed = page.url();
        assertTrue(
            landed.endsWith("/organisation/special-commands/" + skApiId),
            "the old admin SK detail URL must redirect to the SK member page, landed on " + landed);
        assertThat(page.locator("#members-box"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("#add-member-btn")).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "special-command-member-page-redirect");
        throw failure;
      }
    }
  }

  /**
   * Naming a not-yet-profit-eligible Spezialkommando as a job order's responsible (processing)
   * OrgUnit returns HTTP 400 — only profit-eligible org units may process orders, and a freshly
   * created SK is not eligible by default.
   */
  @Test
  void specialCommandCannotOwnAJobOrder() {
    int status =
        new BackendSeeder()
            .attemptCreateJobOrderStatus(
                ADMIN_USER,
                ADMIN_PASSWORD,
                skApiId,
                IRIDIUM_ID,
                "E2E SK Order",
                materialId,
                650,
                50);
    assertEquals(
        400,
        status,
        "a non-profit-eligible SK named as a job order's responsible OrgUnit must be rejected with"
            + " 400");
  }

  /**
   * Submits an in-place SK write: hides the {@code position: fixed} footer, clicks the submit and
   * waits for the {@code POST /admin/special-commands*} response. The list fragment is re-swapped
   * rather than navigated, so the caller reloads the list itself.
   *
   * @param submit the submit control (in the create/edit or delete modal) to click
   */
  private static void submitInPlace(Locator submit) {
    Page page = submit.page();
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    page.waitForResponse(
        r -> r.url().contains("/admin/special-commands") && "POST".equals(r.request().method()),
        new Page.WaitForResponseOptions().setTimeout(15_000),
        submit::click);
  }
}
