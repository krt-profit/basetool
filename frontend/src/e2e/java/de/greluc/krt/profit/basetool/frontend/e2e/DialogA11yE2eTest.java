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

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Accessibility smoke of the one dialog contract (FE-SIMP-04 / FE-SIMP-04b, REQ-UI-013, ADR-0177):
 * a {@code .krt-modal-overlay} is a native {@code <dialog>} that {@code window.krtModal.open} shows
 * modally, so the page behind it is inert, focus moves into it and cannot land outside it, Escape
 * closes it, focus returns to the control that opened it, and it opens again afterwards.
 *
 * <p>Driven on the hangar's add-ship dialog because it is the plainest one a fresh stack reaches:
 * one trigger button, a form, a close control. Every other dialog shares the same code path — the
 * shared {@code open-modal-display} trigger, a page module, the mission page's aliases all call
 * {@code krtModal.open}, and a dialog shown any other way is upgraded by the contract's observer.
 */
@Tag("e2e")
class DialogA11yE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** The dialog under test and the button that opens it. */
  private static final String DIALOG = "ship-modal";

  private static final String OPENER = "add-ship-btn";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser. */
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
   * Opens the dialog, checks the modal state, the inert background and the focus containment,
   * closes it with Escape, checks focus came back, and opens it again.
   */
  @Test
  void aDialogIsModalClosesOnEscapeReturnsFocusAndReopens() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/hangar");
        page.locator("#" + OPENER).click();

        assertThat(evaluate(page, "d => d.matches(':modal') && d.open"))
            .as("the dialog is shown with showModal(), in the top layer")
            .isEqualTo(true);
        assertThat(evaluate(page, "d => d.contains(document.activeElement)"))
            .as("focus moved into the dialog")
            .isEqualTo(true);
        assertThat(
                page.evaluate(
                    "() => { const b = document.getElementById('"
                        + OPENER
                        + "'); b.focus(); return document.activeElement === b; }"))
            .as("the page behind the dialog is inert: its controls cannot take focus")
            .isEqualTo(false);

        for (int i = 0; i < 12; i++) {
          page.keyboard().press("Tab");
          assertThat(
                  evaluate(
                      page,
                      "d => d.contains(document.activeElement)"
                          + " || document.activeElement === document.body"
                          + " || document.activeElement === null"))
              .as("Tab #%d never lands on a control behind the dialog", i + 1)
              .isEqualTo(true);
        }

        page.keyboard().press("Escape");
        assertThat(evaluate(page, "d => !d.open && getComputedStyle(d).display === 'none'"))
            .as("Escape closed the dialog, both its modal state and its display")
            .isEqualTo(true);
        assertThat(page.evaluate("() => document.activeElement && document.activeElement.id"))
            .as("focus returned to the button that opened the dialog")
            .isEqualTo(OPENER);

        page.locator("#" + OPENER).click();
        assertThat(evaluate(page, "d => d.open && getComputedStyle(d).display !== 'none'"))
            .as("the dialog opens again after an Escape close")
            .isEqualTo(true);
        page.locator("#" + DIALOG + " .krt-modal-close").click();
        assertThat(evaluate(page, "d => !d.open && getComputedStyle(d).display === 'none'"))
            .as("the close control closes it through the same contract")
            .isEqualTo(true);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "dialog-a11y");
        throw failure;
      }
    }
  }

  /**
   * Evaluates a predicate against the dialog under test.
   *
   * @param page the page
   * @param predicate a JavaScript arrow function taking the dialog element
   * @return the predicate's result
   */
  private static Object evaluate(Page page, String predicate) {
    return page.evaluate("(" + predicate + ")(document.getElementById('" + DIALOG + "'))");
  }
}
