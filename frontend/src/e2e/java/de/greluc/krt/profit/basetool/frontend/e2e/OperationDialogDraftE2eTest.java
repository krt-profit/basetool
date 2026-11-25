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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The operation create dialog keeps a half-filled operation as a tab-scoped draft (REQ-FE-041):
 * after a reload the page offers it, restore reopens the dialog through its opener and fills it,
 * and the save removes it.
 */
@Tag("e2e")
class OperationDialogDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds the test member's IRIDIUM membership. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
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

  /** Brings a half-filled operation back into the reopened dialog, and the save removes it. */
  @Test
  void aReloadOffersTheOperationAndTheSaveRemovesIt() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String name = "E2E Draft Operation " + UUID.randomUUID().toString().substring(0, 8);
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth))) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/operations");
        page.waitForLoadState();
        page.locator(
                "button[data-trigger='open-modal-display'][data-modal-id='create-operation-modal']")
            .click();
        page.locator("#create-name").fill(name);
        page.locator("#create-desc").fill("Draft description");
        page.locator("#create-status").selectOption("ACTIVE");
        page.waitForFunction(
            "text => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
                + " && k.includes('operation-create') && sessionStorage.getItem(k).includes(text))",
            name);
        page.keyboard().press("Escape");

        page.reload();
        page.waitForLoadState();
        page.getByTestId("draft-dialog-offer").getByTestId("draft-dialog-restore").click();
        assertThat(page.locator("#create-operation-modal")).isVisible();
        assertThat(page.locator("#create-name")).hasValue(name);
        assertThat(page.locator("#create-desc")).hasValue("Draft description");
        assertThat(page.locator("#create-status")).hasValue("ACTIVE");

        page.waitForResponse(
            response ->
                response.url().contains("/operations/create")
                    && "POST".equals(response.request().method())
                    && response.status() < 300,
            () -> page.locator("#create-operation-form button[type='submit']").click());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "operation-create-draft");
        throw failure;
      }
    }
  }
}
