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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The mission edit form keeps unsaved edits as a version-aware draft (REQ-FE-041): a reload shows
 * the edit pane with the offer, a peer's save to another section leaves both changes in place, and
 * a peer's save to the same section refuses the restored save instead of overwriting it.
 */
@Tag("e2e")
class MissionEditDraftE2eTest {

  /** Provisions the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

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

  /** Shows the edit pane with the offer after a reload, and the save removes the draft. */
  @Test
  void reloadShowsTheEditPaneWithTheOfferAndTheSaveRemovesIt() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String missionId = seedMission(false);
    String missionUrl = STACK.baseUrl() + "/missions/" + missionId;
    try (BrowserContext context = newContext()) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, missionUrl + "?tab=verw");
        page.waitForLoadState();
        assertThat(page.getByTestId("draft-offer")).isHidden();
        page.locator("#mission-meeting-point").fill("Draft meeting point");
        awaitDraft(page, "Draft meeting point");
        page.evaluate("() => localStorage.setItem(`krt.einsatz.${window.missionId}.tab`, 'ueb')");

        E2eSupport.navigate(page, missionUrl);
        page.waitForLoadState();
        assertThat(page.locator("#pane-verw")).hasClass(Pattern.compile("\\bon\\b"));
        assertThat(page.getByTestId("draft-offer")).isVisible();
        assertThat(page.getByTestId("draft-offer-stale")).isHidden();
        page.getByTestId("draft-restore").click();
        assertThat(page.locator("#mission-meeting-point")).hasValue("Draft meeting point");

        assertEquals(200, save(page, missionId).status());
        page.waitForFunction("() => " + DRAFTS + " === 0");
        assertEquals("Draft meeting point", mission(missionId).get("meetingPoint").getAsString());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-edit-draft");
        throw failure;
      }
    }
  }

  /** Keeps a peer's change to another section when the restored edits are saved. */
  @Test
  void aPeerSaveToAnotherSectionKeepsBothChanges() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String missionId = seedMission(true);
    String editUrl = STACK.baseUrl() + "/missions/" + missionId + "?tab=verw";
    try (BrowserContext editorContext = newContext();
        BrowserContext peerContext = newContext()) {
      Page editor = editorContext.newPage();
      Page peer = peerContext.newPage();
      editor.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(editor, editUrl);
        editor.waitForLoadState();
        editor.locator("#isInternal").uncheck();
        awaitDraft(editor, "\"flags\":true");

        E2eSupport.navigate(peer, editUrl);
        peer.waitForLoadState();
        peer.locator("#mission-meeting-point").fill("Peer meeting point");
        assertEquals(200, save(peer, missionId).status());

        editor.reload();
        editor.waitForLoadState();
        assertThat(editor.getByTestId("draft-offer-stale")).isVisible();
        editor.getByTestId("draft-restore").click();
        assertThat(editor.locator("#isInternal")).not().isChecked();

        assertEquals(200, save(editor, missionId).status());
        JsonObject saved = mission(missionId);
        assertEquals("Peer meeting point", saved.get("meetingPoint").getAsString());
        assertEquals(false, saved.get("isInternal").getAsBoolean());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(editor, "mission-edit-draft-editor");
        E2eSupport.dump(peer, "mission-edit-draft-peer");
        throw failure;
      }
    }
  }

  /** Refuses the restored save when a peer saved the same section meanwhile. */
  @Test
  void aPeerSaveToTheSameSectionRefusesTheRestoredSave() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    String missionId = seedMission(false);
    String editUrl = STACK.baseUrl() + "/missions/" + missionId + "?tab=verw";
    String peerName = "E2E peer name " + UUID.randomUUID();
    try (BrowserContext editorContext = newContext();
        BrowserContext peerContext = newContext()) {
      Page editor = editorContext.newPage();
      Page peer = peerContext.newPage();
      editor.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(editor, editUrl);
        editor.waitForLoadState();
        editor.locator("#mission-meeting-point").fill("Editor meeting point");
        awaitDraft(editor, "Editor meeting point");

        E2eSupport.navigate(peer, editUrl);
        peer.waitForLoadState();
        peer.getByTestId("mission-name-input").fill(peerName);
        assertEquals(200, save(peer, missionId).status());

        editor.reload();
        editor.waitForLoadState();
        assertThat(editor.getByTestId("draft-offer-stale")).isVisible();
        editor.getByTestId("draft-restore").click();

        assertEquals(409, save(editor, missionId).status());
        assertThat(editor.locator("#mission-meeting-point")).hasValue("Editor meeting point");
        JsonObject saved = mission(missionId);
        assertEquals(peerName, saved.get("name").getAsString());
        assertNotEquals("Editor meeting point", textOf(saved, "meetingPoint"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(editor, "mission-edit-draft-conflict-editor");
        E2eSupport.dump(peer, "mission-edit-draft-conflict-peer");
        throw failure;
      }
    }
  }

  /**
   * Seeds a mission of the test member's unit.
   *
   * @param internal whether the mission is internal
   * @return the mission id
   */
  private static String seedMission(boolean internal) {
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    return seeder.createMission(
        USERNAME, PASSWORD, "E2E draft mission " + UUID.randomUUID(), internal);
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
   * Waits for the mission edit draft that holds the given text.
   *
   * @param page the mission page
   * @param text a text the stored draft must contain
   */
  private static void awaitDraft(Page page, String text) {
    page.waitForFunction(
        "text => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
            + " && k.includes('mission-edit') && sessionStorage.getItem(k).includes(text))",
        text);
  }

  /**
   * Saves the mission form and waits for the write's response.
   *
   * @param page the mission page
   * @param missionId the mission id
   * @return the response of the save
   */
  private static Response save(Page page, String missionId) {
    return page.waitForResponse(
        response ->
            response.url().endsWith("/missions/" + missionId)
                && "POST".equals(response.request().method()),
        () -> page.locator("button[form='mission-form'][type='submit']").click());
  }

  /**
   * Reads a text property of a JSON object.
   *
   * @param object the object
   * @param name the property
   * @return its text; null when it is absent or null
   */
  private static String textOf(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsString()
        : null;
  }

  /**
   * Reads a mission from the backend.
   *
   * @param missionId the mission id
   * @return the mission
   */
  private static JsonObject mission(String missionId) {
    return JsonParser.parseString(
            new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/missions/" + missionId))
        .getAsJsonObject();
  }
}
