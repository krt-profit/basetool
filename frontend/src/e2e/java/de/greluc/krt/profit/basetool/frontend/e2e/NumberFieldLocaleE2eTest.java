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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A number field types with the separators of the page language while its value stays canonical
 * (REQ-UI-037), and the deposit fee line follows the same language (REQ-BANK-065, REQ-UI-038).
 * Nothing is submitted.
 */
@Tag("e2e")
class NumberFieldLocaleE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String MGMT_USER = "test-bank-management";
  private static final String MGMT_PASSWORD = "test-bank-management-pw";
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";

  /** The canonical IRIDIUM Squadron seeded at stack bootstrap. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String accountId;

  /** Gives the officer an IRIDIUM membership and makes sure the squadron has a bank account. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    BackendSeeder seeder = new BackendSeeder();
    String officerId = seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
    seeder.assignStaffelleiterSeat(
        ADMIN_USER, ADMIN_PASSWORD, officerId, IRIDIUM_SQUADRON_ID, false);
    accountId =
        seeder.ensureOrgUnitBankAccount(
            MGMT_USER, MGMT_PASSWORD, "E2E Org-Unit Bank", IRIDIUM_SQUADRON_ID);
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

  /** On an English page the amount reads 1,999,999 while typing and the fee line groups alike. */
  @Test
  void anEnglishPageGroupsWithCommas() {
    typeAmountAndCheck("en", "1,999,999", "With fee", Pattern.compile("\\d{1,3}(,\\d{3})+ aUEC"));
  }

  /** On a German page the same keystrokes read 1.999.999. */
  @Test
  void aGermanPageGroupsWithPoints() {
    typeAmountAndCheck("de", "1.999.999", "Mit Geb", Pattern.compile("\\d{1,3}(\\.\\d{3})+ aUEC"));
  }

  /**
   * Opens the deposit request dialog in a language, types an amount key by key and checks the shown
   * text, the canonical value and the fee line.
   *
   * @param lang the page language
   * @param shown the grouped amount the field must show
   * @param feeLabel the start of the fee line in that language
   * @param grouped the grouped amount the fee line must show
   */
  private static void typeAmountAndCheck(
      String lang, String shown, String feeLabel, Pattern grouped) {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/org-unit-bank?lang=" + lang);
        page.locator("[data-testid='org-unit-bank-request-btn']")
            .first()
            .click(new Locator.ClickOptions().setTimeout(20_000));
        page.locator("[data-testid='org-unit-request-type']").selectOption("DEPOSIT");
        page.locator("[data-testid='org-unit-request-account']").selectOption(accountId);

        Locator amount = page.locator("[data-testid='org-unit-request-amount']");
        amount.click();
        amount.pressSequentially("1999999");

        assertThat(amount).hasValue(shown);
        assertEquals("1999999", amount.evaluate("el => el.value"), "the value stays canonical");
        Locator gross = page.locator("[data-testid='org-unit-request-deposit-gross']");
        assertThat(gross).isVisible();
        assertThat(gross).containsText(feeLabel);
        assertThat(gross).containsText(grouped);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "number-field-locale-" + lang);
        throw failure;
      }
    }
  }
}
