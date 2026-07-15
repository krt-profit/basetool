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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Functional flow for the Bearbeiter (assignee) section of a job order: enroll, attach a note, edit
 * it, delete it, and unenroll — all driven through the order detail page. Since the section moved
 * to AJAX (REQ-ORDERS-013/014), every action re-renders the {@code #assignees-section} fragment in
 * place via {@code outerHTML} swap, so this test proves the whole loop works without a page reload.
 *
 * <p>Each step is gated on the AJAX call (via {@code waitForResponse}) and then verified by reading
 * the persisted state straight from the backend ({@code GET /api/v1/orders/{id}} → {@code
 * assignees[]}), which is deterministic and does not race the client-side fragment swap. The UI is
 * additionally asserted (name visible, note text visible/gone) to prove the fragment rendered.
 *
 * <p>The actor is {@code test-admin}, which reaches {@code LOGISTICIAN} through the role hierarchy;
 * it enrolls and edits its <em>own</em> entry, exercising the self path of the self-or-logistician
 * rule. The enroll/unenroll buttons and the per-row note controls all live on the swapped fragment,
 * so re-locating them each step also proves the delegated handlers survive the swap.
 */
@Tag("e2e")
class JobOrderAssigneeNotesE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static final String NOTE_FIRST = "E2E note: working Friday evening";
  private static final String NOTE_EDITED = "E2E note: taking the refining part";

  private static Playwright playwright;
  private static Browser browser;
  private static String jobOrderId;
  private static String currentUserId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership, a job-order
   * material and a fresh {@code OPEN} order, and resolves the acting user's id (so the assignee
   * read-back can match the enrolled entry).
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId =
          seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Assignee Material");
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Assignee Order", materialId, 650, 100.0);
      currentUserId = seeder.getUserId(USERNAME, PASSWORD);
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
   * Drives the full Bearbeiter loop end to end: enroll self, add a note, edit it, delete it,
   * unenroll — asserting the persisted state after every AJAX step plus the re-rendered fragment.
   */
  @Test
  void enrollAddEditDeleteNoteThenUnenroll() {
    String baseUrl = STACK.baseUrl();
    String detailUrl = baseUrl + "/orders/" + jobOrderId + "?tab=assignees";
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, detailUrl);

        // 1) Enroll self (AJAX POST /orders/{id}/assignees, no reload).
        page.waitForResponse(
            response -> isEnrollCall(response.url()) && "POST".equals(response.request().method()),
            () -> page.locator("[data-trigger='oa-add-me']").click());
        assertThat(page.locator("#assignees-section [data-trigger='oa-edit-note']")).isVisible();
        assertTrue(isEnrolled(), "the acting user is an assignee after self-enroll");

        // 2) Add a note on the own entry (AJAX PUT .../note).
        openNoteModalAndSave(page, NOTE_FIRST);
        assertThat(page.locator("#assignees-section").getByText(NOTE_FIRST)).isVisible();
        assertEquals(NOTE_FIRST, persistedNote(), "note persists after the first save");

        // 3) Edit the note (re-open with the fresh version carried on the swapped button).
        openNoteModalAndSave(page, NOTE_EDITED);
        assertThat(page.locator("#assignees-section").getByText(NOTE_EDITED)).isVisible();
        assertEquals(NOTE_EDITED, persistedNote(), "note persists after the edit");

        // 4) Delete the note (AJAX DELETE .../note); the row stays, the note text + delete icon go.
        page.waitForResponse(
            response ->
                response.url().contains("/note") && "DELETE".equals(response.request().method()),
            () -> page.locator("#assignees-section [data-trigger='oa-delete-note']").click());
        assertThat(page.locator("#assignees-section [data-trigger='oa-delete-note']")).hasCount(0);
        assertTrue(persistedNote() == null, "note is cleared after delete");
        assertTrue(isEnrolled(), "the user stays an assignee after the note is deleted");

        // 5) Unenroll self (AJAX DELETE /orders/{id}/assignees/{userId}, no reload).
        page.waitForResponse(
            response ->
                isEnrollCall(response.url()) && "DELETE".equals(response.request().method()),
            () -> page.locator("#assignees-section [data-trigger='oa-remove-assignee']").click());
        assertThat(page.locator("#assignees-section [data-trigger='oa-edit-note']")).hasCount(0);
        assertTrue(!isEnrolled(), "the user is no longer an assignee after unenroll");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-assignee-notes");
        throw failure;
      }
    }
  }

  /**
   * Opens the note modal from the own entry's pencil button (which carries the current edge
   * version), replaces the text and saves, awaiting the AJAX {@code PUT .../note} round-trip.
   *
   * @param page the driven page
   * @param noteText the note text to store
   */
  private static void openNoteModalAndSave(Page page, String noteText) {
    page.locator("#assignees-section [data-trigger='oa-edit-note']").click();
    Locator textarea = page.locator("#assignee-note-text");
    assertThat(textarea).isVisible();
    textarea.fill(noteText);
    page.waitForResponse(
        response -> response.url().contains("/note") && "PUT".equals(response.request().method()),
        () -> page.locator("[data-trigger='oa-save-note']").click());
  }

  /**
   * Distinguishes the enroll/unenroll calls ({@code .../assignees} and {@code
   * .../assignees/{userId}}) from the note calls ({@code .../assignees/{userId}/note}), which share
   * the {@code /assignees} path prefix.
   *
   * @param url the response URL
   * @return {@code true} when the URL is an enroll/unenroll call, not a note call
   */
  private static boolean isEnrollCall(String url) {
    return url.contains("/assignees") && !url.contains("/note");
  }

  /**
   * @return the order's current assignee array, read straight from the backend.
   */
  private static JsonArray assignees() {
    String body = new BackendSeeder().getBody(USERNAME, PASSWORD, "/api/v1/orders/" + jobOrderId);
    return JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("assignees");
  }

  /**
   * @return {@code true} when the acting user is present in the order's assignee array.
   */
  private static boolean isEnrolled() {
    for (JsonElement element : assignees()) {
      JsonObject user = element.getAsJsonObject().getAsJsonObject("user");
      if (user != null && currentUserId.equals(user.get("id").getAsString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return the acting user's persisted note on this order, or {@code null} when absent/unset.
   */
  private static String persistedNote() {
    for (JsonElement element : assignees()) {
      JsonObject assignee = element.getAsJsonObject();
      JsonObject user = assignee.getAsJsonObject("user");
      if (user != null && currentUserId.equals(user.get("id").getAsString())) {
        JsonElement note = assignee.get("note");
        return (note == null || note.isJsonNull()) ? null : note.getAsString();
      }
    }
    return null;
  }
}
