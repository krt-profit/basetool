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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Representative CRUD coverage for the admin mission-data page ({@code /admin/mission-data}), a
 * previously untested member of the admin data-management cluster. It creates a squadron through
 * the modal and deletes it through the confirm dialog — both modal-driven {@code krtFetch} writes
 * that re-render the {@code #squadrons-results} section in place (REQ-FE-002), never reloading the
 * page.
 *
 * <p>The flow is hermetic: it creates its own {@code "E2E Mission Data Squad"} (a member-less
 * squadron deletes cleanly — one still in use would 409) and removes it again, so it leaves no
 * residue that sibling suites depend on. Actor: {@code test-admin} (the page is {@code
 * ADMIN}-gated). Tagged {@code @Tag("e2e")}: it mutates data, so it runs only against the ephemeral
 * stack.
 */
@Tag("e2e")
class AdminMissionDataCrudE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Distinctive name of the squadron this test creates then deletes. */
  private static final String SQUADRON_NAME = "E2E Mission Data Squad";

  /** Distinctive (unique) shorthand for the created squadron. */
  private static final String SQUADRON_SHORTHAND = "E2EMD";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /**
   * Launches the browser and, for the ephemeral stack, ensures the admin's own {@code app_user} row
   * exists, then captures one authenticated admin session reused across the test.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Creates a squadron through the create modal (asserting it appears in the squadrons section in
   * place), then deletes it through the confirm modal (asserting the row is gone), with no page
   * reload and no error toast across either write.
   */
  @Test
  void createsAndDeletesASquadronInPlace() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/mission-data");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");

        // CREATE
        page.locator("#add-squadron-btn").click();
        Locator name = page.locator("#sq-name");
        assertThat(name).isVisible();
        name.fill(SQUADRON_NAME);
        page.locator("#sq-shorthand").fill(SQUADRON_SHORTHAND);
        page.locator("#sq-desc").fill("Created by AdminMissionDataCrudE2eTest.");
        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/mission-data/squadrons")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#squadron-form button[type='submit']").click());
        assertThat(squadronRow(page))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);

        // DELETE — open the confirm modal from the created row, then confirm.
        squadronRow(page).locator(".delete-btn").first().click();
        assertThat(page.locator("#delete-confirm-modal")).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/admin/mission-data/squadrons/")
                    && response.url().endsWith("/delete")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#delete-confirm-form button[type='submit']").click());
        assertThat(squadronRow(page))
            .hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(15_000));

        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the squadron writes must update in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-mission-data-crud");
        throw failure;
      }
    }
  }

  // ---------------------------------------------------------------------- helpers --

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
   * Locates the squadrons-section row carrying this test's squadron name.
   *
   * @param page the page showing the mission-data admin view
   * @return a locator for the squadron's {@code <tr>} (count 0 once it is deleted)
   */
  private static Locator squadronRow(Page page) {
    return page.locator(
        "#squadrons-results tr", new Page.LocatorOptions().setHasText(SQUADRON_NAME));
  }
}
