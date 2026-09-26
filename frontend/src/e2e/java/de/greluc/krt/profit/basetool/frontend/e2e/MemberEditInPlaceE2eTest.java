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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the in-place rank save on the admin member edit page ({@code /members/{id}/edit},
 * REQ-FE-007): no reload, no error, no 409, and the backend persists the new rank.
 *
 * <p>Runs as {@code test-admin} editing {@code test-member}; it mutates data, so it runs only
 * against the ephemeral stack.
 */
@Tag("e2e")
class MemberEditInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  /** The rank value the test assigns and then reads back from the backend. */
  private static final int TARGET_RANK = 7;

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String memberUserId;

  /**
   * Launches the browser, materialises {@code test-member} in the backend (so the edit page
   * resolves) and, for the ephemeral stack, ensures the admin's own {@code app_user} row exists,
   * then captures one authenticated admin session reused across the test.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      memberUserId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN_USER, ADMIN_PASSWORD);
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
   * Opens the member's edit page, changes the rank and saves in place, asserting the success UX (no
   * error toast, no reload-confirm dialog, no page reload) and that the backend persisted the new
   * rank.
   */
  @Test
  void savesMemberRankInPlace() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/members/" + memberUserId + "/edit?source=members");
        page.waitForLoadState();
        assertThat(page.locator("#member-edit-form")).isVisible();

        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        page.locator("#member-edit-form select[name='rank']")
            .selectOption(String.valueOf(TARGET_RANK));
        page.waitForResponse(
            response ->
                response.url().contains("/members/" + memberUserId + "/edit")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#member-edit-form button[type='submit']").click());

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the member save must update in place — no page reload cleared the marker");
        assertEquals(TARGET_RANK, persistedRank(), "the in-place save must persist the new rank");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-edit-in-place");
        throw failure;
      }
    }
  }

  /**
   * Clicks "Zweite Staffel hinzufügen" and asserts the second Staffel slot is revealed by toggling
   * its {@code krtm-hidden} class (REQ-ORG-017, ADR-0093).
   *
   * <p>A pure client-side toggle; mutates nothing.
   */
  @Test
  void revealsSecondStaffelSlotOnAdd() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/members/" + memberUserId + "/edit?source=members");
        page.waitForLoadState();
        assertThat(page.locator("#member-edit-form")).isVisible();

        assertThat(page.locator("#staffel-add-2")).isVisible();
        assertThat(page.locator("#staffel-slot-2")).isHidden();

        page.locator("#staffel-add-2").click();

        assertThat(page.locator("#staffel-slot-2")).isVisible();
        assertThat(page.locator("#staffel-add-2")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-edit-second-staffel");
        throw failure;
      }
    }
  }

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused admin storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Reads the edited member's persisted rank straight from the backend ({@code GET
   * /api/v1/users/{id}}), so the persistence assertion does not race the client's in-place update.
   *
   * @return the persisted rank as an int
   */
  private static int persistedRank() {
    String body =
        new BackendSeeder().getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/users/" + memberUserId);
    return JsonParser.parseString(body).getAsJsonObject().get("rank").getAsInt();
  }
}
