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
 * Two-context live-sync coverage for the refinery-order detail page (#1238, REQ-FE-015 / ADR-0094):
 * an edit one viewer saves must appear on another viewer's form without a manual reload — the
 * {@code order} section key crossing the {@code refinery-order:{id}} room.
 *
 * <p>This is the page family that the #1235 sweep could not cover, because save / store / cancel
 * all navigated away to the list and the template exposed no fragment seam. The room only works on
 * top of that conversion, so this test is really asserting both halves at once: that a save
 * re-renders the {@code order} section in place for the acting client (page A keeps its no-reload
 * marker), and that the same section is pushed to a passive viewer (page B).
 *
 * <p>The deterministic pre-mutation wait is {@code window.krtLiveSync.subscribedTopics()} becoming
 * non-empty — a subscribe is registered only once its async server-side authorization has acked, so
 * A's change frame cannot race past B's subscription. Two browser contexts as the same test user
 * are two distinct {@code /ws/sync} sockets, which is exactly what the relay fans out between (it
 * skips the originating session).
 *
 * <p>Ore-Sales is the mutation: a plain numeric field rendered inside the swapped {@code order}
 * fragment, so what the peer sees is unambiguous. The main form still has to be made
 * <em>submittable</em> first, though — its {@code required} input-material combobox can render
 * empty for a freshly-seeded material (see the inline comment), and a blocked submit would silently
 * look like a live-sync failure.
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

        // B starts on the seeded value, not the one A is about to save.
        assertThat(pageB.locator("#oreSales"))
            .not()
            .hasAttribute("value", Pattern.compile("^" + NEW_ORE_SALES));

        // A full reload on either page would clear these markers; an in-place swap leaves them.
        pageA.evaluate("window.__krtNoReload = true;");
        pageB.evaluate("window.__krtNoReload = true;");

        // Deterministic wait: B is registered with the relay once its refinery-order:{id} subscribe
        // is acked (subscribedTopics non-empty), so A's change frame cannot race past it.
        pageB.waitForCondition(
            () ->
                Boolean.TRUE.equals(
                    pageB.evaluate(
                        "!!(window.krtLiveSync && window.krtLiveSync.subscribedTopics"
                            + " && window.krtLiveSync.subscribedTopics().length > 0)")));

        // The input-material picker is `required`, but its options come from the 10-minute-cached
        // /api/v1/materials, which need not contain this test's freshly-seeded material — so the
        // row's pre-selected option can be absent, leaving the picker empty. The browser then
        // blocks
        // the submit with a native validation bubble, no POST is issued, and every later assertion
        // fails for the wrong reason. Pick whatever RAW material the combobox offers, exactly as
        // RefineryOrderLifecycleE2eTest#editsAnOrderThroughTheUi does. The form carries no
        // output-material field, so the backend re-infers the output; this test asserts Ore-Sales.
        E2eSupport.selectComboboxFirstOption(
            pageA.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"));

        // Context A saves. The fixed footer can intercept the click on the bottom action row, so
        // drop it first (the same guard the other refinery UI tests use). Waiting on the update
        // POST's own response makes a submit that never fires — the validation-bubble trap above —
        // fail here and loudly, instead of silently downgrading into a "peer never synced" failure.
        pageA.evaluate(
            "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
                + " 'none'; } }");
        pageA.locator("#oreSales").fill(NEW_ORE_SALES);
        pageA.waitForResponse(
            response ->
                response.url().contains("/refinery-orders/" + orderId)
                    && "POST".equals(response.request().method()),
            () -> pageA.locator("button[form='refineryOrderMainForm']").click());

        // A's own form is re-rendered in place from the saved state — the save did not navigate to
        // the list, which is the REQ-FE-001 half of this change. The assertion is on the value
        // ATTRIBUTE, not the property: `fill` above set only the property, so the attribute can
        // change to the persisted value ("54321.0", a Double) only by a server re-render of the
        // `order` fragment. Asserting the property here would pass on the typed-in text alone and
        // prove nothing.
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

        // The assertion under test: context B — which did nothing — shows the new value, pushed
        // over /ws/sync and applied as an in-place `order` section swap. B never typed, so its
        // property and attribute both come from the re-rendered fragment.
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
