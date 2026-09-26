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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Asserts that abandoning a searchable combobox edit restores both the committed label and the
 * submitted value (REQ-FE-017).
 *
 * <p>Runs on the {@code /inventory/input} material picker as {@code test-admin}, with a freshly
 * seeded material.
 */
@Tag("e2e")
class ComboboxBlurRestoresValueE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Text that must not match any option label, so {@code reconcile()} clears the value. */
  private static final String NON_MATCHING_TEXT = "zzz-kein-treffer-zzz";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds the actor's membership plus one pickable material. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      seeder.createRefineryMaterial(USERNAME, PASSWORD, "E2E Combobox Restore Mat");
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
   * Commits a pick, types non-matching text (which must clear the value), blurs, and asserts value
   * and label are restored together and the control is valid.
   */
  @Test
  void blurAfterNonMatchingTextRestoresValueNotOnlyLabel() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/inventory/input?source=my");
        page.waitForLoadState();

        Locator textbox = page.locator(".krt-combobox:has(#materialId) .krt-combobox__input");
        Locator hidden = page.locator("#materialId");

        E2eSupport.selectComboboxFirstOption(textbox);
        String pickedValue = hidden.inputValue();
        String pickedLabel = textbox.inputValue();
        assertFalse(pickedValue.isBlank(), "picking an option must set the submitted value");
        assertFalse(pickedLabel.isBlank(), "picking an option must show its label");

        textbox.click();
        textbox.fill(NON_MATCHING_TEXT);
        assertEquals(
            "",
            hidden.inputValue(),
            "non-matching text must clear the submitted value (reconcile's guard)");

        page.locator("#amount").click();
        page.waitForFunction(
            "expected => {"
                + " const el = document.getElementById('materialId');"
                + " return el instanceof HTMLInputElement && el.value === expected;"
                + " }",
            pickedValue);

        assertEquals(
            pickedValue,
            hidden.inputValue(),
            "blur must restore the committed value, not just the visible label");
        assertEquals(
            pickedLabel, textbox.inputValue(), "blur must restore the committed label as well");
        assertTrue(
            (Boolean)
                textbox.evaluate("el => el instanceof HTMLInputElement && el.checkValidity()"),
            "the restored control must report valid, so submit is not blocked");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "combobox-blur-restores-value");
        throw failure;
      }
    }
  }
}
