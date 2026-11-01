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
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the navigation of REQ-UI-026 in a browser: the drawer's menu filter and its hand-over to
 * the quick access, the {@code Ctrl+K} palette, the administration mode on admin pages and the
 * phone tab bar with its menu sheet.
 */
@Tag("e2e")
class NavigationE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** Launches the browser and captures one authenticated administrator session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * The menu filter narrows the drawer to matching links, says so when nothing matches, and hands
   * its text to the quick access, whose first hit opens on Enter.
   */
  @Test
  void drawerFilterNarrowsTheMenuAndHandsOverToTheQuickAccess() {
    run(
        1440,
        900,
        "nav-filter",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.locator("#hamburger").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("nav-filter")).isFocused();

          page.getByTestId("nav-filter").fill("zz-no-such-entry");
          assertThat(page.locator(".nav-filter-empty")).isVisible();

          String hangar = page.getByTestId("nav-hangar").textContent().trim();
          page.getByTestId("nav-filter").fill(hangar);
          assertThat(page.getByTestId("nav-hangar")).isVisible();
          assertThat(page.getByTestId("nav-missions")).isHidden();
          assertThat(page.locator(".nav-filter-empty")).isHidden();

          page.getByTestId("nav-filter-to-palette").click();
          assertThat(page.locator("#sidebar")).not().hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("palette-input")).isFocused();
          assertThat(page.getByTestId("palette-input")).hasValue(hangar);
          page.getByTestId("palette-input").press("Enter");
          page.waitForURL(Pattern.compile(".*/hangar$"));
        });
  }

  /**
   * {@code Ctrl+K} opens the quick access from anywhere, the arrow keys move the selection and
   * Escape closes it again.
   */
  @Test
  void shortcutOpensTheQuickAccessAndEscapeClosesIt() {
    run(
        1440,
        900,
        "nav-palette",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.keyboard().press("Control+k");
          assertThat(page.locator("#nav-palette")).isVisible();
          assertThat(page.getByTestId("palette-input")).isFocused();
          assertThat(page.locator("#nav-palette .palette-row").first())
              .hasClass(Pattern.compile("\\bis-selected\\b"));

          String orgChart = page.getByTestId("nav-org-chart").textContent().trim();
          page.getByTestId("palette-input").fill(orgChart);
          assertThat(page.locator("#nav-palette .palette-row[data-href='/org-chart']")).isVisible();
          page.keyboard().press("Escape");
          assertThat(page.locator("#nav-palette")).isHidden();
        });
  }

  /**
   * The quick access finds a page by its label spelled without umlauts and by a search term, and
   * runs the create-mission action.
   */
  @Test
  void quickAccessMatchesTolerantlyAndRunsActions() {
    run(
        1440,
        900,
        "nav-palette-actions",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          String overview =
              page.locator(".palette-source-pages a[href='/materials/overview']")
                  .textContent()
                  .trim();
          String spelled =
              overview
                  .replace("Ü", "Ue")
                  .replace("ü", "ue")
                  .replace("Ä", "Ae")
                  .replace("ä", "ae")
                  .replace("Ö", "Oe")
                  .replace("ö", "oe");
          page.keyboard().press("Control+k");
          page.getByTestId("palette-input").fill(spelled.toLowerCase());
          assertThat(page.locator("#nav-palette .palette-row[data-href='/materials/overview']"))
              .isVisible();

          String keywords =
              page.locator("#sidebar a[href='/hangar']").getAttribute("data-nav-keywords");
          page.getByTestId("palette-input").fill(keywords.split(" ")[0]);
          assertThat(page.locator("#nav-palette .palette-row[data-href='/hangar']")).isVisible();

          String create = page.getByTestId("palette-action-create-mission").textContent().trim();
          page.getByTestId("palette-input").fill(create);
          page.getByTestId("palette-input").press("Enter");
          page.waitForURL(Pattern.compile(".*/missions/new$"));
        });
  }

  /** The administration action opens the drawer on the administration groups. */
  @Test
  void quickAccessOpensTheAdministration() {
    run(
        1440,
        900,
        "nav-palette-admin",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.keyboard().press("Control+k");
          page.locator("#nav-palette .palette-row .palette-row-label")
              .filter(
                  new Locator.FilterOptions()
                      .setHasText(page.locator(".palette").getAttribute("data-action-admin")))
              .first()
              .click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-admin-mode\\b"));
        });
  }

  /**
   * An administration page opens the drawer in administration mode, and the main-menu control
   * switches back to the main groups.
   */
  @Test
  void adminPageOpensTheDrawerInAdministrationMode() {
    run(
        1440,
        900,
        "nav-admin-mode",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/audit-log");
          page.locator("#hamburger").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-admin-mode\\b"));
          assertThat(page.getByTestId("nav-admin-audit-log")).isVisible();
          assertThat(page.getByTestId("nav-missions")).isHidden();

          page.getByTestId("nav-admin-back").click();
          assertThat(page.getByTestId("nav-missions")).isVisible();
          assertThat(page.getByTestId("nav-admin-audit-log")).isHidden();
          assertThat(page.getByTestId("nav-admin-toggle")).isVisible();
        });
  }

  /**
   * The administration groups start collapsed, the hub link leads to the hub page, and opening a
   * group shows its links.
   */
  @Test
  void adminGroupsStartCollapsedBehindTheHubLink() {
    run(
        1440,
        900,
        "nav-admin-collapsed",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");
          page.locator("#hamburger").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bis-admin-mode\\b"));
          assertThat(page.getByTestId("nav-admin-dashboard")).isVisible();
          assertThat(page.locator("#sidebar .nav-mode-admin .nav-group-head")).hasCount(3);
          for (Locator head : page.locator("#sidebar .nav-mode-admin .nav-group-head").all()) {
            assertThat(head).hasAttribute("aria-expanded", "false");
          }
          assertThat(page.getByTestId("nav-admin-audit-log")).isHidden();

          page.locator(
                  "#sidebar .nav-mode-admin [data-group-key='admin-operations'] .nav-group-head")
              .click();
          assertThat(page.getByTestId("nav-admin-audit-log")).isVisible();
          assertThat(page.getByTestId("admin-hub")).isVisible();
        });
  }

  /**
   * The member default groups its links by task, shows every group open, and merged pages carry
   * their siblings as tabs; the Star Citizen links sit in the footer.
   */
  @Test
  void memberDefaultHasTaskGroupsAndTabbedPages() {
    run(
        1440,
        900,
        "nav-member-default",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          page.locator("#hamburger").click();
          for (String group : new String[] {"operations", "logistics", "trade", "unit"}) {
            assertThat(
                    page.locator(
                        "#sidebar .nav-mode-main [data-group-key='" + group + "'] .nav-group-head"))
                .hasAttribute("aria-expanded", "true");
          }
          assertThat(page.locator("#sidebar [data-group-key='resources']")).hasCount(0);
          assertThat(page.getByTestId("nav-missions")).isVisible();
          assertThat(page.getByTestId("footer-sc-links")).hasAttribute("href", "/sc-links");
          page.keyboard().press("Escape");

          assertThat(page.getByTestId("page-tab-missions.list"))
              .hasAttribute("aria-current", "page");
          page.getByTestId("page-tab-operations.list").click();
          page.waitForURL(Pattern.compile(".*/operations$"));
          assertThat(page.getByTestId("page-tab-operations.list"))
              .hasAttribute("aria-current", "page");

          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/stock-levels");
          assertThat(page.getByTestId("page-tab-storage.levels"))
              .hasAttribute("aria-current", "page");
          page.getByTestId("page-tab-storage.overview").click();
          page.waitForURL(Pattern.compile(".*/inventory$"));
        });
  }

  /**
   * The former Lager list URLs open the Lager tab with the option selected; the switch moves
   * between the options without leaving the tab.
   */
  @Test
  void formerLagerListUrlsOpenTheLagerTabWithTheOption() {
    run(
        1440,
        900,
        "nav-lager-options",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory/my");
          page.waitForURL(Pattern.compile(".*/inventory\\?storage=my$"));
          assertThat(page.getByTestId("inventory-my-link")).hasAttribute("aria-current", "page");
          assertThat(page.getByTestId("page-tab-storage.overview"))
              .hasAttribute("aria-current", "page");

          page.getByTestId("inventory-global-link").click();
          page.waitForURL(Pattern.compile(".*/inventory\\?storage=all$"));
          assertThat(page.getByTestId("inventory-global-link"))
              .hasAttribute("aria-current", "page");
          assertThat(page.getByTestId("page-tab-storage.overview"))
              .hasAttribute("aria-current", "page");
        });
  }

  /** A merged admin page shows the tab bar of its siblings and the tabs lead to them. */
  @Test
  void mergedAdminPageShowsItsTabBar() {
    run(
        1440,
        900,
        "nav-admin-tabs",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/settings");
          assertThat(page.getByTestId("page-tabs")).isVisible();
          assertThat(page.getByTestId("page-tab-admin.settings.general"))
              .hasAttribute("aria-current", "page");
          page.getByTestId("page-tab-admin.dangerzone").click();
          page.waitForURL(Pattern.compile(".*/admin/settings/danger-zone$"));
          assertThat(page.getByTestId("page-tab-admin.dangerzone"))
              .hasAttribute("aria-current", "page");
          assertThat(page.getByTestId("bank-wipe-card")).isVisible();
          assertThat(page.getByTestId("bp-purge-card")).isVisible();
        });
  }

  /**
   * On a phone the tab bar marks the current area, the footer gives way to it, and the menu tab
   * opens the sheet with the user row.
   */
  @Test
  void phoneTabBarOpensTheMenuSheet() {
    run(
        390,
        844,
        "nav-phone",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
          assertThat(page.getByTestId("mobile-tab-missions"))
              .hasClass(Pattern.compile("\\bis-active\\b"));
          assertThat(page.locator(".krt-footer")).isHidden();
          assertThat(page.locator("#hamburger")).isHidden();

          page.getByTestId("mobile-tab-menu").click();
          assertThat(page.locator("#sidebar")).hasClass(Pattern.compile("\\bopen\\b"));
          assertThat(page.getByTestId("mobile-tab-menu")).hasAttribute("aria-expanded", "true");
          assertThat(page.getByTestId("nav-user-toggle")).isVisible();

          page.getByTestId("mobile-tab-menu").click();
          assertThat(page.locator("#sidebar")).not().hasClass(Pattern.compile("\\bopen\\b"));
        });
  }

  /**
   * Chooses a desktop mode in the profile's „Darstellung" card and waits until the page carries it.
   *
   * @param page the page to drive
   * @param mode the mode to choose: {@code drawer}, {@code rail} or {@code pinned}
   */
  private static void chooseMode(Page page, String mode) {
    E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
    page.getByTestId("segment-desktop-mode-profile-" + mode).click();
    assertThat(page.locator("body")).hasAttribute("data-nav-mode", mode);
  }

  private static String scrollPadding(Page page) {
    return (String)
        page.evaluate("getComputedStyle(document.documentElement).scrollPaddingInlineStart");
  }

  /**
   * The three desktop modes follow their widths: the drawer by default, the rail from 1025 px, the
   * pinned sidebar from 1280 px with the rail between 1025 and 1279 px, the stored mode kept below;
   * the rail and the pinned sidebar reserve their width in {@code scroll-padding-inline-start}
   * (WCAG 2.4.11).
   */
  @Test
  void desktopModesFollowTheirWidthsAndKeepTheStoredMode() {
    run(
        1440,
        900,
        "nav-desktop-modes",
        page -> {
          try {
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            assertThat(page.locator("body")).hasAttribute("data-nav-mode", "drawer");
            assertThat(page.locator("#nav-rail")).isHidden();
            assertThat(page.locator("#hamburger")).isVisible();

            chooseMode(page, "rail");
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            assertThat(page.locator("#nav-rail")).isVisible();
            assertThat(page.locator("#hamburger")).isHidden();
            org.junit.jupiter.api.Assertions.assertEquals("64px", scrollPadding(page));
            org.junit.jupiter.api.Assertions.assertTrue(
                page.locator("main").boundingBox().x >= 64, "main starts right of the rail");
            assertThat(page.getByTestId("nav-rail-group-operations"))
                .hasClass(Pattern.compile("\\bis-current\\b"));

            page.setViewportSize(1100, 900);
            assertThat(page.locator("#nav-rail")).isVisible();
            page.setViewportSize(1024, 900);
            assertThat(page.locator("#nav-rail")).isHidden();
            assertThat(page.locator("#hamburger")).isVisible();
            assertThat(page.locator("body")).hasAttribute("data-nav-mode", "rail");
            org.junit.jupiter.api.Assertions.assertEquals("auto", scrollPadding(page));

            page.setViewportSize(1440, 900);
            chooseMode(page, "pinned");
            assertThat(page.locator("#nav-rail")).isHidden();
            assertThat(page.locator("#sidebar")).isVisible();
            assertThat(page.locator("#sidebar")).not().hasAttribute("inert", "");
            org.junit.jupiter.api.Assertions.assertEquals("300px", scrollPadding(page));
            org.junit.jupiter.api.Assertions.assertTrue(
                page.locator("main").boundingBox().x >= 300, "main starts right of the sidebar");
            assertThat(page.getByTestId("nav-missions")).isVisible();

            page.setViewportSize(1100, 900);
            assertThat(page.locator("#nav-rail")).isVisible();
            assertThat(page.locator("#sidebar")).hasAttribute("inert", "");
            page.setViewportSize(1280, 900);
            assertThat(page.locator("#nav-rail")).isHidden();
            assertThat(page.locator("#sidebar")).isVisible();
          } finally {
            page.setViewportSize(1440, 900);
            chooseMode(page, "drawer");
          }
        });
  }

  /** The switch in the sidebar head stores the mode, which is still there after a reload. */
  @Test
  void theSidebarSwitchStoresTheDesktopMode() {
    run(
        1440,
        900,
        "nav-mode-switch",
        page -> {
          try {
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            page.locator("#hamburger").click();
            page.getByTestId("segment-desktop-mode-sidebar-rail").click();
            assertThat(page.locator("body")).hasAttribute("data-nav-mode", "rail");
            assertThat(page.locator("#nav-rail")).isVisible();

            page.reload();
            assertThat(page.locator("body")).hasAttribute("data-nav-mode", "rail");
            assertThat(page.locator("#nav-rail")).isVisible();
          } finally {
            page.setViewportSize(1440, 900);
            chooseMode(page, "drawer");
          }
        });
  }

  /**
   * A rail flyout is a disclosure: it opens on hover and stays while the pointer is on it (WCAG
   * 1.4.13), closes after the pointer leaves, a click pins it until an outside click, Escape
   * returns focus to the icon, and Enter and Space open it.
   */
  @Test
  void railFlyoutsOpenOnHoverClickAndKeyboardAndCloseOnEscape() {
    run(
        1440,
        900,
        "nav-rail-flyouts",
        page -> {
          try {
            chooseMode(page, "rail");
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            Locator button = page.getByTestId("nav-rail-group-operations");
            Locator flyout = page.locator("#nav-rail-flyout-operations");
            assertThat(button).hasAttribute("aria-expanded", "false");
            assertThat(flyout).isHidden();
            assertThat(flyout.locator(".nav-rail-flyout-head"))
                .hasText(button.getAttribute("aria-label"));
            assertThat(flyout.locator("a[data-entry-key='missions.list']")).hasCount(1);

            button.hover();
            assertThat(flyout).isVisible();
            assertThat(button).hasAttribute("aria-expanded", "true");
            flyout.locator(".nav-rail-flyout-head").hover();
            page.waitForTimeout(600);
            assertThat(flyout).isVisible();

            page.mouse().move(900, 500);
            assertThat(flyout).isHidden();
            assertThat(button).hasAttribute("aria-expanded", "false");

            button.click();
            assertThat(flyout).isVisible();
            page.mouse().move(900, 500);
            page.waitForTimeout(600);
            assertThat(flyout).isVisible();
            page.mouse().click(900, 500);
            assertThat(flyout).isHidden();

            button.focus();
            page.keyboard().press("Enter");
            assertThat(flyout).isVisible();
            page.keyboard().press("Escape");
            assertThat(flyout).isHidden();
            assertThat(button).isFocused();

            page.keyboard().press("Space");
            assertThat(flyout).isVisible();
            page.keyboard().press("Escape");
            assertThat(flyout).isHidden();
            assertThat(button).isFocused();

            assertThat(page.locator("#nav-rail [role='menu']")).hasCount(0);
            assertThat(page.locator("#nav-rail [aria-haspopup]")).hasCount(0);
          } finally {
            chooseMode(page, "drawer");
          }
        });
  }

  /** The rail foot holds the admin toggle, which swaps the groups, and the avatar flyout. */
  @Test
  void railFootSwitchesAdminModeAndOpensTheAvatarFlyout() {
    run(
        1440,
        900,
        "nav-rail-foot",
        page -> {
          try {
            chooseMode(page, "rail");
            E2eSupport.navigate(page, STACK.baseUrl() + "/missions");
            Locator admin = page.getByTestId("nav-rail-admin");
            assertThat(admin).hasAttribute("aria-pressed", "false");
            assertThat(page.getByTestId("nav-rail-group-operations")).isVisible();
            assertThat(page.getByTestId("nav-rail-group-admin-members")).isHidden();

            admin.click();
            assertThat(admin).hasAttribute("aria-pressed", "true");
            assertThat(page.getByTestId("nav-rail-group-admin-members")).isVisible();
            assertThat(page.getByTestId("nav-rail-group-operations")).isHidden();
            admin.click();
            assertThat(page.getByTestId("nav-rail-group-operations")).isVisible();

            page.getByTestId("nav-rail-avatar").click();
            assertThat(page.locator("#nav-rail-flyout-avatar")).isVisible();
            assertThat(page.locator("#nav-rail-flyout-avatar a[data-entry-key='personal.profile']"))
                .isVisible();
            assertThat(page.getByTestId("nav-rail-logout")).isVisible();
          } finally {
            chooseMode(page, "drawer");
          }
        });
  }

  /**
   * Runs one scenario in a fresh authenticated context of the given viewport, dumping the page when
   * it fails.
   *
   * @param width viewport width in CSS pixels
   * @param height viewport height in CSS pixels
   * @param label the dump's file label
   * @param scenario the steps and assertions
   */
  private static void run(int width, int height, String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(width, height))) {
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
