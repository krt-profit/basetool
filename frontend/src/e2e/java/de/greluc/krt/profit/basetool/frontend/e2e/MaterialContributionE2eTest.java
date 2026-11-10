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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Exercises genuine provision credit, peer updates and deliberate correction retry in browsers. */
@Tag("e2e")
class MaterialContributionE2eTest {
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();
  private static final String ADMIN = "test-admin";
  private static final String ADMIN_PASSWORD = "test-admin-pw";
  private static final String ACTOR = "test-officer";
  private static final String ACTOR_PASSWORD = "test-officer-pw";
  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static String membershipPath;
  private static JsonArray originalStaffeln;
  private static String unit;
  private static String material;
  private static String order;
  private static String claim;
  private static String stock;

  /** Seeds a fresh real contextual writer and ordinary unallocated stock through public APIs. */
  @BeforeAll
  static void setUp() {
    if (!STACK.managesStack())
      throw new IllegalStateException("Requires the isolated managed E2E stack");
    seeder = new BackendSeeder();
    String actor = seeder.getUserId(ACTOR, ACTOR_PASSWORD);
    membershipPath = "/api/v1/org-units/members/" + actor + "/memberships";
    JsonArray memberships =
        JsonParser.parseString(seeder.getBody(ADMIN, ADMIN_PASSWORD, membershipPath + "/detail"))
            .getAsJsonObject()
            .getAsJsonArray("memberships");
    originalStaffeln = new JsonArray();
    for (JsonElement value : memberships) {
      JsonObject row = value.getAsJsonObject();
      if (!"SQUADRON".equals(row.get("kind").getAsString())) continue;
      JsonObject original = new JsonObject();
      original.add("squadronId", row.get("orgUnitId"));
      original.addProperty(
          "isLogistician", row.has("isLogistician") && row.get("isLogistician").getAsBoolean());
      original.addProperty(
          "isMissionManager",
          row.has("isMissionManager") && row.get("isMissionManager").getAsBoolean());
      originalStaffeln.add(original);
    }
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    unit =
        seeder.createSquadron(
            ADMIN, ADMIN_PASSWORD, "E2E material " + suffix, "MC" + suffix.substring(0, 4));
    seeder.moveOrgUnitToProfit(ADMIN, ADMIN_PASSWORD, unit);
    JsonArray granted = originalStaffeln.deepCopy();
    JsonObject grant = new JsonObject();
    grant.addProperty("squadronId", unit);
    grant.addProperty("isLogistician", true);
    grant.addProperty("isMissionManager", false);
    granted.add(grant);
    replaceStaffeln(granted);
    material = seeder.ensureJobOrderMaterial(ADMIN, ADMIN_PASSWORD, "E2E contribution " + suffix);
    String location = seeder.createLocation(ADMIN, ADMIN_PASSWORD, "E2E contribution " + suffix);
    order =
        seeder.createJobOrder(
            ACTOR, ACTOR_PASSWORD, unit, "E2E material contribution", material, 650, 10);
    JsonObject bucket =
        JsonParser.parseString(
                seeder.getBody(ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + order + "/claims"))
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject();
    JsonObject promise = new JsonObject();
    promise.addProperty("materialId", material);
    promise.add("qualityRequirement", bucket.get("qualityRequirement"));
    promise.addProperty("claimingOrgUnitId", unit);
    promise.addProperty("amount", 4);
    claim =
        seeder.seedEntity(
            ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + order + "/claims", promise.toString());
    JsonObject item = new JsonObject();
    item.addProperty("materialId", material);
    item.addProperty("locationId", location);
    item.addProperty("owningOrgUnitId", unit);
    item.addProperty("quality", 750);
    item.addProperty("amount", 6);
    item.addProperty("personal", false);
    stock = seeder.seedEntity(ACTOR, ACTOR_PASSWORD, "/api/v1/inventory", item.toString());
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
  }

  /** Restores the actor's original grants and releases browser resources. */
  @AfterAll
  static void tearDown() {
    try {
      if (originalStaffeln != null) replaceStaffeln(originalStaffeln);
    } finally {
      if (browser != null) browser.close();
      if (playwright != null) playwright.close();
    }
  }

  private static void replaceStaffeln(JsonArray staffeln) {
    JsonObject body = new JsonObject();
    body.add("staffeln", staffeln);
    assertEquals(
        200, seeder.patchForStatus(ADMIN, ADMIN_PASSWORD, membershipPath, body.toString()));
  }

