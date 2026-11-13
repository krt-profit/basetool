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
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.nio.file.Files;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks extracted action geometry and filter reset behavior without an application stack. */
@Tag("e2e")
class FilterResetTouchTargetE2eTest {

  private static final String ORIGIN = "https://filter-layout.test";
  private static Playwright playwright;
  private static Browser browser;

  @BeforeAll
  static void openBrowser() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, false);
  }

  @AfterAll
  static void closeBrowser() {
    if (browser != null) browser.close();
    if (playwright != null) playwright.close();
  }

  @ParameterizedTest
  @CsvSource({
    "375,812,true,44",
    "810,1080,true,44",
    "1024,768,true,44",
    "1180,820,true,44",
    "1280,800,false,32",
    "1600,900,false,32"
  })
  void resetRetainsItsDeviceTargetAndClearsTheActiveFilter(
      int width, int height, boolean touch, int expectedHeight) throws IOException {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setViewportSize(width, height).setHasTouch(touch))) {
      String html = fixture();
      context.route(ORIGIN + "/**", route -> StaticFixtureServer.serve(route, html));
      Page page = context.newPage();
      page.navigate(ORIGIN + "/");
      page.evaluate("() => document.fonts.ready.then(() => true)");
      Locator reset = page.locator("[data-filter-chips-reset]");
      assertThat(page.locator("[data-filter-chips]")).isVisible();
      assertThat(reset).isVisible();
      double resetHeight = heightOf(reset);
      double addHeight = heightOf(page.locator(".filter-add"));
      double menuHeight = heightOf(page.locator(".overflow-menu__item"));
      SoftAssertions.assertSoftly(soft -> {
        soft.assertThat(resetHeight)
            .as("filter reset at %sx%s, touch=%s", width, height, touch)
            .isEqualTo(expectedHeight);
        soft.assertThat(addHeight)
            .as("filter add at %sx%s, touch=%s", width, height, touch)
            .isEqualTo(expectedHeight);
        soft.assertThat(menuHeight)
            .as("overflow action at %sx%s, touch=%s", width, height, touch)
            .isEqualTo(touch ? 44 : 40);
      });

      reset.click();
      assertThat(page.locator("#open-orders")).not().isChecked();
      assertThat(page.locator("[data-filter-chips]")).isHidden();
    }
  }

  private static double heightOf(Locator control) {
    return ((Number) control.evaluate("el => el.getBoundingClientRect().height")).doubleValue();
  }

  private static String fixture() throws IOException {
    String components =
        Files.readString(
            StaticFixtureServer.RESOURCES.resolve("templates/kernel/fragments/components.html"));
    String marker = "<div th:fragment=\"filterChips(formId)\"";
    int start = components.indexOf(marker);
    assertThat(start).as("production filter chip fragment").isNotNegative();
    String fragment =
        components
            .substring(start, components.indexOf("</div>", start) + "</div>".length())
            .replace("th:data-filter-form=\"${formId}\"", "data-filter-form=\"filters\"");
    return """
    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="/css/kernel/styles.css">
    <link rel="stylesheet" href="/css/kernel/filters.css">
    <link rel="stylesheet" href="/css/kernel/overflow.css">
    <script type="module" src="/js/kernel/krt-filter-chips.js"></script>
    </head><body><form id="filters">
    <label><input id="open-orders" type="checkbox" name="status" checked>Open</label>
    """
        + fragment
        + """
        <button type="button" class="filter-add">More filters</button>
        <button type="button" class="overflow-menu__item">Action</button>
        </form></body></html>
        """;
  }
}
