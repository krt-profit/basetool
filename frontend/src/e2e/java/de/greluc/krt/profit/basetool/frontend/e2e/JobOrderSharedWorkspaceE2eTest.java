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
  private static final String REQUESTER_WRITER = "test-officer";
  private static final String REQUESTER_WRITER_PASSWORD = "test-officer-pw";
  private static final String IRIDIUM = "00000000-0000-0000-0000-000000000001";
  private static final String NOTE = "Shared workspace draft survives a peer status change";

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String orderId;
  private static String unrelatedOrderId;
  private static String readerId;
  private static String requesterUnitId;
  private static String materialId;

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
    requesterUnitId =
        seeder.createSquadron(ADMIN, ADMIN_PASSWORD, "Workspace requester " + tag, "WR" + tag);
    seeder.assignStaffelMembership(ADMIN, ADMIN_PASSWORD, readerId, requesterUnitId, false, false);
    materialId = seeder.ensureJobOrderMaterial(ADMIN, ADMIN_PASSWORD, "Workspace material " + tag);
    orderId =
        seeder.createJobOrder(
            ADMIN,
            ADMIN_PASSWORD,
            IRIDIUM,
            requesterUnitId,
            "Workspace " + tag,
            materialId,
            650,
            10);
    unrelatedOrderId =
        seeder.createJobOrder(
            ADMIN, ADMIN_PASSWORD, IRIDIUM, "Unrelated " + tag, materialId, 650, 10);
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
        assertThat(queue.locator("#orders-type-filter option[value='AUEC']")).hasCount(1);
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

  /** A requester can cancel and reopen while a peer keeps an independent note draft. */
  @Test
  void requesterCancellationAndReopeningRefreshTheWorkspaceInPlace() {
    assumeTrue(STACK.managesStack(), "requires the isolated requester fixture");
    String writerId = seeder.getUserId(REQUESTER_WRITER, REQUESTER_WRITER_PASSWORD);
    seeder.assignStaffelleiterSeat(ADMIN, ADMIN_PASSWORD, writerId, requesterUnitId, false);
    String id =
        seeder.createJobOrder(
            ADMIN,
            ADMIN_PASSWORD,
            IRIDIUM,
            requesterUnitId,
            "Workspace lifecycle",
            materialId,
            650,
            10);
    seeder.postBody(ADMIN, ADMIN_PASSWORD, "/api/v1/orders/" + id + "/assignees/" + readerId, "");
    String base = STACK.baseUrl();
    try (BrowserContext reader = authenticated(READER, READER_PASSWORD);
        BrowserContext writer = authenticated(REQUESTER_WRITER, REQUESTER_WRITER_PASSWORD);
        BrowserContext processor = authenticated(ADMIN, ADMIN_PASSWORD)) {
      Page queue = reader.newPage();
      Page detail = reader.newPage();
      Page editor = writer.newPage();
      Page fulfillment = processor.newPage();
      try {
        E2eSupport.navigate(queue, base + "/orders");
        E2eSupport.navigate(detail, base + "/orders/" + id + "?tab=assignees");
        E2eSupport.navigate(editor, base + "/orders/" + id);
        for (Page page : List.of(queue, detail, editor)) {
          page.evaluate("window.__workspaceNoReload = true");
          waitForRoom(page, page == queue ? "orders" : "order:" + id);
        }
        assertThat(queue.locator(row(id))).isVisible();
        assertThat(detail.getByTestId("order-cancel")).hasCount(0);
        assertThat(editor.getByTestId("order-cancel")).isVisible();
        assertThat(editor.locator("#status-select")).hasCount(0);

        detail.locator("[data-trigger='oa-edit-note']").click();
        detail.locator("#assignee-note-text").fill(NOTE);
        String noteVersion = detail.locator("#assignee-note-version").inputValue();

        editor.getByTestId("order-cancel").click();
        assertThat(editor.locator("#status-warning-modal")).isVisible();
        expectLifecycleWrite(
            editor, () -> editor.locator("[data-trigger='od-confirm-status']").click());
        assertThat(editor.locator("#order-head-meta")).hasAttribute("data-status", "CANCELLED");
        assertThat(queue.locator(row(id))).hasCount(0);
        assertThat(detail.locator("#krt-livesync-pill")).isVisible();
        assertThat(detail.locator("#assignee-note-text")).hasValue(NOTE);
        assertThat(detail.locator("#assignee-note-version")).hasValue(noteVersion);

        E2eSupport.navigate(fulfillment, base + "/orders/" + id);
        fulfillment.evaluate("window.__workspaceNoReload = true");
        waitForRoom(fulfillment, "order:" + id);
        assertThat(fulfillment.locator("#tab-handovers")).isHidden();

        expectLifecycleWrite(editor, () -> editor.getByTestId("order-reopen").click());
        assertThat(editor.locator("#order-head-meta")).hasAttribute("data-status", "OPEN");
        assertThat(fulfillment.locator("#order-head-meta")).hasAttribute("data-status", "OPEN");
        var handoversTab = fulfillment.locator("#tab-handovers");
        assertThat(handoversTab).isVisible();
        handoversTab.press("Enter");
        assertThat(handoversTab).hasAttribute("aria-selected", "true");
        assertThat(fulfillment.getByTestId("order-handover-open")).isVisible();
        assertThat(queue.locator(row(id) + " .status-open")).isVisible();
        assertThat(detail.locator("#assignee-note-text")).hasValue(NOTE);
        assertThat(detail.locator("#assignee-note-version")).hasValue(noteVersion);
        var noteResponse =
            detail.waitForResponse(
                response ->
                    response.url().endsWith("/note") && "PUT".equals(response.request().method()),
                () -> detail.locator("[data-trigger='oa-save-note']").click());
        assertEquals(200, noteResponse.status());
        assertThat(detail.locator("#assignees-section").getByText(NOTE)).isVisible();
        for (Page page : List.of(queue, detail, editor, fulfillment)) {
          assertEquals(Boolean.TRUE, page.evaluate("window.__workspaceNoReload === true"));
        }
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(queue, "shared-lifecycle-queue");
        E2eSupport.dump(detail, "shared-lifecycle-reader");
        E2eSupport.dump(editor, "shared-lifecycle-requester");
        E2eSupport.dump(fulfillment, "shared-lifecycle-fulfillment");
        throw failure;
      }
    }
  }

  private static void expectLifecycleWrite(Page page, Runnable action) {
    var response =
        page.waitForResponse(
            result -> result.url().endsWith("/status") && "POST".equals(result.request().method()),
            action);
    assertEquals(200, response.status());
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
    E2eSupport.openFilterPanel(queue);
    queue.locator("#orders-type-filter").selectOption("ITEM");
    assertThat(queue.locator(row(orderId))).hasCount(0);
    E2eSupport.openFilterPanel(queue);
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
