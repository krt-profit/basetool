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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The bank movement dialog keeps a half-filled booking as a tab-scoped draft (REQ-FE-041): closing
 * the dialog keeps it, a reload offers it on the page, restore reopens the dialog through its
 * opener and fills it, the booking removes the draft, and a discard drops it.
 */
@Tag("e2e")
class BankDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String EMPLOYEE_USER = "test-bank-employee";
  private static final String EMPLOYEE_PASSWORD = "test-bank-employee-pw";
  private static final String ADMIN_USER = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;
  private static String accountId;
  private static String holderId;

  /** Launches the browser and seeds an account the employee may book on, with a holder. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    String employeeId = seeder.getUserId(EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    seeder.assignStaffelMembership(
        ADMIN_USER, ADMIN_PASSWORD, employeeId, IRIDIUM_ID, false, false);
    accountId =
        seeder.createBankAccount(MGMT_USER, MGMT_PASSWORD, "E2E Dialog Draft Account", "SPECIAL");
    seeder.createBankGrant(MGMT_USER, MGMT_PASSWORD, employeeId, accountId, true, true, true);
    holderId = seeder.registerBankHolder(MGMT_USER, MGMT_PASSWORD, employeeId);
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

  /** Offers a closed, half-filled movement after a reload and restores it into the dialog. */
  @Test
  void aReloadOffersTheMovementAndRestoreFillsTheReopenedDialog() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        openFilledMovement(page, "321", "Draft booking note");
        page.keyboard().press("Escape");
        assertThat(page.locator("#bank-movement-modal")).isHidden();

        page.reload();
        page.waitForLoadState();
        Locator offer = page.getByTestId("draft-dialog-offer");
        assertThat(offer).isVisible();
        assertThat(offer.getByTestId("draft-dialog-name")).hasText(Pattern.compile("\\S"));
        assertThat(page.locator("#bank-movement-modal")).isHidden();
        offer.getByTestId("draft-dialog-restore").click();

        assertThat(offer).hasCount(0);
        assertThat(page.locator("#bank-movement-modal")).isVisible();
        assertEquals("321", canonicalAmount(page));
        assertEquals(
            holderId,
            page.evaluate(
                "() => document.querySelector('#bank-movement-modal"
                    + " [name=\"holderId\"]:not(:disabled)').value"));
        assertThat(page.locator("#bank-mv-note")).hasValue("Draft booking note");

        page.waitForResponse(
            r ->
                r.url().contains("/api/proxy/bank/deposits")
                    && "POST".equals(r.request().method())
                    && r.status() < 300,
            new Page.WaitForResponseOptions().setTimeout(60_000),
            () -> page.getByTestId("bank-movement-submit").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-dialog-draft");
        throw failure;
      }
    }
  }

  /** Drops the movement draft on discard, so the next reload offers nothing. */
  @Test
  void discardDropsTheMovementDraft() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      try {
        openFilledMovement(page, "45", "Discarded booking note");

        page.reload();
        page.waitForLoadState();
        Locator offer = page.getByTestId("draft-dialog-offer");
        offer.getByTestId("draft-dialog-discard").click();
        assertThat(offer).hasCount(0);
        assertEquals(0, ((Number) page.evaluate("() => " + DRAFTS)).intValue());

        page.reload();
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-dialog-offer")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "bank-dialog-draft-discard");
        throw failure;
      }
    }
  }

  /**
   * Opens a browser context signed in as the bank employee.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth =
        E2eSupport.authenticatedStorageState(
            browser, STACK.baseUrl(), EMPLOYEE_USER, EMPLOYEE_PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth));
  }

  /**
   * Opens the account's movement dialog, fills a deposit and waits for its draft.
   *
   * @param page the page
   * @param amount the whole-aUEC amount
   * @param note the booking note
   */
  private static void openFilledMovement(Page page, String amount, String note) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/bank/accounts/" + accountId);
    page.waitForLoadState();
    assertThat(page.getByTestId("draft-dialog-offer")).hasCount(0);
    page.getByTestId("bank-movement-open").click(new Locator.ClickOptions().setTimeout(20_000));
    page.getByTestId("bank-movement-amount").fill(amount);
    E2eSupport.selectComboboxByValue(page.getByTestId("bank-movement-deposit-holder"), holderId);
    page.locator("#bank-mv-note").fill(note);
    page.waitForFunction(
        "note => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes('bank-movement') && sessionStorage.getItem(k).includes(note))",
        note);
  }

  /**
   * Reads the amount the dialog submits, which the number field shows formatted.
   *
   * @param page the page
   * @return the canonical amount
   */
  private static String canonicalAmount(Page page) {
    return (String) page.evaluate("() => document.getElementById('bank-mv-amount').value");
  }
}
