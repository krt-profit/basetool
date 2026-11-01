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
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end tests of the quick access's entity search: missions, operations and materials from the
 * backend in one list with the pages, prefixes that narrow the kind, the prefix help, and a
 * highlight that survives late hits (REQ-UI-033).
 */
@Tag("e2e")
class QuickAccessSearchE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /** Canonical IRIDIUM Squadron id, the administrator's home Staffel. */
  private static final String IRIDIUM_SQUADRON_ID = "00000000-0000-0000-0000-000000000001";

  /** A name fragment no other entity of the stack carries. */
  private static final String TOKEN = "Quickfind " + UUID.randomUUID().toString().substring(0, 6);

  private static final String ENTITY_ROWS =
      "#nav-palette .palette-row[data-href^='/missions/'],"
          + " #nav-palette .palette-row[data-href^='/operations/'],"
          + " #nav-palette .palette-row[data-href^='/materials/']";

  private static Playwright playwright;
  private static Browser browser;
  private static Path storageState;
  private static String missionId;
  private static String squadronId;
  private static String squadronName;
  private static String adminUserId;
  private static String adminName;
  private static String jobOrderId;
  private static int jobOrderNumber;

  /** Launches the browser, captures an administrator session and seeds one entity per type. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    storageState =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      missionId = seeder.createMission(USERNAME, PASSWORD, TOKEN + " Mission", false);
      seeder.createOperation(USERNAME, PASSWORD, TOKEN + " Operation");
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, TOKEN + " Material");
      String tag = UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
      squadronName = TOKEN + " Squad";
      squadronId = seeder.createSquadron(USERNAME, PASSWORD, squadronName, "QF" + tag);
      adminUserId = seeder.getUserId(USERNAME, PASSWORD);
      adminName = seeder.getEffectiveName(USERNAME, PASSWORD);
      seeder.assignSecondStaffelleiterSeat(
          USERNAME, PASSWORD, adminUserId, IRIDIUM_SQUADRON_ID, squadronId);
      seeder.setSquadronProfitEligible(USERNAME, PASSWORD, squadronId, true);
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, squadronId, TOKEN + " Order", materialId, 650, 10);
      jobOrderNumber = seeder.getJobOrderNumber(USERNAME, PASSWORD, jobOrderId);
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
   * A term finds the seeded mission, operation and material, and opening the mission keeps its name
   * out of the recently visited pages.
   */
  @Test
  void findsEveryTypeAndKeepsEntitiesOutOfTheRecents() {
    Assumptions.assumeTrue(STACK.managesStack(), "needs the seeded entities");
    run(
        "quick-search-types",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.keyboard().press("Control+k");
          page.getByTestId("palette-input").fill(TOKEN);
          assertThat(page.locator(ENTITY_ROWS)).hasCount(3);

          page.locator("#nav-palette .palette-row[data-href='/missions/" + missionId + "']")
              .click();
          page.waitForURL(Pattern.compile(".*/missions/" + missionId + "$"));
          Object recent = page.evaluate("() => localStorage.getItem('krt.recent') || ''");
          assertFalse(String.valueOf(recent).contains(TOKEN), "an entity name entered krt.recent");
        });
  }

  /** The highlighted row stays highlighted when the entity hits arrive and re-sort the list. */
  @Test
  void lateHitsKeepTheHighlight() {
    Assumptions.assumeTrue(STACK.managesStack(), "needs the seeded entities");
    run(
        "quick-search-highlight",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.keyboard().press("Control+k");
          String create = page.getByTestId("palette-action-create-mission").textContent().trim();
          Locator input = page.getByTestId("palette-input");
          input.fill("Mission");
          int rows = page.locator("#nav-palette .palette-row").count();
          for (int i = 0; i < rows; i++) {
            String label =
                page.locator("#nav-palette .palette-row.is-selected .palette-row-label")
                    .textContent();
            if (create.equals(label.trim())) {
              break;
            }
            input.press("ArrowDown");
          }
          assertThat(page.locator("#nav-palette .palette-row.is-selected .palette-row-label"))
              .hasText(create);

          assertThat(page.locator(ENTITY_ROWS).first()).isVisible();
          assertThat(page.locator("#nav-palette .palette-row.is-selected .palette-row-label"))
              .hasText(create);
          input.press("Enter");
          page.waitForURL(Pattern.compile(".*/missions/new$"));
        });
  }

  /**
   * {@code ?} lists the prefixes, a help row puts its prefix into the input, and a prefix narrows
   * the list to its kind.
   */
  @Test
  void prefixesNarrowTheKind() {
    Assumptions.assumeTrue(STACK.managesStack(), "needs the seeded entities");
    run(
        "quick-search-prefixes",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.keyboard().press("Control+k");
          Locator input = page.getByTestId("palette-input");
          input.fill("?");
          assertThat(page.locator("#nav-palette .palette-row")).hasCount(10);

          String missionsOnly =
              page.locator(".palette-source-prefixes [data-prefix-kind='mission']")
                  .textContent()
                  .trim();
          page.locator("#nav-palette .palette-row .palette-row-label")
              .filter(new Locator.FilterOptions().setHasText(missionsOnly))
              .click();
          assertThat(input).hasValue("mission: ");
          assertThat(input).isFocused();

          input.fill("mission: " + TOKEN);
          assertThat(page.locator("#nav-palette .palette-row[data-href^='/missions/']"))
              .hasCount(1);
          assertThat(page.locator(ENTITY_ROWS)).hasCount(1);

          input.fill("> " + TOKEN);
          assertThat(page.locator(ENTITY_ROWS)).hasCount(0);
        });
  }

  /** A single digit after {@code #} finds the seeded order by its number and opens it. */
  @Test
  void findsAJobOrderByItsNumber() {
    Assumptions.assumeTrue(STACK.managesStack(), "needs the seeded entities");
    run(
        "quick-search-order",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.keyboard().press("Control+k");
          page.getByTestId("palette-input").fill("#" + jobOrderNumber);
          Locator hit =
              page.locator("#nav-palette .palette-row[data-href='/orders/" + jobOrderId + "']");
          assertThat(hit).isVisible();
          assertThat(hit.locator(".palette-row-label")).hasText("#" + jobOrderNumber);
          hit.click();
          page.waitForURL(Pattern.compile(".*/orders/" + jobOrderId + "$"));
        });
  }

  /** An org unit and a member open the org chart with exactly that unit or person highlighted. */
  @Test
  void orgUnitAndMemberHighlightTheChart() {
    Assumptions.assumeTrue(STACK.managesStack(), "needs the seeded entities");
    run(
        "quick-search-org-chart",
        page -> {
          E2eSupport.navigate(page, STACK.baseUrl() + "/inventory");
          page.keyboard().press("Control+k");
          Locator input = page.getByTestId("palette-input");
          input.fill("unit: " + squadronName);
          page.locator(
                  "#nav-palette .palette-row[data-href='/org-chart?highlight=" + squadronId + "']")
              .click();
          page.waitForURL(Pattern.compile(".*/org-chart\\?highlight=" + squadronId + "$"));
          assertThat(page.locator(".oc-unit-box.is-highlighted")).hasCount(1);

          page.keyboard().press("Control+k");
          page.getByTestId("palette-input").fill("@" + adminName);
          page.locator(
                  "#nav-palette .palette-row[data-href='/org-chart?highlight=" + adminUserId + "']")
              .click();
          page.waitForURL(Pattern.compile(".*/org-chart\\?highlight=" + adminUserId + "$"));
          assertThat(page.locator(".oc-node.is-highlighted").first()).isVisible();
        });
  }

  private static void run(String label, Consumer<Page> scenario) {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState)
                .setViewportSize(1440, 900))) {
      Page page = context.newPage();
      try {
        scenario.accept(page);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, label);
        throw failure;
      }
    }
  }
}