  @Test
  void ordinaryAllocationCreditsOnceAndDirtyPeerCorrectionRequiresExplicitRetry() {
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext acting =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(state));
        BrowserContext viewing =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(true)
                    .setStorageStatePath(state))) {
      Page actor = acting.newPage();
      Page peer = viewing.newPage();
      try {
        navigateInUnit(actor, "/inventory?storage=all");
        navigateInUnit(peer, "/orders/" + order + "?tab=materials");
        peer.evaluate("window.__krtNoReload = true");
        peer.waitForCondition(() ->
            Boolean.TRUE.equals(
                peer.evaluate(
                    "(topic) => !!window.krtLiveSync?.subscribedTopics().includes(topic)",
                    "order:" + order)));
        assertThat(peer.locator("[data-contribution-id='" + claim + "']"))
            .hasAttribute("data-credited-amount", "0.0");
        actor.locator(".tree-row--group[data-material-id='" + material + "']").click();
        actor.locator(".stack-header[data-material-id='" + material + "']").click();
        Locator split =
            actor.locator(
                ".assoc-split[data-entry-id='" + stock + "'][data-assoc-field='JOB_ORDER']");
        split.locator("[data-trigger='inv-admin-assoc-add-open']").click();
        Locator pop = split.locator("[data-assoc-pop]");
        E2eSupport.selectComboboxByValue(pop.locator(".krt-combobox__input"), order);
        assertThat(pop.locator("[data-provision-select] option[value='" + claim + "']"))
            .hasCount(1);
        pop.locator("[data-provision-select]").selectOption(claim);
        pop.locator("[data-assoc-amount-input]").fill("3");
        assertEquals(
            200,
            actor
                .waitForResponse(
                    response ->
                        response.url().contains("/allocation")
                            && response.request().method().equals("POST"),
                    () -> pop.locator("[data-trigger='inv-admin-assoc-save']").click())
                .status());
        assertThat(peer.locator("[data-contribution-id='" + claim + "']"))
            .hasAttribute(
                "data-credited-amount",
                "3.0",
                new LocatorAssertions.HasAttributeOptions().setTimeout(30_000));
        assertEquals(Boolean.TRUE, peer.evaluate("window.__krtNoReload === true"));

        E2eSupport.navigate(actor, STACK.baseUrl() + "/orders/" + order + "?tab=materials");
        actor.evaluate("window.__krtNoReload = true");
        Locator draft = openQuantityCorrection(actor);
        draft.locator("[data-correction-amount]").fill("1");
        String observed = draft.getAttribute("data-credit-version");
        Locator other = openQuantityCorrection(peer);
        other.locator("[data-correction-amount]").fill("2");
        assertEquals(200, saveCorrection(peer, other));
        assertThat(draft.locator("[data-correction-amount]")).hasValue("1");
        assertEquals(observed, draft.getAttribute("data-credit-version"));
        assertEquals(409, saveCorrection(actor, draft));
        assertThat(draft.locator("[data-correction-amount]")).hasValue("1");
        actor.waitForCondition(() ->
            !observed.equals(draft.getAttribute("data-credit-version"))
                && draft.getAttribute("data-refreshing") == null);
        assertThat(draft.locator("[data-correction-message]")).not().isEmpty();
        assertEquals(200, saveCorrection(actor, draft));
        assertThat(peer.locator("[data-contribution-id='" + claim + "']"))
            .hasAttribute(
                "data-credited-amount",
                "1.0",
                new LocatorAssertions.HasAttributeOptions().setTimeout(30_000));
        assertEquals(Boolean.TRUE, actor.evaluate("window.__krtNoReload === true"));
        assertEquals(Boolean.TRUE, peer.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(actor, "material-contribution-actor");
        E2eSupport.dump(peer, "material-contribution-peer");
        throw failure;
      }
    }
  }

  private static void navigateInUnit(Page page, String path) {
    E2eSupport.navigate(page, STACK.baseUrl() + path);
    Locator select = page.locator("#squadron-switcher-select");
    if (select.count() > 0) {
      page.waitForResponse(
          response ->
              response.url().endsWith("/me/active-org-unit")
                  && response.request().method().equals("POST"),
          () -> select.selectOption(unit));
      E2eSupport.navigate(page, STACK.baseUrl() + path);
    }
  }

  private static Locator openQuantityCorrection(Page page) {
    Locator provision =
        page.locator("[data-contribution-id='" + claim + "'] details[data-provision-id]").first();
    provision.locator("summary").click();
    provision.locator("[data-open-contribution-correction]").click();
    Locator form = provision.locator("form[data-contribution-correction]");
    form.locator("[data-correction-kind]").selectOption("QUANTITY");
    assertThat(form.locator("[data-stock-version]").first()).isAttached();
    return form;
  }

  private static int saveCorrection(Page page, Locator form) {
    return page.waitForResponse(
            response ->
                response.url().endsWith("/correction")
                    && response.request().method().equals("POST"),
            () -> form.locator("button[type='submit']").click())
        .status();
  }
}
