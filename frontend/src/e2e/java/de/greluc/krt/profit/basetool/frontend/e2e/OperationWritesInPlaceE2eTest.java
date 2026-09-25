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

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow (#576): operation writes save in place. Proves the three conversions: creating an
 * operation from the list modal swaps the list with no full-page reload, editing an operation on
 * the detail page saves in place and a second consecutive save does not 409 (the version
 * writeback), and deleting from the detail page navigates back to the list (the entity is gone).
 *
 * <p>Drive via UI, verify via API ({@link BackendSeeder}). The actor is {@code test-admin}, who can
 * create, edit and delete every operation through the role hierarchy.
 */
@Tag("e2e")
class OperationWritesInPlaceE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership. */
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

  /**
   * Creates an operation through the list modal: the create POST runs as an AJAX twin (the window
   * marker survives, proving no full reload) and the new operation appears in the swapped-in list.
   */
  @Test
  void createOperationUpdatesListInPlace() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String name = "E2E Op " + UUID.randomUUID();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/operations");
        page.waitForLoadState();

        page.locator(
                "button[data-trigger='open-modal-display'][data-modal-id='create-operation-modal']")
            .click();
        page.locator("#create-name").fill(name);
        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            response ->
                response.url().endsWith("/operations/create")
                    && "POST".equals(response.request().method()),
            () -> page.locator("#create-operation-form button[type='submit']").click());

        assertThat(page.locator("#operations-results").getByText(name))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10_000));
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the create must update the list in place — no full-page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "operation-create");
        throw failure;
      }
    }
  }

  /**
   * Edits an operation's name on the detail page and saves twice in a row: the first save persists
   * without a reload, and the second save succeeds without a 409 — proving the twin wrote the fresh
   * optimistic-lock version back into the form.
   */
  @Test
  void updateOperationInPlaceAndDoubleSaveDoesNotConflict() {
    String baseUrl = STACK.baseUrl();
    String operationId = new BackendSeeder().createOperation(USERNAME, PASSWORD, "E2E Edit Op");
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    String firstName = "E2E Renamed " + UUID.randomUUID();
    String secondName = "E2E Re-renamed " + UUID.randomUUID();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/operations/" + operationId + "?tab=verw");
        page.waitForLoadState();

        page.locator("#op-name").fill(firstName);
        page.evaluate("window.__krtNoReload = true;");
        page.waitForResponse(
            response ->
                response.url().endsWith("/operations/" + operationId + "/update")
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='operation-form'][type='submit']").click());
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "the save must update in place — no full-page reload cleared the marker");
        assertEquals(firstName, operationName(operationId), "the first edit must persist");

        page.locator("#op-name").fill(secondName);
        page.waitForResponse(
            response ->
                response.url().endsWith("/operations/" + operationId + "/update")
                    && "POST".equals(response.request().method()),
            () -> page.locator("button[form='operation-form'][type='submit']").click());
        assertEquals(
            secondName,
            operationName(operationId),
            "the second consecutive save must persist (no 409 — the version writeback worked)");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "operation-update");
        throw failure;
      }
    }
  }

  /**
   * Deletes an operation from its detail page: the delete runs as an AJAX twin and, because the
   * operation no longer exists, the client navigates back to the list (navigate-after-AJAX).
   */
  @Test
  void deleteOperationFromDetailNavigatesToList() {
    String baseUrl = STACK.baseUrl();
    String operationId = new BackendSeeder().createOperation(USERNAME, PASSWORD, "E2E Delete Op");
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/operations/" + operationId + "?tab=verw");
        page.waitForLoadState();

        page.locator("[data-trigger='operation-open-delete']").click();
        page.locator("#delete-operation-form button[type='submit']").click();
        page.waitForURL(java.util.regex.Pattern.compile(".*/operations$"));

        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/operations$"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "operation-delete");
        throw failure;
      }
    }
  }

  /**
   * Reads the operation's current name straight from the backend ({@code GET
   * /api/v1/operations/{id}}), so persistence assertions don't race the client's in-place save.
   *
   * @param operationId the operation to read
   * @return the operation's current {@code name}
   */
  private static String operationName(String operationId) {
    String body =
        new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/operations/" + operationId);
    return JsonParser.parseString(body).getAsJsonObject().get("name").getAsString();
  }
}
