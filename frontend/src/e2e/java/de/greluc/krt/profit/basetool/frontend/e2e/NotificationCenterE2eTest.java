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
 * Functional coverage for the notifications centre ({@code /notifications}), which previously had
 * no end-to-end test. It produces a real notification through a seeded domain event, then drives
 * the mark-as-read mutation in the UI and asserts it patches the row in place (REQ-FE-001/002,
 * REQ-NOTIF-*): no page reload, the row flips to read, and its mark-read affordance disappears.
 *
 * <p><b>Producing a notification.</b> Notification rules exclude the actor, so producer and viewer
 * must differ. {@code test-member} (a plain member) creates a job order for the IRIDIUM Squadron;
 * the seeded {@code JOB_ORDER_CREATED} rule notifies the responsible unit's officers/leads plus
 * global admins, excluding the actor — so {@code test-admin} (a global admin) receives it. The
 * admin's realm role must be mirrored into the backend before the event fires, so {@code
 * getUserId(test-admin)} (which logs the admin in and syncs its roles) runs first. The seeded
 * order's {@code displayId} makes the notification text ({@code "New job order #<displayId> for
 * <unit>"}) uniquely locatable on the shared stack, where sibling suites also create orders — so
 * the test never asserts absolute counts, only the specific row and its per-item transition.
 *
 * <p>Live SSE push is deliberately not asserted (best-effort, jittered reconnect, ≤60 s poll
 * fallback — materially flakier); the persisted-list assertion after navigation is the robust
 * contract. Tagged {@code @Tag("e2e")}: it mutates data, so it runs only against the ephemeral
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

  /**
   * Launches the browser, seeds a job order as {@code test-member} so {@code test-admin} receives a
   * {@code JOB_ORDER_CREATED} notification (mirroring the admin's role into the backend first), and
   * captures one authenticated admin session reused across the test.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      // Log the admin in first so its Admin realm role is mirrored into user_roles before the event
      // fires — the JOB_ORDER_CREATED rule's ROLE-ADMIN selector resolves recipients from that.
      seeder.getUserId(ADMIN_USER, ADMIN_PASSWORD);
      String materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E Notif Material");
      String orderId =
          seeder.createJobOrder(
              MEMBER_USER,
              MEMBER_PASSWORD,
              IRIDIUM_SQUADRON_ID,
              "E2E Notif Order",
              materialId,
              650,
              100.0);
      String orderJson = seeder.getBody(ADMIN_USER, ADMIN_PASSWORD, "/api/v1/orders/" + orderId);
      orderDisplayId =
          JsonParser.parseString(orderJson).getAsJsonObject().get("displayId").getAsString();
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

        // Target the exact notification for the seeded order. The display id is always numeric, so
        // it is interpolated directly (NOT via Pattern.quote, whose Java \Q…\E fences are not valid
        // JS regex quoting once Playwright serialises the pattern to its driver). The negative
        // lookahead avoids a shorter id matching a longer one, e.g. #1 must not match #12 on the
        // shared stack where sibling suites also create orders.
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

  // ---------------------------------------------------------------------- helpers --

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
