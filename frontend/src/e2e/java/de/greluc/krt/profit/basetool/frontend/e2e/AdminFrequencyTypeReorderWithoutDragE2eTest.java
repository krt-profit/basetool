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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The frequency types on {@code /admin/mission-data} reorder without dragging (REQ-UI-031): the up
 * control and Alt+ArrowDown move a type one place in place, the focus stays on the moved type and
 * the live region announces the move. Runs as {@code test-admin}.
 */
@Tag("e2e")
class AdminFrequencyTypeReorderWithoutDragE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  private static final String INDEX_OF =
      "id => Array.from(document.querySelectorAll('#freqtypes-results tr.draggable-row'))"
          + ".findIndex(r => r.dataset.id === id)";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String firstTypeId;
  private static String secondTypeId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds two frequency types so the list has a
   * row that can move up.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      firstTypeId = createFrequencyType(seeder, "E2E Reorder Freq A", 1000);
      secondTypeId = createFrequencyType(seeder, "E2E Reorder Freq B", 1001);
    }
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
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
   * Moves the lower of the two seeded types up with its button, then back down with Alt+ArrowDown,
   * and asserts the list position, the focus, the announcement and that no reload happened.
   */
  @Test
  void movesAFrequencyTypeUpAndDownWithoutDragging() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/admin/mission-data?kind=freqtypes");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.evaluate("() => { window.__krtNoReload = true; }");

        assertThat(rowOf(page, firstTypeId)).isVisible();
        assertThat(rowOf(page, secondTypeId)).isVisible();
        String movedId =
            indexOf(page, firstTypeId) > indexOf(page, secondTypeId) ? firstTypeId : secondTypeId;
        int before = indexOf(page, movedId);
        assertTrue(before > 0, "the moved type must not already head the list");

        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/mission-data/frequency-types/reorder")
                    && "POST".equals(response.request().method()),
            () -> rowOf(page, movedId).getByTestId("reorder-up").click());
        waitForIndex(page, movedId, before - 1);
        assertFocusOn(page, movedId);
        page.waitForFunction(
            "() => (document.querySelector('[data-reorder-live]')?.textContent || '').length > 0");

        page.waitForResponse(
            response ->
                response.url().endsWith("/admin/mission-data/frequency-types/reorder")
                    && "POST".equals(response.request().method()),
            () -> page.keyboard().press("Alt+ArrowDown"));
        waitForIndex(page, movedId, before);
        assertFocusOn(page, movedId);

        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the reorder must update in place — no page reload");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "admin-freqtype-reorder-without-drag");
        throw failure;
      }
    }
  }

  /**
   * Creates an active frequency type through the backend API.
   *
   * @param seeder the backend seeder
   * @param prefix the readable start of the type's name; a random suffix keeps it unique
   * @param sortIndex the type's initial position in the admin order
   * @return the new type's id
   */
  private static String createFrequencyType(BackendSeeder seeder, String prefix, int sortIndex) {
    String name = prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    String body =
        seeder.postBody(
            USERNAME,
            PASSWORD,
            "/api/v1/frequency-types",
            "{\"name\":\""
                + name
                + "\",\"description\":\"E2E reorder\",\"active\":true,\"sortIndex\":"
                + sortIndex
                + "}");
    return JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();
  }

  /**
   * Locates the list row of one frequency type.
   *
   * @param page the mission-data page
   * @param id the type id
   * @return the row locator
   */
  private static Locator rowOf(Page page, String id) {
    return page.locator("#freqtypes-results tr.draggable-row[data-id='" + id + "']");
  }

  /**
   * Reads the zero-based position of a frequency type in the list.
   *
   * @param page the mission-data page
   * @param id the type id
   * @return its position, or -1 when it is not listed
   */
  private static int indexOf(Page page, String id) {
    return ((Number) page.evaluate(INDEX_OF, id)).intValue();
  }

  /**
   * Waits until the re-rendered list shows a frequency type at the given position.
   *
   * @param page the mission-data page
   * @param id the type id
   * @param index the expected zero-based position
   */
  private static void waitForIndex(Page page, String id, int index) {
    page.waitForFunction(
        "([id, index]) => (" + INDEX_OF + ")(id) === index", new Object[] {id, index});
  }

  /**
   * Asserts that the focus sits on the up control of the given type's row.
   *
   * @param page the mission-data page
   * @param id the type id
   */
  private static void assertFocusOn(Page page, String id) {
    page.waitForFunction(
        "id => { const a = document.activeElement;"
            + " return !!a && a.getAttribute('data-reorder-move') === 'up'"
            + " && a.closest('tr')?.dataset.id === id; }",
        id);
  }
}
