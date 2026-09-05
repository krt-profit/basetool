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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow: create a Refinery Order through the UI and verify it appears in the order list.
 *
 * <p>Refinery orders are staffel-scoped (need an OrgUnit membership) and the create form selects a
 * refinery-hosting Location + a RefiningMethod (both UEX-owned, provided by the SQL catalog seed in
 * {@link E2eStackExtension}) plus an input Material (a manual RAW material, creatable via the admin
 * API). {@link BackendSeeder} seeds the IRIDIUM membership + the material in {@link #setUp()}. The
 * first goods row is pre-rendered ({@code #inputMaterialId_0} / {@code #outputQuantity_0}); the
 * owner field is only editable for logisticians and otherwise auto-defaults to the caller.
 */
@Tag("e2e")
class RefineryOrderCreateE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Manual RAW input material used by the keyboard-pick test; refines into the material below. */
  private static final String KEYBOARD_RAW_MATERIAL = "E2E Keyboard Pick Raw";

  /** REFINED output the keyboard-pick test expects the form to derive from the input above. */
  private static final String KEYBOARD_REFINED_MATERIAL = "E2E Keyboard Pick Refined";

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;

  /** Launches the browser and, for the ephemeral stack, seeds the membership + input material. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      // Location + refining method come from the SQL catalog seed (E2eStackExtension.seedCatalog);
      // the input material is a manual RAW material, creatable via the admin API. Get-or-create:
      // RefineryImportE2eTest pre-seeds this material before it opens the create page, because
      // that first render freezes the frontend's 10-minute materials-lookup cache for the suite.
      materialId = seeder.ensureRefineryMaterial(USERNAME, PASSWORD, "E2E Refinery Material");
      // A SECOND input material, this one carrying a refined output, for the keyboard-pick test:
      // the material above has no refined counterpart, so its output display stays at "-" whether
      // the metadata mirror works or not.
      seeder.ensureRefineryMaterialWithRefinedOutput(
          USERNAME, PASSWORD, KEYBOARD_RAW_MATERIAL, KEYBOARD_REFINED_MATERIAL);
      // Mirror-seed the sibling class's material in case THIS class runs first (class order is
      // an implementation detail) — same cache rationale, union of all dropdown materials.
      seeder.ensureRefineryMaterial(USERNAME, PASSWORD, "E2E Import Material");
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
   * Fills and submits the refinery-order create form (location, refining method, one input-material
   * good) and asserts a refinery order then appears in the list.
   */
  @Test
  void createsARefineryOrderThroughTheUi() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        page.getByTestId("refinery-create-link").click();
        page.waitForURL(url -> url.contains("/refinery-orders/create"));
        page.waitForLoadState();

        // The owner picker is a searchable combobox for logisticians (enhanced from #ownerId), and
        // a
        // disabled native field otherwise; pick the first user when the editable combobox is
        // present,
        // else it auto-defaults to the caller via a hidden field.
        Locator ownerCombo = page.locator(".krt-combobox:has(#ownerId) .krt-combobox__input");
        if (ownerCombo.count() > 0) {
          E2eSupport.selectComboboxFirstOption(ownerCombo);
        }
        page.locator("#locationId").selectOption(new SelectOption().setLabel("E2E Refinery Hub"));
        page.locator("#refiningMethodId")
            .selectOption(new SelectOption().setLabel("E2E Refining Method"));
        // The input-material picker is a server-side-search combobox (REQ-FE-016); its id stays on
        // the hidden input. The empty-query popup renders only the first 25 catalog rows (name
        // ascending) and the seeded material sorts beyond that window, so the pick must type the
        // material's name first — the real-user narrowing flow.
        E2eSupport.selectComboboxByValue(
            page.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"),
            materialId,
            "E2E Refinery Material");
        // Both the input and the expected output quantity of the goods row are required.
        page.locator("#inputQuantity_0").fill("100");
        page.locator("#outputQuantity_0").fill("100");
        // Wait for the full post-submit redirect to settle before navigating, else WebKit aborts
        // the in-flight redirect GET (HTTP/2 INTERNAL_ERROR) — see E2eSupport#awaitFormPost.
        E2eSupport.awaitFormPost(page, () -> page.getByTestId("refinery-submit").click());

        // The created order must appear in the list (fresh ephemeral DB => exactly one). Route the
        // post-submit GET through the retry helper: WebKit can still abort it (HTTP/2
        // INTERNAL_ERROR) even after the redirect settled — see E2eSupport#navigate.
        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        // 20 s, not the 5 s default: the post-submit list render is slow on WebKit under CI load.
        assertThat(page.getByTestId("refinery-order-row").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-create");
        throw failure;
      }
    }
  }

  /**
   * Regression: narrowing the input-material picker by typing and committing the sole match with
   * ENTER — without clicking the dropdown row — must prefill the read-only output material, exactly
   * as a mouse pick does.
   *
   * <p>The combobox's rendered-row list used to carry only each option's value and label, so the
   * keyboard commit paths mirrored an option with no metadata onto the hidden input and {@code
   * updateOutputMaterial} read an empty {@code data-refined-name}, leaving the output display at
   * its "-" placeholder while the input showed a correctly picked material.
   */
  @Test
  void keyboardPickPrefillsTheOutputMaterial() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create");
        page.waitForLoadState();

        Locator combo = page.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input");
        combo.click();
        combo.fill(KEYBOARD_RAW_MATERIAL);
        // Wait for the debounced remote fetch to render the sole match before committing it, so
        // Enter lands on a populated list rather than on the transient "loading" row.
        assertThat(combo.locator("xpath=..").locator("li[role='option']:not([data-value=''])"))
            .hasCount(1);
        combo.press("Enter");

        assertThat(combo).hasValue(KEYBOARD_RAW_MATERIAL);
        assertThat(page.locator("#outputMaterialDisplay_0")).hasText(KEYBOARD_REFINED_MATERIAL);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-create-keyboard-pick");
        throw failure;
      }
    }
  }
}
