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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Cross-Staffel job-order flow (UC-08): inventory Staffel B links to Staffel A's order appears in
 * A's order context but not in A's org-scoped Lager view.
 */
@Tag("e2e")
class CrossStaffelJobOrderE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String ADMIN_USER = System.getProperty("e2e.username", "test-admin");
  private static final String ADMIN_PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String OFFICER_USER = "test-officer";
  private static final String OFFICER_PASSWORD = "test-officer-pw";
  private static final String MEMBER_USER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  private static Playwright playwright;
  private static Browser browser;
  private static String materialId;
  private static String jobOrderId;
  private static String bInventoryItemId;

  /**
   * Seeds the cross-Staffel precondition: an Officer homed in Staffel A (IRIDIUM), a fresh Staffel
   * B with {@code test-member} homed in it, a job-order material, a job order owned by A, and a
   * B-owned inventory item linked to that order.
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
          seeder.createSquadron(ADMIN_USER, ADMIN_PASSWORD, "E2E JobOrder B", "EJOB");
      seeder.assignStaffelMembership(
          ADMIN_USER,
          ADMIN_PASSWORD,
          seeder.getUserId(MEMBER_USER, MEMBER_PASSWORD),
          staffelBId,
          false,
          false);
      materialId =
          seeder.ensureJobOrderMaterial(ADMIN_USER, ADMIN_PASSWORD, "E2E CrossStaffel Mat");
      String bLocationId =
          seeder.createLocation(ADMIN_USER, ADMIN_PASSWORD, "E2E CrossStaffel Loc");
      jobOrderId =
          seeder.createJobOrder(
              ADMIN_USER,
              ADMIN_PASSWORD,
              IRIDIUM_ID,
              "E2E CrossStaffel Order",
              materialId,
              650,
              80);
      bInventoryItemId =
          seeder.createInventoryItemForJobOrder(
              MEMBER_USER, MEMBER_PASSWORD, materialId, bLocationId, jobOrderId, 750, 60);
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
   * Asserts the cross-Staffel isolation: B's linked item is selectable in A's order handover
   * dropdown (order context), is visible to B in B's own Lager-View, but is absent from A's
   * Lager-View.
   */
  @Test
  void foreignStaffelItemSurfacesInOrderButNotInAStaffelLager() {
    String baseUrl = STACK.baseUrl();

    try (BrowserContext context =
        browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
      Page page = context.newPage();
      try {
        E2eSupport.login(page, baseUrl, OFFICER_USER, OFFICER_PASSWORD);
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId + "?tab=handovers");
        page.waitForResponse(
            response ->
                response.url().contains("/materials/") && response.url().contains("/inventory"),
            () -> page.getByTestId("order-handover-open").click());
        page.locator("#add-handover-item-btn").click();
        assertThat(
                page.locator(
                    "select[name='items[0].inventoryItemId'] option[value='"
                        + bInventoryItemId
                        + "']"))
            .hasCount(1);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "crossstaffel-joborder");
        throw failure;
      }
    }

    BackendSeeder seeder = new BackendSeeder();
    String lagerPath = "/api/v1/inventory/material/" + materialId;
    String bLager = seeder.getBody(MEMBER_USER, MEMBER_PASSWORD, lagerPath);
    assumeTrue(
        bLager.contains(bInventoryItemId),
        "precondition: B's own Lager-View should list the seeded item");
    String aLager = seeder.getBody(OFFICER_USER, OFFICER_PASSWORD, lagerPath);
    assertFalse(
        aLager.contains(bInventoryItemId),
        "B-owned inventory must NOT leak into Staffel A's Lager-View");
    assertTrue(
        bLager.contains(bInventoryItemId),
        "B-owned inventory must be visible in B's own Lager-View");
  }
}
