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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the navigation shortcuts of REQ-UI-035 in a browser: Alt+1 opens favourite 1 and Alt+0 the
 * dashboard, an empty or greyed slot says so in a toast, the keys rest while a dialog is open, in
 * administration mode and while the profile switch is off, and the „Tastenkürzel" dialog opens from
 * the profile menu and the quick access.
 */
@Tag("e2e")
class NavigationShortcutsE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MEMBER = System.getProperty("e2e.username", "test-member");
  private static final String MEMBER_PASSWORD =
      System.getProperty("e2e.password", "test-member-pw");
  private static final String ADMIN = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";

  /** Leaves the member with two favourites and the shortcuts on, whatever a scenario did. */
  private static final String RESET =
      """
      async () => {
        const favourites = document.querySelector('[data-favourites-state]');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/favourites',
          payload: { keys: ['hangar.overview', 'operations.list'],
                     version: Number(favourites.dataset.version) },
          toast: false,
        });
        const state = document.querySelector('[data-appearance-state]');
        await window.krtFetch.write({
          method: 'PUT',
          url: '/navigation/appearance',
          payload: { desktopMode: state.dataset.mode.toUpperCase(), shortcutsEnabled: true,
                     version: Number(state.dataset.version) },
          toast: false,
        });
      }
      """;

  private static final String HANGAR_FAVOURITE = "nav-favourite-hangar.overview";
  private static final String EMPTY_SLOT_TOAST = "Auf Platz 5 liegt kein Favorit.";

  private static Playwright playwright;
  private static Browser browser;
  private static Path memberState;
  private static Path adminState;

  /** Launches the browser and captures an authenticated member and administrator session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    memberState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER_PASSWORD);
    adminState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN, ADMIN_PASSWORD);
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

  /** Stores the member's favourites and switch, so every scenario starts from the same page. */
  @BeforeEach
  void resetThePreferences() {
    run(
        memberState,
        "nav-shortcuts-reset",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.evaluate(RESET);
        });
  }

  /** Alt+1 and Alt+2 open the first two favourites, Alt+0 the dashboard. */
  @Test
  void theDigitKeysOpenTheFavouritesAndTheDashboard() {
    run(
        memberState,
        "nav-shortcuts-open",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(page.getByTestId(HANGAR_FAVOURITE)).hasAttribute("aria-keyshortcuts", "Alt+1");
          assertThat(page.locator("header .brand")).hasAttribute("aria-keyshortcuts", "Alt+0");

          page.keyboard().press("Alt+Digit1");
          page.waitForURL(Pattern.compile(".*/hangar$"));

          page.keyboard().press("Alt+Digit2");
          page.waitForURL(Pattern.compile(".*/operations$"));

          page.keyboard().press("Alt+Digit0");
          page.waitForURL(STACK.baseUrl() + "/");
        });
  }

  /** The numpad digits are no shortcut, and a bare digit is not either. */
  @Test
  void onlyTheDigitRowCounts() {
    run(
        memberState,
        "nav-shortcuts-numpad",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");

          page.keyboard().press("Alt+Numpad1");
          page.keyboard().press("1");
          page.waitForTimeout(300);

          assertTrue(page.url().endsWith("/missions"), page.url());
        });
  }

  /** An empty slot and a greyed favourite open nothing and say so. */
  @Test
  void anEmptyOrGreyedSlotShowsAToastAndStaysPut() {
    run(
        memberState,
        "nav-shortcuts-toast",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");

          page.keyboard().press("Alt+Digit5");
          assertThat(page.getByText(EMPTY_SLOT_TOAST)).isVisible();

          page.evaluate(
              """
              () => {
                const link = document.querySelector(
                  '#sidebar .nav-favourites[data-favourites-mode="member"] a.nav-link');
                const span = document.createElement('span');
                span.className = 'nav-link is-unavailable';
                link.replaceWith(span);
              }
              """);
          page.keyboard().press("Alt+Digit1");
          assertThat(page.getByText("Favorit 1 ist in dieser Einheit nicht verfügbar")).isVisible();
          assertTrue(page.url().endsWith("/missions"), page.url());
        });
  }

  /** While a dialog is open the keys rest, the quick access included. */
  @Test
  void theKeysRestWhileADialogIsOpen() {
    run(
        memberState,
        "nav-shortcuts-dialog",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.keyboard().press("Control+k");
          assertThat(page.locator("#nav-palette")).isVisible();

          page.keyboard().press("Alt+Digit1");
          page.waitForTimeout(300);

          assertTrue(page.url().endsWith("/missions"), page.url());
          assertThat(page.getByText("Auf Platz")).hasCount(0);
        });
  }

  /** In administration mode the keys do nothing and carry no hint. */
  @Test
  void theKeysRestInAdministrationMode() {
    run(
        adminState,
        "nav-shortcuts-admin",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/audit-log");
          assertThat(page.locator("header .brand"))
              .not()
              .hasAttribute("aria-keyshortcuts", "Alt+0");

          page.keyboard().press("Alt+Digit0");
          page.waitForTimeout(300);

          assertTrue(page.url().endsWith("/admin/audit-log"), page.url());
        });
  }

  /**
   * The switch in „Darstellung" turns the keys and their hints off in place, survives a reload and
   * turns them on again.
   */
  @Test
  void theProfileSwitchTurnsTheKeysOffAndOn() {
    run(
        memberState,
        "nav-shortcuts-switch",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          int version =
              Integer.parseInt(
                  page.locator("[data-appearance-state]").getAttribute("data-version"));

          page.getByTestId("profile-shortcuts-switch").uncheck();

          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-version", String.valueOf(version + 1));
          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-shortcuts", "false");
          assertThat(page.getByTestId(HANGAR_FAVOURITE))
              .not()
              .hasAttribute("aria-keyshortcuts", "Alt+1");
          page.keyboard().press("Alt+Digit1");
          page.waitForTimeout(300);
          assertTrue(page.url().endsWith("/profile"), page.url());

          page.reload();
          assertThat(page.getByTestId("profile-shortcuts-switch")).not().isChecked();

          page.getByTestId("profile-shortcuts-switch").check();
          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-shortcuts", "true");
          page.keyboard().press("Alt+Digit1");
          page.waitForURL(Pattern.compile(".*/hangar$"));
        });
  }

  /** The dialog opens from the profile menu and from the quick access and lists the keys. */
  @Test
  void theShortcutsDialogOpensFromTheProfileMenuAndTheQuickAccess() {
    run(
        memberState,
        "nav-shortcuts-help",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          openShortcutsFromTheProfileMenu(page);

          assertThat(page.locator("#nav-shortcuts")).isVisible();
          assertThat(page.getByTestId("shortcuts-favourites")).containsText("Alt+1");
          assertThat(page.getByTestId("shortcuts-dashboard")).containsText("Alt+0");
          assertThat(page.getByTestId("nav-shortcuts-body")).containsText("STRG K");

          page.keyboard().press("Escape");
          assertThat(page.locator("#nav-shortcuts")).isHidden();

          page.keyboard().press("Control+k");
          page.getByTestId("palette-input").fill("Tastenkürzel");
          page.getByTestId("palette-input").press("Enter");
          assertThat(page.locator("#nav-shortcuts")).isVisible();
        });
  }

  /** In administration mode the dialog says the keys work in member mode only. */
  @Test
  void theDialogSaysMemberModeOnlyInAdministrationMode() {
    run(
        adminState,
        "nav-shortcuts-help-admin",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/audit-log");
          openShortcutsFromTheProfileMenu(page);

          assertThat(page.getByTestId("shortcuts-favourites"))
              .containsText("nur im Mitgliedermodus");
          assertThat(page.getByTestId("shortcuts-dashboard"))
              .containsText("nur im Mitgliedermodus");
        });
  }

  /** With the switch off the dialog points to „Darstellung". */
  @Test
  void theDialogPointsToTheSwitchWhileItIsOff() {
    run(
        memberState,
        "nav-shortcuts-help-off",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
          page.getByTestId("profile-shortcuts-switch").uncheck();
          assertThat(page.locator("[data-appearance-state]"))
              .hasAttribute("data-shortcuts", "false");

          openShortcutsFromTheProfileMenu(page);

          assertThat(page.getByTestId("shortcuts-favourites")).containsText("ausgeschaltet");
          assertThat(page.getByTestId("shortcuts-appearance-link").first())
              .hasAttribute("href", "/profile#profile-appearance-card");
        });
  }

  /**
   * Opens the drawer and the profile menu, unless the page already shows it, and clicks through.
   */
  private static void openShortcutsFromTheProfileMenu(Page page) {
    page.locator("#hamburger").click();
    if (!"true".equals(page.getByTestId("nav-user-toggle").getAttribute("aria-expanded"))) {
      page.getByTestId("nav-user-toggle").click();
    }
    page.getByTestId("nav-shortcuts-open").click();
  }

  private static void run(Path state, String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state)
                .setViewportSize(1440, 900))) {
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
