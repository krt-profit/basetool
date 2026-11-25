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
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The purchasing partner dialog keeps a half-filled new partner as a tab-scoped draft (REQ-FE-041):
 * after a reload the page offers it, restore reopens the dialog and fills it, the save removes the
 * draft, and the same dialog keeps no draft while it edits a partner.
 */
@Tag("e2e")
class PurchasingDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and prepares the purchasing surface for the test user. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder()
          .preparePurchasing(
              USERNAME, PASSWORD, "draft" + UUID.randomUUID().toString().substring(0, 8));
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

  /** Offers a closed, half-filled partner after a reload, restores and saves it. */
  @Test
  void aReloadOffersTheNewPartnerAndEditingKeepsNoDraft() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String name = "E2E Draft Partner " + UUID.randomUUID().toString().substring(0, 8);
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/purchasing/partners");
        page.waitForLoadState();
        page.getByTestId("purchasing-new-partner").click();
        page.getByTestId("purchasing-partner-name").fill(name);
        page.waitForFunction(
            "name => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:') &&"
                + " k.includes('purchasing-partner') && sessionStorage.getItem(k).includes(name))",
            name);
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        Locator offer = page.getByTestId("draft-dialog-offer");
        assertThat(offer).isVisible();
        offer.getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#purchasing-partner-modal")).isVisible();
        assertThat(page.getByTestId("purchasing-partner-name")).hasValue(name);

        page.waitForResponse(
            r ->
                r.url().endsWith("/api/proxy/purchasing/partners")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            () -> page.getByTestId("purchasing-partner-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");

        page.getByTestId("purchasing-partner-row")
            .filter(new Locator.FilterOptions().setHasText(name))
            .getByTestId("purchasing-edit-partner")
            .click();
        assertThat(page.locator("#purchasing-partner-modal")).isVisible();
        assertEquals(
            Boolean.FALSE,
            page.evaluate(
                "() => document.getElementById('purchasing-partner-form')"
                    + ".hasAttribute('data-krt-draft')"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "purchasing-partner-draft");
        throw failure;
      }
    }
  }
}
