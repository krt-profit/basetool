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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * E2E live-sync coverage for the job-order queue (REQ-ORDERS-056, REQ-FE-015): a form create
 * reaches a peer's open {@code /orders} overview in place through the backend's after-commit {@code
 * orders} frame, and the creator's own open overview receives that frame exactly once.
 */
@Tag("e2e")
class JobOrderQueueLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * The canonical IRIDIUM Squadron seeded at stack bootstrap (a profit-eligible unit, so it is both
   * a valid requester and a valid responsible for the create, and the queue viewer sees it).
   */
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The overview rows of the queue fragment. */
  private static final String QUEUE_ROWS = "#orders-results tr[data-id]";

  /** How long the peer's and the creator's overviews may take to show the created order. */
  private static final double ARRIVAL_TIMEOUT_MS = 30_000;

  /**
   * How long the creator's overview keeps listening after the first frame: two announcing paths
   * would both fire for the one create request, well inside this window.
   */
  private static final double SECOND_FRAME_WINDOW_MS = 5_000;

  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;

  /**
   * Launches the browser and seeds IRIDIUM membership, a job-order material and one initial order
   * so the queue has a non-empty baseline the live create adds to.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E LiveSync Ore");
      seeder.createJobOrder(USERNAME, PASSWORD, IRIDIUM_ID, "E2E seed", materialId, 650, 10);
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
   * Creates an order from the creator's {@code /orders/create} tab while a peer context and the
   * creator's second tab both view {@code /orders}: both overviews gain the row without reloading,
   * and the creator's overview counts exactly one {@code orders} frame on its socket.
   */
  @Test
  void orderCreateReachesEveryOpenOverviewLiveWithOneFrame() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext peer = newContext(storageState);
        BrowserContext creator = newContext(storageState)) {
      Page peerOverview = peer.newPage();
      Page creatorOverview = creator.newPage();
      Page creatorForm = creator.newPage();
      AtomicInteger creatorFrames = new AtomicInteger();
      creatorOverview.onWebSocket(socket ->
          socket.onFrameReceived(frame -> {
            if (isOrdersChange(frame.text())) {
              creatorFrames.incrementAndGet();
            }
          }));
      try {
        openSubscribedOverview(peerOverview, baseUrl);
        openSubscribedOverview(creatorOverview, baseUrl);
        int before = peerOverview.locator(QUEUE_ROWS).count();
        int creatorBefore = creatorOverview.locator(QUEUE_ROWS).count();

        E2eSupport.navigate(creatorForm, baseUrl + "/orders/create");
        creatorForm.locator("#requestingOrgUnitId").selectOption(IRIDIUM_ID);
        creatorForm.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        creatorForm.locator("#handle").fill("E2E live " + UUID.randomUUID());
        E2eSupport.selectComboboxFirstOption(creatorForm.getByTestId("order-material-select"));
        creatorForm.getByTestId("order-material-amount").fill("5");
        creatorFrames.set(0);
        E2eSupport.clickSubmitClearingFooter(creatorForm.getByTestId("order-submit"));
        creatorForm.waitForLoadState();

        awaitRowCount(peerOverview, before + 1);
        awaitRowCount(creatorOverview, creatorBefore + 1);
        creatorOverview.waitForTimeout(SECOND_FRAME_WINDOW_MS);

        assertEquals(
            1, creatorFrames.get(), "the creator's overview must receive the create's frame once");
        assertNoReload(peerOverview, "the peer's overview");
        assertNoReload(creatorOverview, "the creator's overview");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(peerOverview, "orders-queue-livesync-peer");
        E2eSupport.dump(creatorOverview, "orders-queue-livesync-creator-overview");
        E2eSupport.dump(creatorForm, "orders-queue-livesync-creator-form");
        throw failure;
      }
    }
  }

  private static BrowserContext newContext(Path storageState) {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  private static void openSubscribedOverview(Page page, String baseUrl) {
    E2eSupport.navigate(page, baseUrl + "/orders");
    page.waitForLoadState();
    page.evaluate("window.__krtNoReload = true;");
    page.waitForCondition(() ->
        Boolean.TRUE.equals(
            page.evaluate(
                "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                    + " && window.krtLiveSync.subscribedTopics().includes('orders'))")));
  }

  private static void awaitRowCount(Page page, int expected) {
    page.waitForCondition(
        () -> page.locator(QUEUE_ROWS).count() == expected,
        new Page.WaitForConditionOptions().setTimeout(ARRIVAL_TIMEOUT_MS));
  }

  private static void assertNoReload(Page page, String overview) {
    assertEquals(
        Boolean.TRUE,
        page.evaluate("window.__krtNoReload === true"),
        "the live update on " + overview + " must be an in-place swap, not a full-page reload");
  }

  /**
   * Tells whether a relay frame is a {@code changed} signal of the {@code orders} room; the relay
   * sends every frame as one JSON object with a {@code type} and a {@code topic}.
   *
   * @param text the frame's text payload, {@code null} for a binary frame
   * @return {@code true} for a {@code changed} frame whose topic is {@code orders}
   */
  private static boolean isOrdersChange(@Nullable String text) {
    if (text == null) {
      return false;
    }
    JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
    return "changed".equals(textField(frame, "type")) && "orders".equals(textField(frame, "topic"));
  }

  private static @Nullable String textField(JsonObject frame, String name) {
    return frame.has(name) ? frame.get(name).getAsString() : null;
  }
}
