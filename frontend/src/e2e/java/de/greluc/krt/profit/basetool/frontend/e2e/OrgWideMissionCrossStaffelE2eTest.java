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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Cross-Staffel mission visibility (UC-10): Staffel A owns one organisation-wide mission ({@code
 * is_internal=false}) and one internal mission. A member of Staffel B must see — and be able to
 * open — A's organisation-wide mission (the escape that enables cross-Staffel collaboration) but
 * must NOT see A's internal mission. This is the only cross-Staffel visibility path for missions
 * ({@code searchMissions}: {@code owning_org_unit.id IN (:memberOrgUnitIds) OR is_internal =
 * false}).
 *
 * <p><b>Renamed from {@code PublicMissionCrossStaffelE2eTest} with ADR-0159.</b> "Public" meant two
 * different things in that name and only one of them survived: {@code is_internal = false} still
 * opens a mission to the whole organisation, and it no longer opens it to the internet. The escape
 * itself is untouched, which is exactly why this test is the regression guard the members-only
 * change needed — a gate written one notch too tight would close it, and every member outside the
 * owning Staffel would quietly stop seeing the mission.
 *
 * <p>The join (participant add) is the documented next step; it is not automated here because it
 * depends on seeded job types, but the visibility + cross-Staffel detail access it builds on are
 * fully covered.
 */
@Tag("e2e")
class OrgWideMissionCrossStaffelE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";
  private static final String ORG_WIDE_MISSION = "E2E Org-wide Mission AB";
  private static final String INTERNAL_MISSION = "E2E Internal Mission AB";

  private static Playwright playwright;
  private static Browser browser;
  private static String orgWideMissionId;

  /**
   * Seeds an Officer homed in Staffel A (IRIDIUM) who owns one public and one internal mission, and
   * {@code test-member} homed in a fresh Staffel B (the cross-Staffel viewer).
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(ADMIN_USER, ADMIN_PASSWORD);
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(OFFICER_USER, OFFICER_PASSWORD),
          IRIDIUM_ID,
          false,
          false);
      String staffelBId =
          seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E Mission B", "EMIB");
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
          staffelBId,
          false,
          false);
      // Both missions are owned by Staffel A (test-officer auto-stamps its home Staffel).
      orgWideMissionId =
          seeder.createMission(OFFICER_USER, OFFICER_PASSWORD, ORG_WIDE_MISSION, false);
      seeder.createMission(OFFICER_USER, OFFICER_PASSWORD, INTERNAL_MISSION, true);
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
   * A member of Staffel B sees A's organisation-wide mission in the list and can open its detail
   * page, but does not see A's internal mission at all.
   */
  @Test
  void staffelBMemberSeesOrgWideMissionButNotInternal() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, MEMBER_USER, MEMBER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/missions");
        page.waitForLoadState();

        assertThat(
                page.getByTestId("mission-row")
                    .filter(new Locator.FilterOptions().setHasText(ORG_WIDE_MISSION)))
            .isVisible();
        assertThat(
                page.getByTestId("mission-row")
                    .filter(new Locator.FilterOptions().setHasText(INTERNAL_MISSION)))
            .hasCount(0);

        // B can open A's organisation-wide mission detail cross-Staffel.
        E2eSupport.navigate(page, baseUrl + "/missions/" + orgWideMissionId);
        page.waitForLoadState();
        assertThat(page.getByText(ORG_WIDE_MISSION).first()).isVisible();
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "public-mission-crossstaffel");
        throw failure;
      }
    }
  }
}
