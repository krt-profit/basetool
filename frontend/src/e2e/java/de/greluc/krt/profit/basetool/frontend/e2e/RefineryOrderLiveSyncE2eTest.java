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
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Two-context live-sync coverage for the refinery-order detail page (REQ-FE-015): an edit saved by
 * one viewer appears on another viewer's form without a reload, via the {@code order} section of
 * the {@code refinery-order:{id}} room.
 *
 * <p>Waits for {@code window.krtLiveSync.subscribedTopics()} to be non-empty before mutating, so
 * the change cannot race the subscription. Ore sales is the mutated field.
 */
@Tag("e2e")
class RefineryOrderLiveSyncE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The catalog-seeded refinery-hosting location the order runs at. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  /** The Ore-Sales value context A saves; context B's form must pick it up live. */
  private static final String NEW_ORE_SALES = "54321";

  private static Playwright playwright;
  private static Browser browser;
  private static String orderId;

  /** Launches the browser and seeds the IRIDIUM membership plus one OPEN refinery order. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String hubLocationId = seeder.findLocationIdByName(USERNAME, PASSWORD, REFINERY_HUB);
      String materialId =
          seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E LiveSync Refinery Material");
      orderId =
          seeder.createRefineryOrder(USERNAME, PASSWORD, hubLocationId, materialId, null, null);
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
   * Context A edits Ore-Sales and saves; context B — a passive viewer that never reloads — must
   * show the new value, driven purely by the change signal over {@code /ws/sync}. Both contexts
   * assert their no-reload marker survived, so a full-page reload on either side fails the test
   * rather than masquerading as a live update.
   */
  @Test
  void saveByOneViewerPropagatesToAnotherViewerLive() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext contextA =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState));
        BrowserContext contextB =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(storageState))) {
      Page pageA = contextA.newPage();
      Page pageB = contextB.newPage();
      try {
        E2eSupport.navigate(pageA, baseUrl + "/refinery-orders/" + orderId);
        pageA.waitForLoadState();
        E2eSupport.navigate(pageB, baseUrl + "/refinery-orders/" + orderId);
        pageB.waitForLoadState();

        assertThat(pageB.locator("#oreSales"))
            .not()
            .hasAttribute("value", Pattern.compile("^" + NEW_ORE_SALES));

        pageA.evaluate("window.__krtNoReload = true;");
        pageB.evaluate("window.__krtNoReload = true;");

        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        E2eSupport.selectComboboxFirstOption(
            pageA.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"));

        pageA.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        pageA.locator("#oreSales").fill(NEW_ORE_SALES);
        pageA.waitForResponse(
            response ->
                response.url().contains("/refinery-orders/" + orderId)
                    && "POST".equals(response.request().method()),
            () -> pageA.locator("button[form='refineryOrderMainForm']").click());

        assertThat(pageA.locator("#refineryOrderMainForm"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(pageA.locator("#oreSales"))
            .hasAttribute(
                "value",
                Pattern.compile("^" + NEW_ORE_SALES),
                new LocatorAssertions.HasAttributeOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            pageA.evaluate("window.__krtNoReload === true"),
            "the acting client's save must be an in-place swap — no navigation, no reload");

        assertThat(pageB.locator("#oreSales"))
            .hasAttribute(
                "value",
                Pattern.compile("^" + NEW_ORE_SALES),
                new LocatorAssertions.HasAttributeOptions().setTimeout(20_000));
        assertEquals(
            Boolean.TRUE,
            pageB.evaluate("window.__krtNoReload === true"),
            "the live update on the second viewer must be an in-place swap — no full-page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(pageA, "refinery-order-livesync-a");
        E2eSupport.dump(pageB, "refinery-order-livesync-b");
        throw failure;
      }
    }
  }
}
