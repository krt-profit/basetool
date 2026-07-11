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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Functional CRUD coverage for the audited "Beförderung" (Promotion) area — the topic-catalogue
 * admin surface at {@code /promotion/admin/topics} — which previously had no end-to-end test at
 * all. It exercises the create / rename / delete lifecycle of a promotion topic through the
 * modal-driven {@code krtFetch} writes (REQ-AUDIT-001 audited mutations, REQ-FE-001/005 in-place
 * fragment swap): each write re-renders the {@code #pa-topics-results} fragment in place and
 * dispatches {@code krt:swapped}, never reloading the page.
 *
 * <p><b>Actor: an IRIDIUM-homed officer, not the admin.</b> The promotion pages are per-squadron
 * and gated on the active squadron's promotion flag; an admin with no squadron pin resolves to
 * all-squadrons mode, where the CRUD affordances and their JS module are not rendered. A non-admin
 * is never in all-squadrons mode, so {@code test-officer} (realm role {@code Officer}, which passes
 * the {@code ADMIN_OR_OFFICER} gate) homed to the IRIDIUM Squadron — whose promotion feature is on
 * by default — renders the full editor with zero squadron-pin plumbing. The topic create also
 * auto-stamps its owning squadron from the caller's single membership, which the homing provides.
 *
 * <p>Tagged {@code @Tag("e2e")} (not {@code smoke}): it mutates promotion data, so it must run only
 * against the ephemeral, disposable stack.
 */
@Tag("e2e")
class PromotionTopicCrudE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  /** Canonical IRIDIUM Squadron id (promotion feature enabled by default). */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;

  /**
   * Launches the browser and, for the ephemeral stack, homes {@code test-officer} to the IRIDIUM
   * Squadron (so the promotion editor renders and topic create can auto-stamp its owning squadron),
   * then captures one authenticated officer session reused across the test.
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      String officerId = seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD);
      seeder.assignStaffelMembership(
          ADMIN_USER, ADMIN_PASSWORD, officerId, IRIDIUM_SQUADRON_ID, false, false);
    }
    storageState =
        E2eSupport.authenticatedStorageState(
            browser, STACK.baseUrl(), OFFICER_USER, OFFICER_PASSWORD);
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
   * Creates a promotion topic through the create modal, renames it through the edit modal, then
   * deletes it through the confirm dialog — asserting the {@code #pa-topics-results} fragment
   * reflects each step and that every write stays in place (no reload, no error toast, no lingering
   * confirm overlay). The topic name is a test-supplied literal asserted directly on the rendered
   * {@code .admin-topic-name}.
   */
  @Test
  void createsRenamesAndDeletesAPromotionTopic() {
    try (BrowserContext context = authedContext()) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, STACK.baseUrl() + "/promotion/admin/topics");
        page.waitForLoadState();
        page.evaluate("() => { window.__krtNoReload = true; }");

        // CREATE
        page.locator("[data-trigger='pa-open-create-topic']").first().click();
        Locator createName = page.locator("#ct-name");
        assertThat(createName).isVisible();
        createName.fill("E2E Promo Topic Alpha");
        awaitTopicsRefresh(page, page.locator("[data-trigger='pa-create-topic']").first()::click);
        assertThat(topicNamed(page, "E2E Promo Topic Alpha")).isVisible();

        // EDIT — rename
        topicCard(page, "E2E Promo Topic Alpha")
            .locator("[data-trigger='pa-edit-topic']")
            .first()
            .click();
        Locator editName = page.locator("#et-name");
        assertThat(editName).isVisible();
        editName.fill("E2E Promo Topic Bravo");
        awaitTopicsRefresh(page, page.locator("[data-trigger='pa-update-topic']").first()::click);
        assertThat(topicNamed(page, "E2E Promo Topic Bravo")).isVisible();
        assertThat(topicNamed(page, "E2E Promo Topic Alpha")).hasCount(0);

        // DELETE
        awaitTopicsRefresh(
            page,
            () -> {
              topicCard(page, "E2E Promo Topic Bravo")
                  .locator("[data-trigger='pa-delete-topic']")
                  .first()
                  .click();
              page.locator(".krt-confirm-overlay .krt-confirm-ok").click();
            });
        assertThat(topicNamed(page, "E2E Promo Topic Bravo")).hasCount(0);

        // No write reloaded the page, and none surfaced an error/conflict.
        assertThat(page.locator(".notification-toast.error-toast")).hasCount(0);
        assertThat(page.locator(".krt-confirm-overlay")).hasCount(0);
        assertEquals(
            Boolean.TRUE,
            page.evaluate("() => window.__krtNoReload === true"),
            "the topic writes must update in place — no page reload cleared the marker");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "promotion-topic-crud");
        throw failure;
      }
    }
  }

  // ---------------------------------------------------------------------- helpers --

  /**
   * Opens a new authenticated browser context with HTTPS errors ignored (the stack uses a
   * self-signed certificate) and the reused officer storage state.
   *
   * @return a fresh, authenticated browser context
   */
  private static BrowserContext authedContext() {
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Runs an action that triggers a promotion-topic write and blocks until the resulting in-place
   * {@code #pa-topics-results} refresh has committed. The topic admin JS re-renders that fragment
   * via {@code krtFetch.swap(?fragment=topicsResults)} and dispatches a {@code krt:swapped} event
   * on {@code document} once the new subtree is in the DOM; a one-shot listener scoped to {@code
   * #pa-topics-results} flips a sentinel on exactly that commit, so waiting for it proves the fresh
   * fragment (with its re-stamped {@code data-pa-version}s) is in place before the caller inspects
   * anything.
   *
   * @param page the page that hosts the topics fragment
   * @param action the action that starts the write (and the in-place refresh it triggers)
   */
  private static void awaitTopicsRefresh(Page page, Runnable action) {
    page.evaluate(
        "() => {"
            + "  window.__paSwapped = false;"
            + "  const results = document.getElementById('pa-topics-results');"
            + "  document.addEventListener('krt:swapped', function onSwap(e) {"
            + "    if (e && e.detail && e.detail.container === results) {"
            + "      window.__paSwapped = true;"
            + "      document.removeEventListener('krt:swapped', onSwap);"
            + "    }"
            + "  });"
            + "}");
    action.run();
    page.waitForFunction(
        "() => window.__paSwapped === true",
        null,
        new Page.WaitForFunctionOptions().setTimeout(30_000));
  }

  /**
   * Locates the {@code .admin-topic-name} element(s) carrying the given topic name.
   *
   * @param page the page showing the topics fragment
   * @param name the topic name to match
   * @return a locator for the matching topic-name element(s)
   */
  private static Locator topicNamed(Page page, String name) {
    return page.locator(".admin-topic-name", new Page.LocatorOptions().setHasText(name));
  }

  /**
   * Locates the topic card ({@code details.admin-topic-card}) whose header carries the given name,
   * so the per-card edit/delete controls can be scoped to it.
   *
   * @param page the page showing the topics fragment
   * @param name the topic name whose card to locate
   * @return a locator for the matching topic card
   */
  private static Locator topicCard(Page page, String name) {
    return page.locator("details.admin-topic-card", new Page.LocatorOptions().setHasText(name));
  }
}
