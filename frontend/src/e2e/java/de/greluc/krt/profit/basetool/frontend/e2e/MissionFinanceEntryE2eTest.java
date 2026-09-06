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
import com.microsoft.playwright.Response;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional flow: add a finance entry to a mission through the UI, then reopen the mission-detail
 * page and assert it still renders.
 *
 * <p>Regression coverage for the production 500 where {@code GET /missions/{id}} threw a Thymeleaf
 * {@code TemplateProcessingException} as soon as the mission owned at least one finance entry: the
 * finance loop rendered the edit button's amount with the {@code @moneyFormat} bean inside the
 * restricted {@code th:data-amount} attribute. The fix binds the rounded value via {@code th:with}
 * (see PR #509 and the unit regression in {@code MissionPageControllerMvcTest}). Every prior render
 * test passed an empty finance list, so the loop body never executed and no e2e exercised a
 * populated finance loop — which is why the bug reached production.
 *
 * <p>The mission and a guest participant (the finance modal's participant dropdown is {@code
 * required}) are seeded via {@link BackendSeeder}; the test then drives only the finance-create
 * flow through the UI and reloads the detail page, reusing one authenticated session via {@link
 * E2eSupport#authenticatedStorageState}. A mission is staffel-scoped, so the user is assigned to
 * the IRIDIUM Squadron first.
 */
@Tag("e2e")
class MissionFinanceEntryE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * Distinctive whole-aUEC income amount. The modal's amount input is whole-number-only ({@code
   * step="1"} plus the {@code @WholeNumber} constraint), so the server-side HALF_UP rounding is a
   * no-op here and the rendered {@code data-amount} equals this exact value (fractional rounding is
   * covered by the unit test).
   */
  private static final String FINANCE_AMOUNT = "150000";

  /**
   * Guest participant name that matches no realm test user, so the public add-participant call
   * stays on the guest path instead of being resolved to a registered member.
   */
  private static final String GUEST_PARTICIPANT_NAME = "E2E Finance Participant";

  private static Playwright playwright;
  private static Browser browser;
  private static String missionId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the user's IRIDIUM membership, a
   * mission owned by that squadron, and one guest participant for the finance modal's required
   * dropdown.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, "E2E Finance Mission", true);
      seeder.addExternalParticipant(USERNAME, PASSWORD, missionId, GUEST_PARTICIPANT_NAME);
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
   * Adds an income finance entry to the seeded mission via the "Neuer Eintrag" modal, then reopens
   * the mission-detail page and asserts it renders HTTP 200 (not the pre-fix 500) with the finance
   * edit button present and carrying the rounded {@code data-amount}.
   */
  @Test
  void addsIncomeFinanceEntryThenReopensDetailWithoutTemplateError() {
    String baseUrl = STACK.baseUrl();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        // Open the seeded mission's detail page on the finance tab (?tab=fin deeplink —
        // the "Neuer Eintrag" button lives inside the Finanzen tab pane) and the modal.
        E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=fin");
        page.waitForLoadState();
        page.locator("button[data-trigger='open-modal-display'][data-modal-id='finance-modal']")
            .click();

        // Fill an income entry. The participant picker is a searchable combobox; its empty-value
        // placeholder is not a list option, so the first option is the first real (seeded guest)
        // participant — the equivalent of the former selectOption().setIndex(1). The type is a
        // segment control mirroring into the hidden type input; INCOME is the default, the explicit
        // click guards against a changed default.
        Locator modal = page.locator("#finance-modal");
        E2eSupport.selectComboboxFirstOption(modal.locator(".krt-combobox__input"));
        modal.locator(".seg button[data-type-value='INCOME']").click();
        modal.locator("input[name='amount']").fill(FINANCE_AMOUNT);

        // Submit in place (#574): the finance add now swaps the Finanzen pane via AJAX — there is
        // no
        // Post/Redirect/Get navigation to await. Mark the window so we can prove no full reload
        // happened, click submit, then web-first-wait for the new entry's edit button to appear in
        // the re-rendered pane (which also proves the entry was persisted).
        page.evaluate("window.__krtNoReload = true;");
        modal.locator("button[type='submit']").click();
        assertThat(
                page.locator(
                    "#pane-fin button.edit-finance-btn[data-amount='" + FINANCE_AMOUNT + "']"))
            .isVisible();
        assertEquals(
            Boolean.TRUE,
            page.evaluate("window.__krtNoReload === true"),
            "finance add must swap in place — no full-page reload cleared the window marker");

        // Reopen the detail page. Before the th:with fix this 500'd: the populated finance loop
        // called @moneyFormat inside the restricted th:data-amount attribute and threw a
        // TemplateProcessingException. It must now render 200 with the finance edit button
        // carrying the rounded amount as a plain data-amount value.
        Response reopened =
            E2eSupport.navigate(page, baseUrl + "/missions/" + missionId + "?tab=fin");
        assertEquals(
            200,
            reopened.status(),
            "GET /missions/{id} must render 200 (no 500) once the mission owns a finance entry");
        assertThat(
                page.locator(
                    "#pane-fin button.edit-finance-btn[data-amount='" + FINANCE_AMOUNT + "']"))
            .isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "mission-finance-entry");
        throw failure;
      }
    }
  }
}
