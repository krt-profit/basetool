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
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Drives the admin dashboard of REQ-UI-034 in a browser: the default of the hub and four admin
 * widgets, the edit mode and the range option on the admin board, the removal of the announcement
 * in the Information widget, the redirect of the former announcement page and the refusal of a
 * member.
 */
@Tag("e2e")
class AdminDashboardE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String WIDE = "[data-dashboard-column='WIDE'] [data-dashboard-item]";
  private static final String NARROW = "[data-dashboard-column='NARROW'] [data-dashboard-item]";

  private static final String RESET_ADMIN_DASHBOARD =
      """
      async () => {
        const board = document.getElementById('dashboard-board');
        await window.krtFetch.write({
          method: 'DELETE',
          url: '/dashboard/admin?version=' + board.dataset.version,
          toast: false,
        });
      }
      """;

  private static Playwright playwright;
  private static Browser browser;
  private static Path adminState;
  private static Path memberState;

  /** Launches the browser and captures the administrator and the member session. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership("test-admin", "test-admin-pw");
    }
    adminState = captureState("test-admin", "admin");
    memberState = captureState("test-member", "member");
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

  /** Leaves the administrator with the default admin dashboard. */
  @BeforeEach
  void restoreTheDefault() {
    run(
        adminState,
        "admin-dashboard-restore",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");
          page.evaluate(RESET_ADMIN_DASHBOARD);
        });
  }

  /** The default is the hub in the wide column and the four admin widgets in the narrow one. */
  @Test
  void theDefaultIsTheHubAndFourAdminWidgets() {
    run(
        adminState,
        "admin-dashboard-default",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");

          assertThat(page.getByTestId("dashboard-board")).hasAttribute("data-mode", "admin");
          assertThat(page.locator(WIDE)).hasCount(1);
          assertThat(page.locator(WIDE).first()).hasAttribute("data-widget-type", "ADMIN_HUB");
          assertThat(page.locator(NARROW)).hasCount(4);
          assertThat(page.locator(NARROW).nth(0))
              .hasAttribute("data-widget-type", "DELETION_REQUESTS");
          assertThat(page.locator(NARROW).nth(1))
              .hasAttribute("data-widget-type", "DISCORD_REGISTRATIONS");
          assertThat(page.locator(NARROW).nth(2)).hasAttribute("data-widget-type", "SYNC_STATUS");
          assertThat(page.locator(NARROW).nth(3)).hasAttribute("data-widget-type", "OPEN_TERMS");
          assertThat(page.locator("[data-widget-content]")).hasCount(5);
          assertThat(page.getByTestId("admin-hub")).isVisible();
          assertThat(page.getByTestId("admin-sync-uex")).isVisible();
          assertThat(page.getByTestId("admin-sync-p4k")).isVisible();
        });
  }

  /** Removing the hub and restoring the default changes the admin board in place. */
  @Test
  void theEditModeRemovesTheHubAndTheRestoreBringsItBack() {
    run(
        adminState,
        "admin-dashboard-edit",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");
          page.getByTestId("dashboard-customise").click();

          int version =
              Integer.parseInt(page.locator("#dashboard-board").getAttribute("data-version"));
          page.getByTestId("dashboard-widget-admin-hub").getByTestId("dashboard-remove").click();
          assertThat(page.locator(WIDE)).hasCount(0);
          assertThat(page.locator("#dashboard-board"))
              .hasAttribute("data-version", String.valueOf(version + 1));
          page.reload();
          assertThat(page.locator(WIDE)).hasCount(0);

          page.getByTestId("dashboard-customise").click();
          page.getByTestId("dashboard-reset").click();
          page.getByTestId("dashboard-reset-confirm").click();
          assertThat(page.locator(WIDE)).hasCount(1);
          assertThat(page.getByTestId("admin-hub")).isVisible();
        });
  }

  /** The range option of the sync status is stored on the admin board and survives a reload. */
  @Test
  void theSyncStatusKeepsItsRange() {
    run(
        adminState,
        "admin-dashboard-range",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin");
          page.getByTestId("dashboard-customise").click();

          page.getByTestId("dashboard-widget-sync-status").getByTestId("dashboard-options").click();
          page.locator("#dashboard-option-range").selectOption("30");
          page.getByTestId("dashboard-options-save").click();

          assertThat(page.getByTestId("dashboard-widget-sync-status"))
              .hasAttribute("data-days", "30");
          page.reload();
          assertThat(page.getByTestId("dashboard-widget-sync-status"))
              .hasAttribute("data-days", "30");
        });
  }

  /** The former announcement page leads to the dashboard, where the editor removes the text. */
  @Test
  void theAnnouncementIsEditedAndRemovedOnTheDashboard() {
    run(
        adminState,
        "admin-dashboard-announcement",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/admin/announcement");
          assertEquals(STACK.baseUrl() + "/", page.url());

          page.getByTestId("dashboard-info-edit").click();
          page.getByTestId("dashboard-info-text").fill("Entfernen-Test " + System.nanoTime());
          page.getByTestId("dashboard-info-save").click();
          assertThat(page.getByTestId("home-info")).containsText("Entfernen-Test");

          page.getByTestId("dashboard-info-delete").click();
          page.getByTestId("dashboard-info-delete-confirm").click();

          assertThat(page.getByTestId("home-info")).not().containsText("Entfernen-Test");
          assertThat(page.getByTestId("dashboard-info-delete")).hasCount(0);
        });
  }

  /** A member reaches neither the admin dashboard nor an admin widget. */
  @Test
  void aMemberIsRefused() {
    run(
        memberState,
        "admin-dashboard-member",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/");
          Object status =
              page.evaluate(
                  """
                  async () => (await fetch('/dashboard/widgets/open-terms', {
                    headers: { 'X-Requested-With': 'XMLHttpRequest' },
                  })).status
                  """);
          assertEquals(403, ((Number) status).intValue());
        });
  }

  /** Logs in and keeps the session in a file of its own, as the shared one is overwritten. */
  private static Path captureState(String username, String name) {
    try {
      Path shared =
          E2eSupport.authenticatedStorageState(
              browser, STACK.baseUrl(), username, username + "-pw");
      return Files.copy(
          shared, shared.resolveSibling(name + "-state.json"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IllegalStateException("Could not keep the session of " + username, e);
    }
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
