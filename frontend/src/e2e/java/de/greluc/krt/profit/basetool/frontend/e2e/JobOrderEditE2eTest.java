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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Edits a MATERIAL job order through the detail-page edit modal and verifies the new amount and
 * comment via a polled backend read-back. Runs as {@code test-admin}, which reaches {@code
 * LOGISTICIAN} through the role hierarchy.
 */
@Tag("e2e")
class JobOrderEditE2eTest {

  /** Provisions (or, in staging mode, targets) the shared stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** Maximum order read-back polls awaiting the edit to commit (~15 s at the backoff below). */
  private static final int EDIT_POLL_ATTEMPTS = 30;

  /** Backoff between order read-back polls, in milliseconds. */
  private static final int EDIT_POLL_BACKOFF_MILLIS = 500;

  private static Playwright playwright;
  private static Browser browser;
  private static String jobOrderId;

  /**
   * Launches the browser and, for the ephemeral stack, seeds the IRIDIUM membership, a job-order
   * material and a MATERIAL order requesting it (amount {@code 100.0}, the value the edit raises).
   */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      String materialId = seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Edit Material");
      jobOrderId =
          seeder.createJobOrder(
              USERNAME, PASSWORD, IRIDIUM_ID, "E2E Edit Order", materialId, 650, 100.0);
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
   * Opens the edit modal, raises the material amount to {@code 250} and sets a unique comment,
   * submits, and asserts both the new amount and the comment via a backend read-back.
   */
  @Test
  void editsAMaterialOrderThroughTheModal() {
    String baseUrl = STACK.baseUrl();
    String comment = "E2E edited comment " + UUID.randomUUID();
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/" + jobOrderId);

        page.locator("[data-trigger='open-modal-display'][data-modal-id='edit-modal']").click();
        E2eSupport.selectComboboxFirstOption(
            page.locator(
                "#edit-modal .krt-combobox:has([data-role='material-select'])"
                    + " .krt-combobox__input"));
        page.locator("#edit-modal input[name='materials[0].amount']").fill("250");
        page.locator("#edit-modal #edit-comment").fill(comment);

        page.locator("#edit-modal button[type='submit']").click();

        JsonObject order = awaitMaterialAmount(page, jobOrderId, 250.0);
        assertEquals(
            250.0,
            order.getAsJsonArray("materials").get(0).getAsJsonObject().get("amount").getAsDouble(),
            0.001,
            "the edited material amount must persist");
        assertEquals(
            comment, order.get("comment").getAsString(), "the edited comment must persist");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-edit");
        throw failure;
      }
    }
  }

  /**
   * Polls {@code GET /api/v1/orders/{id}} until the first material's amount reaches {@code
   * expectedAmount}, up to {@link #EDIT_POLL_ATTEMPTS} reads spaced by {@link
   * #EDIT_POLL_BACKOFF_MILLIS}.
   *
   * @param page the page, used only to pace the poll via {@code waitForTimeout}
   * @param orderId the order to read back
   * @param expectedAmount the first-material amount that signals the edit committed
   * @return the order JSON once it reflects {@code expectedAmount}, or the last read on timeout
   */
  private static JsonObject awaitMaterialAmount(Page page, String orderId, double expectedAmount) {
    BackendSeeder seeder = new BackendSeeder();
    JsonObject order = null;
    for (int attempt = 1; attempt <= EDIT_POLL_ATTEMPTS; attempt++) {
      order =
          JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/orders/" + orderId))
              .getAsJsonObject();
      double amount =
          order.getAsJsonArray("materials").get(0).getAsJsonObject().get("amount").getAsDouble();
      if (Math.abs(amount - expectedAmount) < 0.001) {
        return order;
      }
      page.waitForTimeout(EDIT_POLL_BACKOFF_MILLIS);
    }
    return order;
  }
}
