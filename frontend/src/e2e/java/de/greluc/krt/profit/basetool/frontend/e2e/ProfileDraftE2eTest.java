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
import com.microsoft.playwright.Locator;
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
 * Each profile section keeps unsaved edits as its own version-aware draft (REQ-FE-041): a reload
 * offers an edited section back and no other, and when a save meanwhile changed the section's
 * version, the offer says so and the restored save is refused instead of overwriting it.
 */
@Tag("e2e")
class ProfileDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and ensures the test user's backend row exists. */
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

  /** Offers only the edited section back after a reload, and the save removes its draft. */
  @Test
  void reloadOffersTheEditedSectionBackAndTheSaveRemovesIt() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String description = "Draft profile description " + UUID.randomUUID();
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/profile");
        page.waitForLoadState();
        page.locator("#description").fill(description);
        awaitDraft(page, "profile-description", description);

        page.reload();
        page.waitForLoadState();
        Locator offer = page.locator("#profile-description-form").getByTestId("draft-offer");
        assertThat(offer).isVisible();
        assertThat(page.locator("[data-testid='draft-offer']:visible")).hasCount(1);
        assertThat(offer.getByTestId("draft-offer-stale")).isHidden();
        offer.getByTestId("draft-restore").click();
        assertThat(page.locator("#description")).hasValue(description);
        assertThat(page.getByTestId("profile-save-bar")).isVisible();

        assertEquals(200, saveDescription(page).status());
        page.waitForFunction("() => " + DRAFTS + " === 0");
        assertThat(page.getByTestId("profile-save-bar")).isHidden();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "profile-draft");
        throw failure;
      }
    }
  }

  /** Reports a save of the shared user row meanwhile, and the restored save ends in a conflict. */
  @Test
  void aSaveMeanwhileIsReportedAndTheRestoredSaveIsRefused() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String description = "Editor profile description " + UUID.randomUUID();
    String handle = "e2e_" + UUID.randomUUID().toString().substring(0, 8);
    try (BrowserContext editorContext = newContext();
        BrowserContext peerContext = newContext()) {
      Page editor = editorContext.newPage();
      Page peer = peerContext.newPage();
      editor.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(editor, STACK.baseUrl() + "/profile");
        editor.waitForLoadState();
        editor.locator("#description").fill(description);
        awaitDraft(editor, "profile-description", description);

        E2eSupport.navigate(peer, STACK.baseUrl() + "/profile");
        peer.waitForLoadState();
        peer.getByTestId("profile-rsi-handle-input").fill(handle);
        hideFooter(peer);
        peer.waitForResponse(
            response ->
                response.url().endsWith("/profile/rsi-handle")
                    && "POST".equals(response.request().method())
                    && response.status() == 200,
            () -> peer.getByTestId("profile-save").click());

        editor.reload();
        editor.waitForLoadState();
        Locator offer = editor.locator("#profile-description-form").getByTestId("draft-offer");
        assertThat(offer.getByTestId("draft-offer-stale")).isVisible();
        offer.getByTestId("draft-restore").click();
        assertThat(editor.locator("#description")).hasValue(description);

        assertEquals(409, saveDescription(editor).status());
        assertThat(editor.locator("#description")).hasValue(description);
        assertEquals(1, ((Number) editor.evaluate("() => " + DRAFTS)).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(editor, "profile-draft-editor");
        E2eSupport.dump(peer, "profile-draft-peer");
        throw failure;
      }
    }
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
   * Waits for the draft of a profile section that holds the given text.
   *
   * @param page the profile page
   * @param formKey the section form's draft key
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String formKey, String text) {
    page.waitForFunction(
        "([key, text]) => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes(key) && sessionStorage.getItem(k).includes(text))",
        new String[] {formKey, text});
  }

  /**
   * Hides the page footer, which can cover the sticky save bar.
   *
   * @param page the profile page
   */
  private static void hideFooter(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
  }

  /**
   * Saves the profile through the save bar and waits for the description section's write.
   *
   * @param page the profile page
   * @return the response of the description save
   */
  private static Response saveDescription(Page page) {
    hideFooter(page);
    return page.waitForResponse(
        response ->
            response.url().endsWith("/profile/description")
                && "POST".equals(response.request().method()),
        () -> page.getByTestId("profile-save").click());
  }
}
