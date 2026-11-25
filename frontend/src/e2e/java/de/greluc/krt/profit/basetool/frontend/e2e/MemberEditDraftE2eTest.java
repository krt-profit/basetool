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
 * The member edit form keeps unsaved edits as a version-aware draft (REQ-FE-041): a reload offers
 * them back, and when a peer saved meanwhile the offer says so and the restored save is refused by
 * the member's version instead of overwriting the peer's change.
 *
 * <p>Runs as {@code test-admin} editing {@code test-member}.
 */
@Tag("e2e")
class MemberEditDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";

  /** Counts the form drafts of the tab. */
  private static final String DRAFTS =
      "Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;
  private static String editUrl;
  private static String memberUserId;

  /** Launches the browser and materialises both accounts in the backend. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      memberUserId = seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD);
      editUrl = STACK.baseUrl() + "/members/" + memberUserId + "/edit?source=members";
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

  /** Brings unsaved edits back after a reload, and the save removes the draft. */
  @Test
  void reloadOffersTheEditsBackAndTheSaveRemovesThem() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String description = "Draft description " + UUID.randomUUID();
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, editUrl);
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isHidden();
        page.locator("#member-description").fill(description);
        awaitDraft(page, description);

        page.reload();
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isVisible();
        assertThat(page.getByTestId("draft-offer-stale")).isHidden();
        page.getByTestId("draft-restore").click();
        assertThat(page.locator("#member-description")).hasValue(description);

        assertEquals(200, save(page).status());
        page.waitForFunction("() => " + DRAFTS + " === 0");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "member-edit-draft");
        throw failure;
      }
    }
  }

  /** Reports a peer's save meanwhile in the offer, and the restored save ends in a conflict. */
  @Test
  void aPeerSaveMeanwhileIsReportedAndTheRestoredSaveIsRefused() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String description = "Editor description " + UUID.randomUUID();
    try (BrowserContext editorContext = newContext();
        BrowserContext peerContext = newContext()) {
      Page editor = editorContext.newPage();
      Page peer = peerContext.newPage();
      editor.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(editor, editUrl);
        editor.waitForLoadState();
        editor.locator("#member-description").fill(description);
        awaitDraft(editor, description);

        E2eSupport.navigate(peer, editUrl);
        peer.waitForLoadState();
        peer.locator("#member-edit-form select[name='rank']").selectOption("12");
        assertEquals(200, save(peer).status());

        editor.reload();
        editor.waitForLoadState();
        assertThat(editor.getByTestId("draft-offer-stale")).isVisible();
        editor.getByTestId("draft-restore").click();
        assertThat(editor.locator("#member-description")).hasValue(description);

        assertEquals(409, save(editor).status());
        assertThat(editor.locator("#member-description")).hasValue(description);
        assertEquals(1, ((Number) editor.evaluate("() => " + DRAFTS)).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(editor, "member-edit-draft-editor");
        E2eSupport.dump(peer, "member-edit-draft-peer");
        throw failure;
      }
    }
  }

  /**
   * Opens a browser context signed in as the admin.
   *
   * @return the context
   */
  private static BrowserContext newContext() {
    Path auth =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ADMIN_USER, ADMIN_PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth));
  }

  /**
   * Waits for the member edit draft that holds the given text.
   *
   * @param page the member edit page
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String text) {
    page.waitForFunction(
        "text => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes('member-edit') && sessionStorage.getItem(k).includes(text))",
        text);
  }

  /**
   * Saves the member edit form and waits for the write's response.
   *
   * @param page the member edit page
   * @return the response of the save
   */
  private static Response save(Page page) {
    page.evaluate(
        "() => { const f = document.querySelector('.krt-footer'); if (f) { f.style.display ="
            + " 'none'; } }");
    return page.waitForResponse(
        response ->
            response.url().contains("/members/" + memberUserId + "/edit")
                && "POST".equals(response.request().method()),
        () -> page.locator("#member-edit-form button[type='submit']").click());
  }
}
