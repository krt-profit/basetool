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
 * The bank management's create dialogs keep a half-filled entry as a tab-scoped draft (REQ-FE-041):
 * after a reload the page offers it, and restore reopens the dialog through its opener and fills it
 * — the account create with its type set first, so the org unit that type may own is still chosen,
 * and the holder transfer with both holders.
 */
@Tag("e2e")
class BankManageDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;
  private static String sourceHolderId;
  private static String destinationHolderId;

  /** Launches the browser and registers the employee and the management user as holders. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    sourceHolderId =
        seeder.registerBankHolder(
            MGMT_USER, MGMT_PASSWORD, seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD));
    destinationHolderId =
        seeder.registerBankHolder(
            MGMT_USER, MGMT_PASSWORD, seeder.getUserId(MGMT_USER, MGMT_PASSWORD));
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

  /** Restores an org-unit account with its org unit, and the save removes the draft. */
  @Test
  void aReloadOffersTheAccountWithItsTypeAndOrgUnit() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String name = "E2E Draft Account " + UUID.randomUUID().toString().substring(0, 8);
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/bank/manage?tab=konten");
        page.waitForLoadState();
        page.getByTestId("bank-create-account-open").click();
        page.getByTestId("bank-create-name").fill(name);
        page.getByTestId("bank-create-type").selectOption("ORG_UNIT");
        page.getByTestId("bank-create-orgunit").selectOption(IRIDIUM_ID);
        awaitDraft(page, "bank-account-create", name);
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#bank-create-account-modal")).isVisible();
        assertThat(page.getByTestId("bank-create-name")).hasValue(name);
        assertThat(page.getByTestId("bank-create-type")).hasValue("ORG_UNIT");
        assertThat(page.getByTestId("bank-create-orgunit")).hasValue(IRIDIUM_ID);

        page.getByTestId("bank-create-type").selectOption("SPECIAL");
        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/bank/accounts")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            () -> page.getByTestId("bank-create-account-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-account-create-draft");
        throw failure;
      }
    }
  }

  /** Restores a holder transfer with both holders, and a discard drops the draft. */
  @Test
  void aReloadOffersTheHolderTransferWithBothHolders() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/bank/manage?tab=halter");
        page.waitForLoadState();
        page.getByTestId("bank-holder-transfer-open").click();
        E2eSupport.selectComboboxByValue(
            page.getByTestId("bank-holder-transfer-source"), sourceHolderId);
        E2eSupport.selectComboboxByValue(
            page.getByTestId("bank-holder-transfer-dest"), destinationHolderId);
        page.locator("#bank-holder-transfer-note").fill("Draft transfer note");
        awaitDraft(page, "bank-holder-transfer", "Draft transfer note");
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        Locator offer = page.getByTestId("draft-dialog-offer");
        offer.getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#bank-holder-transfer-modal")).isVisible();
        assertEquals(sourceHolderId, holderValue(page, "sourceHolderId"));
        assertEquals(destinationHolderId, holderValue(page, "destinationHolderId"));
        assertThat(page.locator("#bank-holder-transfer-note")).hasValue("Draft transfer note");

        page.keyboard().press("Escape");
        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-discard").click();
        assertEquals(0, ((Number) page.evaluate("() => " + DRAFTS)).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-holder-transfer-draft");
        throw failure;
      }
    }
  }

  /**
   * Opens a browser context signed in as the bank management.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MGMT_USER, MGMT_PASSWORD);
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
   * Reads the holder a combobox of the transfer dialog holds.
   *
   * @param page the page
   * @param name the field name of the holder
   * @return the chosen holder id
   */
  private static String holderValue(Page page, String name) {
    return (String)
        page.evaluate(
            "name => document.querySelector('#bank-holder-transfer-modal [name=\"' + name"
                + " + '\"]').value",
            name);
  }
}
