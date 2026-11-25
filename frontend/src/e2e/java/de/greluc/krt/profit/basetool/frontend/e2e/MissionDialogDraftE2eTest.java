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
 * The mission add dialogs keep a half-filled entry as a tab-scoped draft (REQ-FE-041): after a
 * reload the page offers it, restore reopens the dialog through its opener and fills it, with the
 * finance entry's income or expense choice and a participant's name, and the save removes it.
 */
@Tag("e2e")
class MissionDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

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

  /** Brings a half-filled expense back into the reopened finance dialog. */
  @Test
  void aReloadOffersTheFinanceEntryWithItsExpenseChoice() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String missionUrl = STACK.baseUrl() + "/missions/" + seedMission() + "?tab=fin";
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, missionUrl);
        page.waitForLoadState();
        page.locator("button[data-modal-id='finance-modal']").click();
        page.locator("#finance-modal button[data-type-value='EXPENSE']").click();
        page.locator("#add-finance-form [name='amount']").fill("250");
        page.locator("#add-finance-form [name='note']").fill("Draft fuel cost");
        awaitDraft(page, "mission-finance-add", "Draft fuel cost");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#finance-modal")).isVisible();
        assertThat(page.locator("#add-finance-form [name='note']")).hasValue("Draft fuel cost");
        assertEquals(
            "EXPENSE",
            page.evaluate("() => document.getElementById('add-finance-type').value"));
        assertEquals(
            "250",
            page.evaluate(
                "() => document.querySelector('#add-finance-form [name=\"amount\"]').value"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-finance-draft");
        throw failure;
      }
    }
  }

  /** Brings a half-filled guest participant back, and adding it removes the draft. */
  @Test
  void aReloadOffersTheParticipantAndAddingItRemovesTheDraft() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String missionId = seedMission();
    String guest = "E2E Draft Guest " + UUID.randomUUID().toString().substring(0, 6);
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/missions/" + missionId);
        page.waitForLoadState();
        page.locator("#add-participant-btn").click();
        page.locator("#participant-search-input").fill(guest);
        page.locator("#add-participant-form [name='comment']").fill("Draft comment");
        awaitDraft(page, "mission-participant-add", guest);

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#participant-modal")).isVisible();
        assertThat(page.locator("#participant-search-input")).hasValue(guest);
        assertThat(page.locator("#add-participant-form [name='comment']"))
            .hasValue("Draft comment");

        page.waitForResponse(
            r ->
                r.url().contains("/missions/" + missionId + "/participants/ajax")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            () -> page.locator("#add-participant-form button[type='submit']").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-participant-draft");
        throw failure;
      }
    }
  }

  /**
   * Seeds a mission of the test member's unit.
   *
   * @return the mission id
   */
  private static String seedMission() {
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    return seeder.createMission(
        USERNAME, PASSWORD, "E2E dialog draft mission " + UUID.randomUUID(), false);
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
}
