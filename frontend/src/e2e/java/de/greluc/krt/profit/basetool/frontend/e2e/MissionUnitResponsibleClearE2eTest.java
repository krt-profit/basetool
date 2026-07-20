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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow: a mission unit's explicit responsible person can be REMOVED again through the
 * unit-edit modal — via BOTH supported paths — resetting the field to "none".
 *
 * <p>Regression coverage for the reported bug where, once a responsible person was pinned on a
 * unit, the searchable combobox offered no way back to empty: it swallowed the optional {@code
 * <select>}'s empty option into the placeholder (so "— automatisch: Schiffseigner —" was never a
 * pickable row) and, on blur, snapped a cleared textbox back to the just-removed name. The fix
 * (REQ-FE-011, ADR-0053) exposes two independent paths back to none, one per test method:
 *
 * <ul>
 *   <li>the discoverable dropdown <b>"clear" row</b> ({@link
 *       #clearsUnitResponsibleViaTheClearRow});
 *   <li><b>delete-to-clear</b> — emptying the textbox, whose visual regression was that blur
 *       restored the removed name ({@link #clearsUnitResponsibleByEmptyingTheTextbox}).
 * </ul>
 *
 * <p>The mission, the acting user as a registered participant (a unit's responsible person is
 * chosen from the registered participants), and one unit per path — each pinned to that participant
 * — are seeded via {@link BackendSeeder}; the tests then drive only the clear-and-save flow through
 * the UI, reusing one authenticated session via {@link E2eSupport#authenticatedStorageState}. A
 * mission is staffel-scoped, so the user is assigned to the IRIDIUM Squadron first. The two tests
 * target DISTINCT units (located by {@code data-name}) so they stay independent of ordering.
 */
@Tag("e2e")
class MissionUnitResponsibleClearE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Unit cleared through the dropdown "clear" row. */
  private static final String CLEAR_ROW_UNIT = "E2E Clear Row Unit";

  /** Unit cleared by emptying the combobox textbox (delete-to-clear). */
  private static final String DELETE_UNIT = "E2E Delete Unit";

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;
  private static String responsibleUserId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership, a
   * mission owned by that squadron, the user as a registered participant, and one unit per clear
   * path — each with that participant pinned as its responsible person.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      responsibleUserId = seeder.getUserId(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Unit Responsible Mission", true);
      seeder.addRegisteredParticipant(USERNAME, PASSWORD, missionId, responsibleUserId);
      seeder.addUnitWithResponsible(
          USERNAME, PASSWORD, missionId, CLEAR_ROW_UNIT, responsibleUserId);
      seeder.addUnitWithResponsible(USERNAME, PASSWORD, missionId, DELETE_UNIT, responsibleUserId);
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
   * Clears the responsible person of the {@link #CLEAR_ROW_UNIT} via the combobox's selectable
   * "clear" row (the discoverable path), then saves and asserts the unit no longer has a
   * responsible person.
   */
  @Test
  void clearsUnitResponsibleViaTheClearRow() {
    withUnitEditModalOpen(
        CLEAR_ROW_UNIT,
        "mission-unit-responsible-clear-row",
        (page, combo) -> {
          // Discoverable path: pick the combobox "clear" row.
          E2eSupport.clearCombobox(combo);
          assertThat(combo).hasValue("");
        });
  }

  /**
   * Clears the responsible person of the {@link #DELETE_UNIT} by emptying the combobox textbox and
   * moving focus away — the path a user takes when they miss the dropdown row. Asserts that blur
   * does NOT snap the box back to the removed name (the reported visual regression), then saves and
   * asserts the unit no longer has a responsible person.
   */
  @Test
  void clearsUnitResponsibleByEmptyingTheTextbox() {
    withUnitEditModalOpen(
        DELETE_UNIT,
        "mission-unit-responsible-clear-delete",
        (page, combo) -> {
          // Delete-to-clear: empty the textbox, then Tab away so the combobox blurs. Its blur
          // handler
          // runs on a 150 ms debounce and, before the fix, restored the just-removed name — so wait
          // past that window before asserting the box stays empty.
          combo.fill("");
          combo.press("Tab");
          page.waitForTimeout(400);
          assertThat(combo).hasValue("");
        });
  }

  /**
   * Opens the seeded mission on the crew tab, opens the given unit's edit modal (its responsible
   * person pre-selected, so the combobox textbox is non-empty), runs the caller's clear action,
   * then saves in place and asserts the unit's responsible id went empty without a full-page
   * reload.
   *
   * @param unitName the {@code data-name} of the unit to edit (locates its edit button)
   * @param dumpLabel artifact label used if the flow fails
   * @param clearAction the path-specific clear step, given the page and the responsible combobox
   *     textbox
   */
  private void withUnitEditModalOpen(String unitName, String dumpLabel, ClearAction clearAction) {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        // Open the seeded mission on the crew tab (?tab=crew deeplink — the unit boxes and their
        // edit
        // buttons live in the "Teilnehmer & Einheiten" pane).
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=crew");
        page.waitForLoadState();

        // The unit's edit button (located by data-name) carries the seeded responsible id; open it.
        Locator editBtn = page.locator(".edit-unit-btn[data-name='" + unitName + "']");
        assertThat(editBtn).hasAttribute("data-responsible", responsibleUserId);
        editBtn.click();

        // The modal's responsible picker is a searchable combobox; opening the modal pre-selects
        // the
        // seeded participant, so the textbox shows their (non-empty) name.
        Locator responsibleCombo = page.locator("#edit-unit-modal .krt-combobox__input");
        assertThat(responsibleCombo).hasValue(Pattern.compile(".+"));

        // Path-specific clear step (dropdown row vs delete-to-clear).
        clearAction.run(page, responsibleCombo);

        // Save in place (the unit edit swaps the crew pane via AJAX — no Post/Redirect/Get). Mark
        // the
        // window so we can prove no full reload happened, then web-first-wait for the re-rendered
        // edit
        // button to carry an empty responsible id (which also proves the clear was persisted).
        page.evaluate("window.__krtNoReload = true;");
        page.locator("#edit-unit-form button[type='submit']").click();
        assertThat(page.locator(".edit-unit-btn[data-name='" + unitName + "']"))
            .hasAttribute("data-responsible", "");
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "unit edit must swap in place — no full-page reload cleared the window marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, dumpLabel);
        throw failure;
      }
    }
  }

  /**
   * The path-specific step that clears the responsible combobox inside the open unit-edit modal.
   */
  @FunctionalInterface
  private interface ClearAction {
    /**
     * Clears the responsible combobox by the path under test.
     *
     * @param page the active page
     * @param responsibleCombo the responsible combobox textbox the enhancer rendered
     */
    void run(Page page, Locator responsibleCombo);
  }
}
