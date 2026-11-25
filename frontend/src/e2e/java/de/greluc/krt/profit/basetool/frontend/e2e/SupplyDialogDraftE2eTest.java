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
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The supply create dialogs keep a half-filled entry as a tab-scoped draft (REQ-FE-041): after a
 * reload the page offers it, restore reopens the dialog through its opener and fills it, and the
 * save removes the draft.
 */
@Tag("e2e")
class SupplyDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser, switches the supply surface on and lets IRIDIUM raise needs. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    int settings =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/settings",
            "{\"enabled\":true,\"governingAreaId\":null}");
    assertTrue(settings >= 200 && settings < 300, "the supply switch must be settable");
    int unit =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/units/" + IRIDIUM_ID,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable");
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

  /** Offers a closed, half-filled need after a reload, restores it and saves it. */
  @Test
  void aReloadOffersTheNeedAndTheSaveRemovesIt() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/supply/needs");
        page.waitForLoadState();
        page.getByTestId("supply-raise-need").click();
        page.locator("#supply-need-amount").fill("7");
        page.locator("#supply-need-priority").selectOption("HIGH");
        awaitDraft(page, "supply-need", "\"supply-need-priority\":\"HIGH\"");
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        Locator offer = page.getByTestId("draft-dialog-offer");
        assertThat(offer).isVisible();
        offer.getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#supply-need-modal")).isVisible();
        assertEquals("7", canonical(page, "supply-need-amount"));
        assertThat(page.locator("#supply-need-priority")).hasValue("HIGH");

        page.waitForResponse(
            r ->
                r.url().endsWith("/api/proxy/supply/needs")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            () -> page.getByTestId("supply-need-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-need-draft");
        throw failure;
      }
    }
  }

  /** Brings a half-filled free pledge back into its reopened dialog. */
  @Test
  void aReloadOffersThePledgeAndRestoreFillsIt() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/supply/donations/mine");
        page.waitForLoadState();
        page.getByTestId("supply-free-pledge").click();
        page.locator("#supply-pledge-amount").fill("3");
        awaitDraft(page, "supply-pledge", "\"supply-pledge-amount\":\"3\"");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#supply-pledge-modal")).isVisible();
        assertEquals("3", canonical(page, "supply-pledge-amount"));
        assertThat(page.getByTestId("draft-dialog-offer")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-pledge-draft");
        throw failure;
      }
    }
  }

  /**
   * Opens a signed-in browser context.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth));
  }

  /**
   * Waits for the draft of a dialog form that holds the given text.
   *
   * @param page the page
   * @param formKey the dialog form's draft key
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String formKey, String text) {
    page.waitForFunction(
        "([key, text]) => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes(key) && sessionStorage.getItem(k).includes(text))",
        new String[] {formKey, text});
  }

  /**
   * Reads the value a number field submits, which it shows formatted.
   *
   * @param page the page
   * @param id the field's id
   * @return the canonical value
   */
  private static String canonical(Page page, String id) {
    return (String) page.evaluate("id => document.getElementById(id).value", id);
  }
}
