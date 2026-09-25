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
  private static final String KEYBOARD_RAW_MATERIAL =
      E2eStackExtension.PICKER_MATERIAL_KEYBOARD_RAW;

  /** REFINED output the keyboard-pick test expects the form to derive from the input above. */
  private static final String KEYBOARD_REFINED_MATERIAL =
      E2eStackExtension.PICKER_MATERIAL_KEYBOARD_REFINED;

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
      materialId =
          seeder.ensureRefineryMaterial(
              USERNAME, PASSWORD, E2eStackExtension.PICKER_MATERIAL_REFINERY);
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

        Locator ownerCombo = page.locator(".krt-combobox:has(#ownerId) .krt-combobox__input");
        if (ownerCombo.count() > 0) {
          E2eSupport.selectComboboxFirstOption(ownerCombo);
        }
        page.locator("#locationId").selectOption(new SelectOption().setLabel("E2E Refinery Hub"));
        page.locator("#refiningMethodId")
            .selectOption(new SelectOption().setLabel("E2E Refining Method"));
        E2eSupport.selectComboboxByValue(
            page.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"),
            materialId,
            "E2E Refinery Material");
        page.locator("#inputQuantity_0").fill("100");
        page.locator("#outputQuantity_0").fill("100");
        E2eSupport.awaitFormPost(page, () -> page.getByTestId("refinery-submit").click());

        E2eSupport.navigate(page, baseUrl + "/refinery-orders");
        assertThat(page.getByTestId("refinery-order-row").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-create");
        throw failure;
      }
    }
  }

  /**
   * Verifies that committing the sole match of the input-material picker with ENTER prefills the
   * read-only output material, as a mouse pick does.
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
