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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies Lager view swaps and the replacement filter controls in a browser. */
@Tag("e2e")
class LagerViewSwitchFixtureE2eTest {

  private static final String ORIGIN = "https://lager-view.test";

  @Test
  void aPeerRefreshWaitsForTheViewAndAFailedSwitchPreservesIt() {
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, false);
        BrowserContext context = browser.newContext()) {
      AtomicReference<Route> pendingItems = new AtomicReference<>();
      AtomicReference<Route> pendingMaterial = new AtomicReference<>();
      context.route(ORIGIN + "/**", route -> respond(route, pendingItems, pendingMaterial));
      Page page = context.newPage();
      page.navigate(ORIGIN + "/");

      Locator items = page.locator("#items");
      Locator material = page.locator("#material");
      assertThat(material).hasAttribute("aria-current", "page");

      items.press("Enter");
      page.waitForCondition(() -> pendingItems.get() != null);
      page.evaluate("() => document.dispatchEvent(new Event('fixture:peer'))");
      assertThat(material).hasAttribute("aria-current", "page");
      assertEquals(null, page.locator("#workspace").getAttribute("data-peer-view"));

      pendingItems
          .get()
          .fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(list(true)));

      assertThat(items).hasAttribute("aria-current", "page");
      assertThat(items).isFocused();
      assertThat(page.locator("#workspace")).hasAttribute("data-peer-view", "items");
      assertThat(page.locator("[data-lager-view-link]"))
          .hasAttribute("href", Pattern.compile("storage=my&view=items"));

      Locator filterToggle = page.locator("#filter-toggle");
      filterToggle.click();
      assertThat(page.locator("#filters")).isVisible();
      assertThat(page.locator("#filter")).isFocused();
      assertThat(page.locator("[data-filter-chips]")).isVisible();
      page.locator("[data-filter-chips-reset]").click();
      assertThat(page.locator("#filter")).not().isChecked();
      assertThat(page.locator("[data-filter-chips]")).isHidden();

      material.press("Enter");
      page.waitForCondition(() -> pendingMaterial.get() != null);
      pendingMaterial.get().fulfill(new Route.FulfillOptions().setStatus(503));
      assertThat(page.locator(".notification-toast")).containsText("Unable to load inventory");
      assertThat(items).hasAttribute("aria-current", "page");
      assertEquals(ORIGIN + "/inventory?view=items", page.url());
    }
  }

  /**
   * Holds the view responses until the test releases them and serves the other fixture resources.
   *
   * @param route the intercepted request
   * @param items the holder for the in-flight Items response
   * @param material the holder for the in-flight Material response
   */
  private static void respond(
      Route route, AtomicReference<Route> items, AtomicReference<Route> material) {
    URI uri = URI.create(route.request().url());
    if ("/inventory".equals(uri.getPath())) {
      (uri.getQuery().contains("view=items") ? items : material).set(route);
      return;
    }
    StaticFixtureServer.serve(route, page());
  }

  /**
   * @return the fixture shell using the production swap and filter modules
   */
  private static String page() {
    return """
    <!DOCTYPE html>
    <html lang="en"><head>
      <link rel="stylesheet" href="/css/kernel/styles.css">
      <link rel="stylesheet" href="/css/kernel/filters.css">
      <script>window.krtToastI18n = { errorTitle: 'Error' };</script>
      <script type="module">
        import { bindLagerViewSwitch } from '/js/inventory/inventory-view-switch.js';
        import '/js/kernel/krt-filter-panel.js';
        import '/js/kernel/krt-filter-chips.js';

        const afterViewSwitch = bindLagerViewSwitch({
          container: '#workspace',
          fragmentValue: 'view'
        });
        document.addEventListener('fixture:peer', () => {
          void afterViewSwitch(() => {
            document.getElementById('workspace').dataset.peerView =
              new URL(location.href).searchParams.get('view');
          });
        });
      </script>
    </head><body>
      <nav class="tab-nav">Storage</nav>
      <a href="/inventory?storage=my" data-lager-view-link>My stock</a>
      <section id="workspace" class="list-panel">
    """
        + list(false)
        + """
          </section>
        </body></html>
        """;
  }

  /**
   * Renders a selectable view and its filter controls.
   *
   * @param items whether Items is selected
   * @return the replacement markup
   */
  private static String list(boolean items) {
    return """
    <div class="toolbar">
      <nav class="krt-segmented" data-lager-view-switch
           data-lager-view-error="Unable to load inventory">
        <a id="material" class="krt-segmented__item" href="/inventory" %s>Material</a>
        <a id="items" class="krt-segmented__item" href="/inventory?view=items" %s>Items</a>
      </nav>
      <button id="filter-toggle" type="button" aria-controls="filters">Filter</button>
      <div id="filters" data-filter-panel hidden>
        <label><input id="filter" type="checkbox" name="active" checked>Stock</label>
      </div>
    </div>
    <div data-filter-chips data-filter-form="filters" hidden>
      <button data-filter-chips-reset type="button">Reset</button>
    </div>
    <div class="card card--flush"><table class="data-table"><thead><tr>
      <th scope="col">Name</th>
    </tr></thead><tbody><tr><td>Quantanium</td></tr></tbody></table></div>
    """
        .formatted(items ? "" : "aria-current='page'", items ? "aria-current='page'" : "");
  }
}
