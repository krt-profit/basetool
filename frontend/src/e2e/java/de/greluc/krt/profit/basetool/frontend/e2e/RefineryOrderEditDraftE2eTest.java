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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The refinery-order detail form keeps unsaved edits as a version-aware draft (REQ-FE-041): a
 * reload offers them back, and when a peer saved meanwhile the offer says so and the restored save
 * is refused by the optimistic lock instead of overwriting the peer's change.
 */
@Tag("e2e")
class RefineryOrderEditDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** The catalog-seeded refinery-hosting location the orders run at. */
  private static final String REFINERY_HUB = "E2E Refinery Hub";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

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

  /** Brings unsaved edits back after a reload, and the save removes the draft. */
  @Test
  void reloadOffersTheEditsBackAndTheSaveRemovesThem() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String orderUrl = STACK.baseUrl() + "/refinery-orders/" + seedOrder();
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, orderUrl);
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isHidden();
        editOreSales(page, "777");

        page.reload();
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isVisible();
        assertThat(page.getByTestId("draft-offer-stale")).isHidden();
        page.getByTestId("draft-restore").click();
        assertThat(page.getByTestId("draft-offer")).isHidden();
        assertEquals("777", canonicalOreSales(page));

        assertEquals(200, save(page).status());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "refinery-order-edit-draft");
        throw failure;
      }
    }
  }

  /** Reports a peer's save meanwhile in the offer, and the restored save ends in a conflict. */
  @Test
  void aPeerSaveMeanwhileIsReportedAndTheRestoredSaveIsRefused() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String orderUrl = STACK.baseUrl() + "/refinery-orders/" + seedOrder();
    try (BrowserContext editorContext = newContext();
        BrowserContext peerContext = newContext()) {
      Page editor = editorContext.newPage();
      Page peer = peerContext.newPage();
      editor.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(editor, orderUrl);
        editor.waitForLoadState();
        editOreSales(editor, "888");

        E2eSupport.navigate(peer, orderUrl);
        peer.waitForLoadState();
        E2eSupport.selectComboboxFirstOption(
            peer.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"));
        peer.locator("#oreSales").fill("555");
        assertEquals(200, save(peer).status());

        editor.reload();
        editor.waitForLoadState();
        assertThat(editor.getByTestId("draft-offer")).isVisible();
        assertThat(editor.getByTestId("draft-offer-stale")).isVisible();
        editor.getByTestId("draft-restore").click();
        assertEquals("888", canonicalOreSales(editor));

        assertEquals(409, save(editor).status());
        assertEquals("888", canonicalOreSales(editor));
        assertEquals(1, ((Number) editor.evaluate("() => " + DRAFTS)).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(editor, "refinery-order-edit-draft-editor");
        E2eSupport.dump(peer, "refinery-order-edit-draft-peer");
        throw failure;
      }
    }
  }

  /**
   * Seeds an OPEN refinery order of the test member.
   *
   * @return the order id
   */
  private static String seedOrder() {
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String hub = seeder.findLocationIdByName(USERNAME, PASSWORD, REFINERY_HUB);
    String material =
        seeder.createRefineryMaterial(
            USERNAME, PASSWORD, "E2E draft material " + UUID.randomUUID());
    return seeder.createRefineryOrder(USERNAME, PASSWORD, hub, material, null, null);
  }

  /**
   * Opens a signed-in browser context.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth));
  }

  /**
   * Picks the input material and types an ore-sales value, then waits for the draft that holds it.
   *
   * @param page the detail page
   * @param oreSales the ore-sales value to type
   */
  private static void editOreSales(Page page, String oreSales) {
    E2eSupport.selectComboboxFirstOption(
        page.locator(".krt-combobox:has(#inputMaterialId_0) .krt-combobox__input"));
    page.locator("#oreSales").fill(oreSales);
    page.waitForFunction(
        "value => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes('refinery-order-edit')"
            + " && sessionStorage.getItem(k).includes('\"oreSales\":\"' + value + '\"'))",
        oreSales);
  }

  /**
   * Reads the ore-sales value the form submits, which the number field shows formatted.
   *
   * @param page the detail page
   * @return the canonical value
   */
  private static String canonicalOreSales(Page page) {
    return (String) page.evaluate("() => document.getElementById('oreSales').value");
  }

  /**
   * Saves the order form and waits for the write's response.
   *
   * @param page the detail page
   * @return the response of the save
   */
  private static Response save(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    return page.waitForResponse(
        response ->
            response.url().contains("/refinery-orders/")
                && "POST".equals(response.request().method()),
        () -> page.locator("button[form='refineryOrderMainForm']").click());
  }
}
