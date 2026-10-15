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
 * Verifies that the profile's name-withholding toggle (REQ-XCH-044) saves in place, that a second
 * consecutive save does not 409 on a stale version, and that the saved value survives a reload.
 */
@Tag("e2e")
class ProfileNameWithholdingInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String CHECKBOX =
      "#profile-name-withholding-form input[name='withholdName']";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, ensures the test user's member row. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
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
   * Flips the toggle and saves in place, flips it back and saves again without reloading, then
   * flips it once more, saves and reloads the page to see the stored value.
   */
  @Test
  void savesNameWithholdingInPlaceAndKeepsItAcrossAReload() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/profile");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");

        Locator checkbox = page.locator(CHECKBOX);
        Locator submit = page.getByTestId("profile-save");
        assertThat(page.getByTestId("profile-save-bar")).isHidden();
        boolean initial = checkbox.isChecked();

        checkbox.setChecked(!initial);
        saveInPlace(page, submit);
        checkbox.setChecked(initial);
        saveInPlace(page, submit);
        checkbox.setChecked(!initial);
        saveInPlace(page, submit);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the name-withholding saves must update in place — no page reload on success");

        page.reload();
        page.waitForLoadState();
        assertEquals(
            !initial, page.locator(CHECKBOX).isChecked(), "the saved value must survive a reload");

        page.locator(CHECKBOX).setChecked(initial);
        saveInPlace(page, page.getByTestId("profile-save"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-name-withholding-in-place");
        throw failure;
      }
    }
  }

  /**
   * Saves the section through the save bar and asserts a success toast, no error toast, no
   * reload-confirm dialog and a hidden bar afterwards.
   *
   * @param page the authenticated profile page
   * @param submit the save bar's save button
   */
  private static void saveInPlace(Page page, Locator submit) {
    page.evaluate(
        "() => { document.querySelectorAll('.notification-toast').forEach((t) => t.remove()); }");
    page.waitForResponse(
        response ->
            response.url().endsWith("/profile/name-withholding")
                && "POST".equals(response.request().method()),
        submit::click);

    assertThat(page.locator(".notification-toast:not(.error-toast)"))
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
    assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
    assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
    assertThat(page.getByTestId("profile-save-bar")).isHidden();
  }
}
