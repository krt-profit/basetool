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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The personal inventory's add-entry dialog keeps a half-filled entry as a tab-scoped draft
 * (REQ-FE-041): after a reload the page offers it, restore reopens the dialog through its opener
 * and fills it with the picked location, and the save removes it.
 */
@Tag("e2e")
class PersonalInventoryDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** The UEX city the catalog seed provides for the location type-ahead. */
  private static final String SEEDED_CITY = "E2E Personal Inventory City";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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

  /** Brings a half-filled entry back with its picked location, and the save removes the draft. */
  @Test
  void aReloadOffersTheEntryWithItsLocation() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String name = "E2E Draft Entry " + UUID.randomUUID().toString().substring(0, 8);
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth))) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/personal-inventory");
        page.waitForLoadState();
        page.locator("button[data-trigger='pi-open-create']").click();
        page.locator("#krt-pi-name").fill(name);
        page.locator("#krt-pi-quantity").fill("3");
        page.locator("#krt-pi-location-search").fill(SEEDED_CITY);
        Locator firstResult =
            page.locator("#krt-pi-location-results .krt-pi-typeahead-item").first();
        assertThat(firstResult)
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        firstResult.click();
        String locationId = locationId(page);
        assertNotEquals("", locationId);
        page.locator("#krt-pi-note").fill("Draft note");
        page.waitForFunction(
            "text => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
                + " && k.includes('personal-inventory-entry')"
                + " && sessionStorage.getItem(k).includes(text))",
            "Draft note");
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#krt-pi-modal")).isVisible();
        assertThat(page.locator("#krt-pi-name")).hasValue(name);
        assertThat(page.locator("#krt-pi-quantity")).hasValue("3");
        assertThat(page.locator("#krt-pi-location-search")).hasValue(SEEDED_CITY);
        assertEquals(locationId, locationId(page));

        page.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        page.waitForResponse(
            response ->
                response.url().endsWith("/personal-inventory/add")
                    && "POST".equals(response.request().method())
                    && response.status() < 300,
            () -> page.locator("#krt-pi-form button[type='submit']").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "personal-inventory-draft");
        throw failure;
      }
    }
  }

  /**
   * Reads the location the entry dialog's type-ahead picked.
   *
   * @param page the page
   * @return the picked location's id; empty when none is picked
   */
  private static String locationId(Page page) {
    return (String) page.evaluate("() => document.getElementById('krt-pi-location-id').value");
  }
}
