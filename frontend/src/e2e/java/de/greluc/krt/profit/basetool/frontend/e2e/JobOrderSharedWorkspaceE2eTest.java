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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Exercises distinct requester and processor accounts on the live shared order workspace. */
@Tag("e2e")
class JobOrderSharedWorkspaceE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String READER = "test-member";
  private static final String READER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM = "00000000-0000-0000-0000-000000000001";
  private static final String NOTE = "Shared workspace draft survives a peer status change";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String orderId;
  private static String unrelatedOrderId;
  private static String readerId;

  /** Seeds a non-Profit requester and proves its concrete read before adding a personal entry. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (!STACK.managesStack()) {
      return;
    }
    seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(ADMIN, ADMIN_PASSWORD);
    readerId = seeder.getUserId(READER, READER_PASSWORD);
    assertNotEquals(seeder.getUserId(ADMIN, ADMIN_PASSWORD), readerId);
    seeder.grantStoredRoles(READER, READER_PASSWORD, List.of("KRT_MEMBER"));
    String tag = UUID.randomUUID().toString().substring(0, 8);
    String requester =
        seeder.createSquadron(ADMIN, ADMIN_PASSWORD, "Workspace requester " + tag, "WR" + tag);
    seeder.assignStaffelMembership(ADMIN, ADMIN_PASSWORD, readerId, requester, false, false);
    String material =
        seeder.ensureJobOrderMaterial(ADMIN, ADMIN_PASSWORD, "Workspace material " + tag);
    orderId =
        seeder.createJobOrder(
            ADMIN, ADMIN_PASSWORD, IRIDIUM, requester, "Workspace " + tag, material, 650, 10);
    unrelatedOrderId =
        seeder.createJobOrder(
            ADMIN, ADMIN_PASSWORD, IRIDIUM, "Unrelated " + tag, material, 650, 10);
    JsonObject readable = order(READER, READER_PASSWORD);
    assertEquals(0, readable.getAsJsonArray("assignees").size());
    assertEquals(false, readable.get("canEdit").getAsBoolean());
    assertEquals(1, readable.getAsJsonArray("materials").size());
    assertEquals(
        403,
        seeder.postForStatus(
            READER, READER_PASSWORD, "/api/v1/orders/" + orderId + "/assignees/" + readerId, ""));
    seeder.postBody(
        ADMIN, ADMIN_PASSWORD, "/api/v1/orders/" + orderId + "/assignees/" + readerId, "");
  }

  /** Releases the browser and driver. */
  @AfterAll
  static void tearDown() {
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /** Checks complete reading, live updates, independent note versions and remembered filters. */
  @Test
  void distinctAccountsRefreshInPlaceWithoutLosingAnOpenNoteDraft() {
    assumeTrue(STACK.managesStack(), "requires the isolated requester fixture");
    String base = STACK.baseUrl();
    try (BrowserContext requester = authenticated(READER, READER_PASSWORD);
        BrowserContext processor = authenticated(ADMIN, ADMIN_PASSWORD)) {
      Page queue = requester.newPage();
      Page detail = requester.newPage();
      Page editor = processor.newPage();
      try {
        E2eSupport.navigate(queue, base + "/orders");
        assertThat(queue.locator("input[name='scope'][value='ALL']")).isChecked();
        assertThat(queue.getByTestId("orders-create-link")).hasCount(0);
        assertThat(queue.locator("#orders-type-filter option[value='AUEC']")).hasCount(0);
        assertThat(queue.locator(row(orderId))).isVisible();
        assertThat(queue.locator(row(unrelatedOrderId))).hasCount(0);
        queue.evaluate("window.__workspaceNoReload = true");
        waitForRoom(queue, "orders");

        E2eSupport.navigate(detail, base + "/orders/" + orderId + "?tab=assignees");
        assertThat(detail.getByTestId("order-progress")).isVisible();
        assertThat(detail.locator("#status-select")).hasCount(0);
        assertThat(detail.locator("[data-trigger='oa-add-me']")).hasCount(0);
        assertThat(detail.locator("#assignee-add-select")).hasCount(0);
        detail.evaluate("window.__workspaceNoReload = true");
        waitForRoom(detail, "order:" + orderId);
        assertForbiddenRoom(detail);

        detail.locator("[data-trigger='oa-edit-note']").click();
        detail.locator("#assignee-note-text").fill(NOTE);
        String edgeVersion = detail.locator("#assignee-note-version").inputValue();

        E2eSupport.navigate(editor, base + "/orders/" + orderId);
        editor.waitForResponse(
            response ->
                response.url().endsWith("/status") && "POST".equals(response.request().method()),
            () -> editor.locator("#status-select").selectOption("IN_PROGRESS"));
        assertThat(queue.locator(row(orderId) + " .status-in_progress")).isVisible();
        assertThat(detail.locator("#krt-livesync-pill")).isVisible();
        assertThat(detail.locator("#assignee-note-text")).hasValue(NOTE);
        assertThat(detail.locator("#assignee-note-version")).hasValue(edgeVersion);

        var saved =
            detail.waitForResponse(
                response ->
                    response.url().endsWith("/note") && "PUT".equals(response.request().method()),
                () -> detail.locator("[data-trigger='oa-save-note']").click());
        assertEquals(200, saved.status());
        assertThat(detail.locator("#assignees-section").getByText(NOTE)).isVisible();
        detail.locator("#krt-livesync-pill").click();
        assertThat(detail.locator("#order-status-badge.status-active")).isVisible();
        assertEquals(
            NOTE,
            order(READER, READER_PASSWORD)
                .getAsJsonArray("assignees")
                .get(0)
                .getAsJsonObject()
                .get("note")
                .getAsString());
        assertEquals(Boolean.TRUE, detail.evaluate("window.__workspaceNoReload === true"));
        assertEquals(Boolean.TRUE, queue.evaluate("window.__workspaceNoReload === true"));

        rememberNarrowing(queue, requester, base);
        E2eSupport.capture(queue, "shared-workspace-queue");
        E2eSupport.capture(detail, "shared-workspace-requester");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(queue, "shared-workspace-queue");
        E2eSupport.dump(detail, "shared-workspace-requester");
        E2eSupport.dump(editor, "shared-workspace-processor");
        throw failure;
      }
    }
  }

  private static BrowserContext authenticated(String username, String password) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(
                E2eSupport.authenticatedStorageState(
                    browser, STACK.baseUrl(), username, password)));
  }

  private static JsonObject order(String username, String password) {
    return JsonParser.parseString(seeder.getBody(username, password, "/api/v1/orders/" + orderId))
        .getAsJsonObject();
  }

  private static String row(String id) {
    return "[data-testid='order-row'][data-id='" + id + "']";
  }

  private static void waitForRoom(Page page, String topic) {
    page.waitForCondition(() ->
        Boolean.TRUE.equals(
            page.evaluate(
                "topic => window.krtLiveSync?.subscribedTopics().includes(topic)", topic)));
  }

  private static void assertForbiddenRoom(Page detail) {
    assertEquals(
        403,
        seeder.attemptGetStatus(READER, READER_PASSWORD, "/api/v1/orders/" + unrelatedOrderId));
    detail.evaluate(
        "topic => { window.__deniedWorkspaceRoom = false;"
            + " window.krtLiveSync.subscribe(topic, {"
            + " onDenied() { window.__deniedWorkspaceRoom = true; } }); }",
        "order:" + unrelatedOrderId);
    detail.waitForCondition(
        () -> Boolean.TRUE.equals(detail.evaluate("window.__deniedWorkspaceRoom")));
    assertEquals(
        Boolean.FALSE,
        detail.evaluate(
            "topic => window.krtLiveSync.subscribedTopics().includes(topic)",
            "order:" + unrelatedOrderId));
  }

  private static void rememberNarrowing(Page queue, BrowserContext requester, String base) {
    queue.getByTestId("segment-scope-mine").click();
    queue.locator("#orders-type-filter").selectOption("ITEM");
    assertThat(queue.locator(row(orderId))).hasCount(0);
    queue.locator("#orders-type-filter").selectOption("MATERIAL");
    assertThat(queue.locator(row(orderId))).isVisible();
    assertEquals(Boolean.TRUE, queue.evaluate("window.__workspaceNoReload === true"));
    Page restored = requester.newPage();
    try {
      E2eSupport.navigate(restored, base + "/orders");
      assertThat(restored.locator("input[name='scope'][value='MINE']")).isChecked();
      assertThat(restored.locator("#orders-type-filter")).hasValue("MATERIAL");
      assertThat(restored.locator(row(orderId))).isVisible();
    } finally {
      restored.close();
    }
  }
}
