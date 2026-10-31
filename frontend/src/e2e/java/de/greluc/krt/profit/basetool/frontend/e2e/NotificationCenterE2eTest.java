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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the notifications centre: a notification produced by a seeded domain event is marked
 * read in place, without a reload (REQ-FE-001, REQ-FE-002), and a click on one in the bell dropdown
 * opens its subject and marks it read (REQ-NOTIF-032).
 *
 * <p>{@code test-member} creates job orders so that {@code test-admin} is notified; each row is
 * located by its order's {@code displayId}. Mutates data, so it runs only against the ephemeral
 * stack.
 */
@Tag("e2e")
class NotificationCenterE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** Canonical IRIDIUM Squadron id (opted into job-order processing during bootstrap). */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /** The seeded order's display id — the unique discriminator in the notification text. */
  private static String orderDisplayId;

  /** The id of the order whose notification is opened from the bell dropdown. */
  private static String openedOrderId;

  /** The display id of the order whose notification is opened from the bell dropdown. */
  private static String openedOrderDisplayId;

  /**
   * Launches the browser, seeds two job orders as {@code test-member} so {@code test-admin}
   * receives a {@code JOB_ORDER_CREATED} notification for each (mirroring the admin's role into the
   * backend first), and captures one authenticated admin session reused across the tests.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.getUserId(ADMIN_USER, ADMIN_PASSWORD);
      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Notif Material");
      orderDisplayId = displayIdOf(seeder, seedOrder(seeder, materialId, "E2E Notif Order"));
      openedOrderId = seedOrder(seeder, materialId, "E2E Notif Open Order");
      openedOrderDisplayId = displayIdOf(seeder, openedOrderId);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN_USER, ADMIN_PASSWORD);
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
   * Opens the notifications centre, finds the unread notification for the seeded order, marks it
   * read, and asserts the row transitions in place: the mark-read POST fires, the row gains {@code
   * data-notif-read="true"}, its mark-read button disappears, and the marker proves no reload
   * happened.
   */
  @Test
  void marksASeededNotificationReadInPlace() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/notifications");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");

        Locator row =
            page.locator(
                "#notification-page-list .notification-item",
                new Page.LocatorOptions()
                    .setHasText(Pattern.compile("#" + orderDisplayId + "(?!\\d)")));
        assertThat(row).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(row).hasAttribute("data-notif-read", "false");

        Locator markRead = row.locator("[data-notif-mark-read]");
        assertThat(markRead).isVisible();
        page.waitForResponse(
            response ->
                response.url().contains("/notifications/")
                    && response.url().endsWith("/read")
                    && "POST".equals(response.request().method()),
            markRead::click);

        assertThat(row).hasAttribute("data-notif-read", "true");
        assertThat(row.locator("[data-notif-mark-read]")).hasCount(0);
        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "mark-as-read must update in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "notification-center");
        throw failure;
      }
    }
  }

  /**
   * Opens the bell dropdown, clicks the unread notification for the second seeded order and asserts
   * that the click lands on that order's detail page and that the notification is read afterwards
   * (REQ-NOTIF-032).
   */
  @Test
  void opensANotificationsSubjectFromTheBellAndMarksItRead() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/");
        page.waitForLoadState();
        page.locator("#notification-toggle").click();

        Locator row =
            page.locator(
                "#notification-dropdown-list .notification-item",
                new Page.LocatorOptions()
                    .setHasText(Pattern.compile("#" + openedOrderDisplayId + "(?!\\d)")));
        assertThat(row).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertThat(row).hasAttribute("data-notif-read", "false");

        row.locator("a[data-notif-open]").click();
        page.waitForURL("**/orders/" + openedOrderId);

        E2eSupport.navigate(page, baseUrl + "/notifications");
        page.waitForLoadState();
        Locator inboxRow =
            page.locator(
                "#notification-page-list .notification-item",
                new Page.LocatorOptions()
                    .setHasText(Pattern.compile("#" + openedOrderDisplayId + "(?!\\d)")));
        assertThat(inboxRow).hasAttribute("data-notif-read", "true");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "notification-open");
        throw failure;
      }
    }
  }

  /**
   * Creates a job order as {@code test-member} for the IRIDIUM squadron.
   *
   * @param seeder the backend seeder
   * @param materialId the order's material
   * @param title the order's title
   * @return the new order's id
   */
  private static String seedOrder(BackendSeeder seeder, String materialId, String title) {
    return seeder.createJobOrder(
        MEMBER_USER, MEMBER_PASSWORD, IRIDIUM_SQUADRON_ID, title, materialId, 650, 100.0);
  }

  /**
   * Reads an order's display id as the admin sees it.
   *
   * @param seeder the backend seeder
   * @param orderId the order's id
   * @return the order's display id
   */
  private static String displayIdOf(BackendSeeder seeder, String orderId) {
    String orderJson = seeder.getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/orders/" + orderId);
    return JsonParser.parseString(orderJson).getAsJsonObject().get("displayId").getAsString();
  }

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused admin storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }
}
