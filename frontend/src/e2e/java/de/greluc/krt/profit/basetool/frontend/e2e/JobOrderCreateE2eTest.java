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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow: create a Job Order through the UI and verify it appears in the order list.
 *
 * <p>Job Orders are cross-staffel (no OrgUnit-scope filter). The create form carries two org-unit
 * pickers: the responsible (processing) unit — restricted to profit-eligible org units — and the
 * requesting (customer) unit. IRIDIUM is opted into profit-eligibility once at stack bootstrap
 * ({@link E2eStackExtension}) so it offers in the responsible picker; {@link BackendSeeder} seeds
 * the IRIDIUM membership and one {@code isJobOrder=true} material in {@link #setUp()}. Uses the
 * {@code order-material-*} / {@code order-submit} hooks on the create form and {@code order-row} on
 * the index, with the reused authenticated session from {@link
 * E2eSupport#authenticatedStorageState}.
 */
@Tag("e2e")
class JobOrderCreateE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the membership and guarantees at least
   * one {@code isJobOrder} material exists for the create-form dropdown. The specific id is not
   * retained: the frontend caches the job-order material list ({@code getCached}), so in the shared
   * stack the dropdown may show a material seeded by another test rather than this one — the test
   * therefore selects whatever the dropdown offers.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Job Material");
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
   * Fills and submits the job-order create form (responsible org-unit, requesting org-unit, contact
   * handle, one material row) and asserts an order then appears in the order list.
   */
  @Test
  void createsAJobOrderThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        // Responsible (processing) unit — restricted to profit-eligible org units; IRIDIUM is opted
        // in at stack bootstrap. Required, so it must be selected for the create to pass.
        page.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#requestingOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#handle").fill("E2E Contact");
        // Select whatever material the (frontend-cached) searchable combobox offers — see setUp().
        E2eSupport.selectComboboxFirstOption(page.getByTestId("order-material-select"));
        page.getByTestId("order-material-amount").fill("100");
        // Wait for the full post-submit redirect to settle before navigating, else WebKit aborts
        // the in-flight redirect GET (HTTP/2 INTERNAL_ERROR) — see E2eSupport#awaitFormPost.
        E2eSupport.awaitFormPost(page, () -> page.getByTestId("order-submit").click());

        // The created order must appear in the list (fresh ephemeral DB => exactly one). The
        // post-submit GET goes through the retry helper — WebKit can abort it (HTTP/2
        // INTERNAL_ERROR) even after the redirect settled. See E2eSupport#navigate.
        E2eSupport.navigate(page, baseUrl + "/orders");
        // 20 s, not the 5 s default: the post-submit list render is slow on WebKit under CI load.
        assertThat(page.getByTestId("order-row").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-create");
        throw failure;
      }
    }
  }
}
