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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The start-page select offers the purchasing report only to a caller who may open it (REQ-UI-026,
 * REQ-UI-034): with the supply and purchasing surfaces on, a plain member is not offered „Bericht",
 * a trade delegate is.
 */
@Tag("e2e")
class NavigationDataTabGateE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MEMBER = System.getProperty("e2e.username", "test-member");
  private static final String MEMBER_PASSWORD =
      System.getProperty("e2e.password", "test-member-pw");
  private static final String DELEGATE = System.getProperty("e2e.adminUsername", "test-admin");
  private static final String DELEGATE_PASSWORD =
      System.getProperty("e2e.adminPassword", "test-admin-pw");

  private static final String SELECT = "[data-testid='start-page-select-member']";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser, switches both surfaces on and appoints the second user a delegate. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    BackendSeeder seeder = new BackendSeeder();
    if (STACK.managesStack()) {
      seeder.ensureIridiumMembership(DELEGATE, DELEGATE_PASSWORD);
    }
    seeder.preparePurchasing(
        DELEGATE, DELEGATE_PASSWORD, UUID.randomUUID().toString().substring(0, 8));
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

  /** A plain member is offered the needs page but not the purchasing report. */
  @Test
  void aPlainMemberIsNotOfferedTheReport() {
    run(
        MEMBER,
        MEMBER_PASSWORD,
        "nav-data-tab-member",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          Locator select = page.locator(SELECT);
          assertThat(select.locator("option[value='supply.needs']")).hasCount(1);
          assertThat(select.locator("option[value='purchasing.report']")).hasCount(0);
          assertThat(select.locator("option[value='purchasing.delegates']")).hasCount(0);
        });
  }

  /** A trade delegate is offered the purchasing report and the partners. */
  @Test
  void aTradeDelegateIsOfferedTheReport() {
    run(
        DELEGATE,
        DELEGATE_PASSWORD,
        "nav-data-tab-delegate",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          Locator select = page.locator(SELECT);
          assertThat(select.locator("option[value='purchasing.report']")).hasCount(1);
          assertThat(select.locator("option[value='purchasing.partners']")).hasCount(1);
        });
  }

  private static void run(String username, String password, String label, Consumer<Page> scenario) {
    Path storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), username, password);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        scenario.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
