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
 * Spezialkommando (SK) flow (UC-11): an admin manages an SK as a first-class OrgUnit, and the
 * documented limitation holds — a not-yet-profit-eligible SK cannot be the responsible (processing)
 * unit of a job order. Only profit-eligible org units process orders (V128), and a freshly created
 * SK is not profit-eligible by default, so naming it as the responsible unit returns HTTP 400.
 *
 * <ul>
 *   <li>Lifecycle (UI): the admin creates an SK via {@code /admin/special-commands} and it appears
 *       in the list.
 *   <li>Member page (UI): the old {@code /admin/special-commands/{id}} URL redirects to the SK
 *       member page at {@code /organisation/special-commands/{id}}.
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
        // A full reload would wipe this marker; the #582 in-place create leaves it intact.
        page.evaluate("window.__krtNoReload = true;");
        // The create form lives in a modal opened by the "Neues Spezialkommando" button.
        page.locator("#add-sc-btn").click();
        page.locator("#sc-name").fill(SK_UI_NAME);
        page.locator("#sc-shorthand").fill("ESKU");
        submitInPlace(page.locator("#sc-form button[type='submit']"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "creating an SK must save in place without reloading the page");
        // Re-load the list fresh (as the other create flows do); asserting on the post-submit page
        // directly proved flaky. Match the new SK by its table row, not a bare text locator. Via
        // the retry helper — WebKit can abort this post-submit GET (HTTP/2 INTERNAL_ERROR). See
        // E2eSupport#navigate.
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
   * The admin soft-deletes (deactivates) a Spezialkommando from the list page: the per-row trash
   * button opens the KRT confirmation modal (no native {@code confirm()}), confirming POSTs the
   * deactivate, and the row drops out of the default active-only list while reappearing — flagged
   * inactive — under {@code includeInactive=true}. Guards the pre-existing bug where the trash
   * button was inert (no enclosing form, no script wiring), so the SK could never be deleted from
   * the list UI. Ephemeral-stack only: it seeds a throwaway SK to delete.
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

        // Open the confirm modal from the seeded SK's row, then confirm the deactivate.
        Locator row =
            page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText(SK_DELETE_NAME));
        assertThat(row).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        // A full reload would wipe this marker; the #582 in-place deactivate leaves it intact.
        page.evaluate("window.__krtNoReload = true;");
        row.locator(".delete-btn").click();
        assertThat(page.locator("#sc-delete-modal"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        submitInPlace(page.locator("#sc-delete-form button[type='submit']"));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "deactivating an SK must save in place without reloading the page");

        // The active-only list no longer shows the SK.
        E2eSupport.navigate(page, baseUrl + "/admin/special-commands");
        page.waitForLoadState();
        assertThat(
                page.locator("tbody tr")
                    .filter(new Locator.FilterOptions().setHasText(SK_DELETE_NAME)))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(20_000));

        // includeInactive surfaces it again, flagged inactive — proving a soft-delete, not a purge.
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
   * The SK member page moved out of the admin area to {@code /organisation/special-commands/{id}}
   * (an SK's own lead manages its members too, and {@code /admin/**} stays admin-only): the old
   * {@code /admin/special-commands/{id}} URL redirects there, and the page renders the member
   * roster box with its add-member action. Ephemeral-stack only: it needs the SK seeded in {@link
   * #setUp}.
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
   * Submits an in-place SK write (#582): drops the {@code position: fixed} footer (the WebKit
   * click-interception guard {@link E2eSupport#clickSubmitClearingFooter} also applies) and clicks
   * the submit, blocking on the AJAX twin's {@code POST /admin/special-commands*} response. The
   * write now re-swaps the SK-list fragment instead of navigating, so — unlike the classic flow —
   * there is no post-submit document load to await; the caller re-loads the list itself afterwards.
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
