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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Regression for the price-overview matrix that stopped rendering after the CSP hardening of
 * ADR-0093 ("eliminate inline {@code style=""} attributes so the CSP can pin {@code style-src-attr
 * 'none'}"). That migration converted the {@code /materials/overview} skeleton's {@code
 * style="display:none"} on {@code #tableContainer} / {@code #matrixError} into the {@code
 * krtm-display-none-5790} class but left {@code materials-matrix.js} unchanged, so its {@code
 * wrapper.style.display = ''} no longer revealed the table (clearing an already-empty inline style
 * leaves the class's {@code display:none} in force) and its virtual-scroll spacer rows kept an
 * inline {@code style="height:.."} attribute that {@code style-src-attr 'none'} now blocks. The
 * page therefore showed only the filter bar — exactly the shipped v1.3.2 defect.
 *
 * <p>The fix toggles the visibility class instead of writing {@code style.display}, and applies the
 * spacer height via the CSSOM ({@code data-krtm-height} → {@code style.height}), which {@code
 * style-src-attr} does not govern. This test drives the real page and asserts the matrix container
 * becomes visible while the loading and error boxes are hidden — the buggy frontend leaves {@code
 * #tableContainer} hidden, so it turns this red.
 *
 * <p>Data-independent: the frontend data endpoint always answers {@code 200} (an empty grid on any
 * backend failure), so the success path — and therefore the un-hiding of {@code #tableContainer} —
 * runs even on an ephemeral stack with no seeded UEX terminals. It additionally captures console
 * output and asserts no {@code style-src-attr} CSP violation was logged; that guards the spacer fix
 * wherever the matrix is large enough to materialize spacer rows (e.g. a populated staging target).
 *
 * <p>Read-only and target-agnostic: it navigates and asserts, mutating nothing, so it is safe
 * against a shared deployment. The actor is {@code test-admin}, who may open the trade pages.
 */
@Tag("e2e")
class MaterialsOverviewMatrixRendersE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser shared across the (single) page check. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
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
   * Loads {@code /materials/overview} and asserts the client-rendered matrix container is revealed
   * (loading and error boxes hidden) and no {@code style-src-attr} CSP violation is logged — the
   * two failure modes the ADR-0093 fix addresses.
   */
  @Test
  void priceOverviewMatrixBecomesVisibleWithoutCspViolation() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      List<String> consoleLog = new CopyOnWriteArrayList<>();
      page.onConsoleMessage((ConsoleMessage message) -> consoleLog.add(message.text()));
      try {
        E2eSupport.navigate(page, baseUrl + "/materials/overview");

        // The core regression: the client un-hides the matrix once the data load resolves. On the
        // pre-fix frontend #tableContainer stays display:none (its krtm-display-none-5790 class was
        // never removed), so this visibility assertion is the discriminator.
        assertThat(page.locator("#tableContainer"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15_000));
        assertThat(page.locator("#matrixLoading")).isHidden();
        assertThat(page.locator("#matrixError")).isHidden();

        // Guards the spacer fix: a blocked inline style attribute is reported to the console. With
        // real data the virtual scroller emits spacer rows, so a reintroduced style="height:.." (or
        // any other blocked inline style on this page) would surface here.
        List<String> cspStyleViolations =
            consoleLog.stream().filter(line -> line.contains("style-src-attr")).toList();
        assertTrue(
            cspStyleViolations.isEmpty(),
            "no style-src-attr CSP violation must be logged on the price overview, but saw: "
                + cspStyleViolations);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "materials-overview-matrix");
        throw failure;
      }
    }
  }
}
