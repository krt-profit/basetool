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
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The admin exchange registry registers, suspends, activates and widens a client and flips the
 * global switch, each in place and each risky step behind a confirmation (REQ-XCH-003).
 */
@Tag("e2e")
class AdminExchangeClientsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and ensures the admin test user's backend row exists. */
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

  /**
   * Registers a client, suspends and re-activates it, widens its capabilities and flips the switch
   * off and back on, all without a reload; the client is left suspended, since clients cannot be
   * deleted.
   */
  @Test
  void theRegistryIsManagedInPlace() {
    String baseUrl = STACK.baseUrl();
    String clientId =
        "e2e-xc-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/exchange-clients");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");

        page.locator("#xc-clientId").fill(clientId);
        page.locator("#xc-displayName").fill("E2E Exchange Client");
        page.locator("input[name='capability'][value='exchange.stock.read']").check();
        awaitRegistryRefresh(page, page.locator("#xc-form button[type='submit']")::click);
        Locator row = rowOf(page, "E2E Exchange Client");
        assertThat(row).hasAttribute("data-status", "ACTIVE");

        awaitRegistryRefresh(
            page,
            () -> {
              rowOf(page, "E2E Exchange Client").locator("[data-xc-suspend]").click();
              confirmAs(page, "data-confirm-suspend-ok");
            });
        assertThat(rowOf(page, "E2E Exchange Client")).hasAttribute("data-status", "SUSPENDED");

        awaitRegistryRefresh(
            page, rowOf(page, "E2E Exchange Client").locator("[data-xc-activate]")::click);
        assertThat(rowOf(page, "E2E Exchange Client")).hasAttribute("data-status", "ACTIVE");

        rowOf(page, "E2E Exchange Client").locator("[data-xc-edit]").click();
        assertThat(page.locator("#xc-clientId")).isDisabled();
        assertThat(page.locator("#xc-clientId")).hasValue(clientId);
        page.locator("input[name='capability'][value='exchange.blueprints.read']").check();
        awaitRegistryRefresh(
            page,
            () -> {
              page.locator("#xc-form button[type='submit']").click();
              confirmAs(page, "data-confirm-widen-ok");
            });
        assertThat(rowOf(page, "E2E Exchange Client").locator(".xc-capabilities li")).hasCount(3);

        String before = page.locator("#xc-switch").getAttribute("data-enabled");
        flipSwitch(page);
        assertThat(page.locator("#xc-switch"))
            .hasAttribute("data-enabled", String.valueOf(!Boolean.parseBoolean(before)));
        flipSwitch(page);
        assertThat(page.locator("#xc-switch")).hasAttribute("data-enabled", before);

        awaitRegistryRefresh(
            page,
            () -> {
              rowOf(page, "E2E Exchange Client").locator("[data-xc-suspend]").click();
              confirmAs(page, "data-confirm-suspend-ok");
            });

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the registry writes must update in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-exchange-clients");
        throw failure;
      }
    }
  }

  /**
   * Opens the bulk undo of a freshly registered client, checks a scope that reaches nothing, and
   * finds the start still disabled and the client still active, all without a reload (REQ-XCH-034).
   */
  @Test
  void theBulkUndoChecksItsScopeBeforeItCanStart() {
    String baseUrl = STACK.baseUrl();
    String clientId =
        "e2e-xu-" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/admin/exchange-clients");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");
        page.locator("#xc-clientId").fill(clientId);
        page.locator("#xc-displayName").fill("E2E Undo Client");
        awaitRegistryRefresh(page, page.locator("#xc-form button[type='submit']")::click);

        rowOf(page, "E2E Undo Client").locator("[data-xc-undo]").click();
        assertThat(page.locator("#xc-undo-modal")).isVisible();
        assertThat(page.locator("#xc-undo-client")).hasText("E2E Undo Client");
        assertThat(page.locator("#xc-undo-submit")).isDisabled();
        page.locator("#xc-undo-check").click();
        assertThat(page.locator("#xc-undo-preview")).isVisible();
        assertThat(page.locator("#xc-undo-submit")).isDisabled();
        page.keyboard().press("Escape");

        assertThat(rowOf(page, "E2E Undo Client").locator("[data-xc-suspend]")).hasCount(1);
        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the undo dialog works in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-exchange-bulk-undo");
        throw failure;
      }
    }
  }

  /**
   * Flips the global switch through its confirmation.
   *
   * @param page the registry page
   */
  private static void flipSwitch(Page page) {
    boolean enabled = "true".equals(page.locator("#xc-switch").getAttribute("data-enabled"));
    awaitRegistryRefresh(
        page,
        () -> {
          page.locator("[data-xc-switch]").click();
          confirmAs(page, enabled ? "data-confirm-off-ok" : "data-confirm-on-ok");
        });
  }

  /**
   * Confirms the open confirmation after checking that its button carries the dialog's own label.
   *
   * @param page the registry page
   * @param attribute the {@code #xc-i18n} attribute that holds the expected label
   */
  private static void confirmAs(Page page, String attribute) {
    Locator ok = page.locator(".krt-confirm-overlay .krt-confirm-ok");
    assertThat(ok).hasText(page.locator("#xc-i18n").getAttribute(attribute));
    ok.click();
  }

  /**
   * Locates the table row of a client by its display name.
   *
   * @param page the registry page
   * @param displayName the client's display name
   * @return the row
   */
  private static Locator rowOf(Page page, String displayName) {
    return page.locator(
        "#xc-table tbody tr",
        new Page.LocatorOptions().setHas(page.getByText(displayName, exactText())));
  }

  /**
   * Matches a text exactly.
   *
   * @return the options
   */
  private static Page.GetByTextOptions exactText() {
    return new Page.GetByTextOptions().setExact(true);
  }

  /**
   * Runs an action and waits until the registry section has been swapped in place.
   *
   * @param page the registry page
   * @param action the action that triggers the write
   */
  private static void awaitRegistryRefresh(Page page, Runnable action) {
    page.evaluate(
        "() => {"
            + "  window.__xcSwapped = false;"
            + "  const host = document.getElementById('xc-registry-host');"
            + "  document.addEventListener('krt:swapped', function onSwap(e) {"
            + "    if (e && e.detail && e.detail.container === host) {"
            + "      window.__xcSwapped = true;"
            + "      document.removeEventListener('krt:swapped', onSwap);"
            + "    }"
            + "  });"
            + "}");
    action.run();
    page.waitForFunction(
        "() => window.__xcSwapped === true",
        null,
        new Page.WaitForFunctionOptions().setTimeout(30_000));
  }
}
