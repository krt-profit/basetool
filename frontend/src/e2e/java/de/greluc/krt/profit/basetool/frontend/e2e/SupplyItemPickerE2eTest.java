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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The item picker of the supply need form (REQ-SUP-003, REQ-FE-016): it searches the items the
 * Lager books on the server, a form set to an item asks for one before it sends anything, and a
 * reopened form starts with an empty picker.
 */
@Tag("e2e")
class SupplyItemPickerE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String railgunId;

  /**
   * Launches the browser, switches the supply surface on, lets IRIDIUM raise needs and seeds a
   * weapon no blueprint produces.
   */
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
    assertTrue(
        settings >= 200 && settings < 300, "the supply switch must be settable: " + settings);
    int unit =
        seeder.putForStatus(
            USERNAME,
            PASSWORD,
            "/api/v1/supply/admin/units/" + IRIDIUM_ID,
            "{\"mayRaiseNeeds\":true,\"acceptsDonations\":true,\"version\":null}");
    assertTrue(unit == 200 || unit == 409, "the unit flags must be settable: " + unit);
    railgunId = seeder.seedStockableItem("E2E Supply Railgun", "WeaponPersonal", "Large");
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
   * Sends nothing while the item is missing, finds the weapon by typing its name, and empties the
   * picker when the form is opened again.
   */
  @Test
  void theNeedFormSearchesTheItemAndStartsEmptyWhenReopened() {
    assumeTrue(STACK.managesStack(), "needs the JDBC-seeded game-item catalog");
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/supply/needs");
        page.waitForLoadState();
        AtomicInteger needPosts = new AtomicInteger();
        page.onRequest(request -> {
          if ("POST".equals(request.method())
              && request.url().endsWith("/api/proxy/supply/needs")) {
            needPosts.incrementAndGet();
          }
        });

        page.getByTestId("supply-raise-need").click();
        page.locator("#supply-need-kind").selectOption("item");
        page.locator("#supply-need-amount").fill("1");
        page.getByTestId("supply-need-submit").click();

        assertThat(page.locator(".notification-toast.error-toast"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertEquals(0, needPosts.get(), "a need without its item must not be sent");

        Locator itemPicker =
            page.locator(".krt-combobox:has(#supply-need-item) .krt-combobox__input");
        E2eSupport.selectComboboxByValue(itemPicker, railgunId, "Supply Railgun");
        assertEquals(railgunId, itemValue(page));

        page.locator("#supply-need-modal .btn-ghost[data-trigger='close-modal-display']").click();
        page.getByTestId("supply-raise-need").click();
        page.locator("#supply-need-kind").selectOption("item");

        assertEquals("", itemValue(page), "a reopened form must not keep the last item");
        assertThat(itemPicker).hasValue("");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "supply-item-picker");
        throw failure;
      }
    }
  }

  /**
   * Reads the item the need form would send.
   *
   * @param page the needs page
   * @return the value of the picker's hidden input
   */
  private static String itemValue(Page page) {
    return (String) page.evaluate("() => document.getElementById('supply-need-item').value");
  }
}
