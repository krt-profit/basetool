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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The hangar's add-ship dialog keeps a half-filled ship as a tab-scoped draft (REQ-FE-041): closing
 * the dialog without saving keeps it, after a reload the page offers it, restore reopens the dialog
 * through its opener and fills it, and the save removes it; the dialog keeps no draft while it
 * edits a ship.
 */
@Tag("e2e")
class HangarDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds the test member's IRIDIUM membership. */
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

  /** Keeps a closed, half-filled ship, restores it after a reload and saves it. */
  @Test
  void aClosedShipDialogKeepsItsDraftAndEditingKeepsNone() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String name = "E2E Draft Ship " + UUID.randomUUID().toString().substring(0, 8);
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth))) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/hangar");
        page.waitForLoadState();
        page.getByTestId("hangar-add-ship").click();
        page.locator("#ship-type").selectOption(new SelectOption().setLabel("E2E Ship Type"));
        page.locator("#ship-name").fill(name);
        page.getByTestId("segment-insuranceKind-lti").click();
        page.getByTestId("segment-origin-pledge").click();
        awaitDraft(page, name);
        page.locator("#ship-modal .close-ship-modal").click();
        assertThat(page.locator("#ship-modal")).not().isVisible();
        assertEquals(1, ((Number) page.evaluate(DRAFTS)).intValue());

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#ship-modal")).isVisible();
        assertThat(page.locator("#ship-name")).hasValue(name);
        assertThat(page.locator("#ship-type option:checked")).hasText("E2E Ship Type");
        assertThat(page.getByTestId("segment-insuranceKind-lti").locator("input")).isChecked();

        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        page.waitForResponse(
            r ->
                r.url().contains("/hangar/add")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            () -> page.getByTestId("hangar-ship-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");

        page.getByTestId("hangar-ship-row")
            .filter(new Locator.FilterOptions().setHasText(name))
            .locator("[data-trigger='hangar-edit-ship']")
            .click();
        assertThat(page.locator("#ship-modal")).isVisible();
        assertEquals(
            Boolean.FALSE,
            page.evaluate(
                "() => document.getElementById('ship-form').hasAttribute('data-krt-draft')"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "hangar-ship-draft");
        throw failure;
      }
    }
  }

  /**
   * Waits for the ship dialog's draft that holds the given text.
   *
   * @param page the page
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String text) {
    page.waitForFunction(
        "text => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes('hangar-ship') && sessionStorage.getItem(k).includes(text))",
        text);
  }
}
