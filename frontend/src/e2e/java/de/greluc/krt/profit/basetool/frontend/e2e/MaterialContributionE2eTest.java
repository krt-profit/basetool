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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  private static String responsible;
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
    responsible =
        seeder.createSpecialCommand(
            ADMIN, ADMIN_PASSWORD, "E2E material SK " + suffix, "MS" + suffix.substring(0, 4));
    seeder.moveOrgUnitToProfit(ADMIN, ADMIN_PASSWORD, responsible);
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
            ACTOR,
            ACTOR_PASSWORD,
            responsible,
            unit,
            "E2E material contribution",
            material,
            650,
            10);
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
        String stockLocation = stockRow(stock).getAsJsonObject("location").get("id").getAsString();
        actor
            .locator(
                ".stack-header[data-material-id='"
                    + material
                    + "'][data-location-id='"
                    + stockLocation
                    + "']")
            .click();
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

  @Test
  void originalQualityCanBeCorrectedOnceWithoutChangingCurrentStockOrLosingCredit() {
    QualityFixture fixture = qualityFixture();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        navigateInUnit(page, "/orders/" + fixture.orderId() + "?tab=materials&lang=de");
        assertThat(page.locator("html")).hasAttribute("lang", "de");
        page.evaluate("window.__krtNoReload = true");
        Locator provision =
            page.locator(
                    "[data-contribution-id='" + fixture.claimId() + "'] details[data-provision-id]")
                .first();
        provision.locator("summary").click();
        provision.locator("[data-open-contribution-correction]").click();
        Locator form = provision.locator("form[data-contribution-correction]");
        form.locator("[data-correction-kind]").selectOption("QUALITY");
        page.waitForCondition(() -> form.getAttribute("data-attribution-loading") == null);
        String attributionUrl =
            STACK.baseUrl()
                + "/orders/"
                + fixture.orderId()
                + "/claims/provisions/"
                + form.getAttribute("data-credit-id")
                + "/attribution-options?suppliedQuality=1000";
        AtomicReference<Route> pendingAttribution = new AtomicReference<>();
        page.route(attributionUrl, pendingAttribution::set);
        Locator suppliedQuality = form.locator("[data-correction-supplied-quality]");
        suppliedQuality.fill("1000");
        page.waitForCondition(() -> pendingAttribution.get() != null);
        assertThat(suppliedQuality).hasValue("1.000");
        assertEquals("1000", suppliedQuality.evaluate("el => el.value"));
        assertEquals("true", form.getAttribute("data-attribution-loading"));
        suppliedQuality.press("Tab");
        assertThat(suppliedQuality).hasValue("1.000");
        pendingAttribution.get().resume();
        page.waitForCondition(() -> form.getAttribute("data-attribution-loading") == null);
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        assertThat(form.locator("[data-quality-stock-row]:checked")).hasCount(0);
        var saved =
            page.waitForResponse(
                response ->
                    response.url().endsWith("/correction")
                        && response.request().method().equals("POST"),
                () -> form.locator("button[type='submit']").click());
        assertEquals(200, saved.status());
        JsonElement submittedQuality =
            JsonParser.parseString(saved.request().postData())
                .getAsJsonObject()
                .get("suppliedQuality");
        assertTrue(submittedQuality.getAsJsonPrimitive().isNumber());
        assertEquals(1000, submittedQuality.getAsInt());
        JsonObject history =
            JsonParser.parseString(
                    seeder.getBody(
                        ACTOR,
                        ACTOR_PASSWORD,
                        "/api/v1/orders/" + fixture.orderId() + "/claims/history"))
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject();
        assertEquals(3.0, history.get("creditedAmount").getAsDouble());
        JsonObject receipt = history.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(1000, receipt.get("suppliedQuality").getAsInt());
        assertEquals(2, receipt.getAsJsonArray("factChanges").size());
        JsonObject initial = receipt.getAsJsonArray("factChanges").get(0).getAsJsonObject();
        assertEquals("PROVISION_RECORDED", initial.get("reason").getAsString());
        JsonObject correction = receipt.getAsJsonArray("factChanges").get(1).getAsJsonObject();
        assertEquals("FALSE_FACT_CORRECTED", correction.get("reason").getAsString());
        assertEquals(750, correction.getAsJsonObject("previous").get("suppliedQuality").getAsInt());
        assertEquals(1000, correction.getAsJsonObject("current").get("suppliedQuality").getAsInt());
        JsonObject stockRow = stockRow(fixture.stockId());
        assertEquals(750, stockRow.get("quality").getAsInt());
        assertEquals(6.0, stockRow.get("amount").getAsDouble());
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-original-quality");
        throw failure;
      }
    }
  }

  @Test
  void selectedCurrentPortionChangesQualityWithoutChangingUnselectedStockOrCreditingAgain() {
    QualityFixture fixture = qualityFixture();
    String locationId =
        stockRow(fixture.stockId()).getAsJsonObject("location").get("id").getAsString();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        navigateInUnit(page, "/orders/" + fixture.orderId() + "?tab=materials");
        page.evaluate("window.__krtNoReload = true");
        Locator provision =
            page.locator(
                    "[data-contribution-id='" + fixture.claimId() + "'] details[data-provision-id]")
                .first();
        provision.locator("summary").click();
        provision.locator("[data-open-contribution-correction]").click();
        Locator form = provision.locator("form[data-contribution-correction]");
        form.locator("[data-correction-kind]").selectOption("QUALITY");
        form.locator("[data-correction-supplied-quality]").fill("700");
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        Locator physical = form.locator("[data-correction-quality-stock]");
        physical.locator("summary").click();
        Locator selected =
            physical.locator("[data-quality-stock-row][value='" + fixture.stockId() + "']");
        assertThat(selected).isVisible();
        assertThat(selected).not().isChecked();
        selected.check();
        assertEquals(200, saveCorrection(page, form));
        JsonObject original = stockRow(fixture.stockId());
        assertEquals(750, original.get("quality").getAsInt());
        assertEquals(3.0, original.get("amount").getAsDouble());
        JsonArray rows =
            JsonParser.parseString(
                    seeder.getBody(
                        ACTOR,
                        ACTOR_PASSWORD,
                        "/api/v1/inventory/material/" + material + "?size=100"))
                .getAsJsonObject()
                .getAsJsonArray("content");
        int correctedRows = 0;
        for (JsonElement value : rows) {
          JsonObject row = value.getAsJsonObject();
          if (!locationId.equals(row.getAsJsonObject("location").get("id").getAsString())
              || row.get("quality").getAsInt() != 700) continue;
          correctedRows++;
          assertEquals(3.0, row.get("amount").getAsDouble());
        }
        assertEquals(1, correctedRows);
        JsonObject history =
            JsonParser.parseString(
                    seeder.getBody(
                        ACTOR,
                        ACTOR_PASSWORD,
                        "/api/v1/orders/" + fixture.orderId() + "/claims/history"))
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject();
        assertEquals(3.0, history.get("creditedAmount").getAsDouble());
        JsonObject receipt = history.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(700, receipt.get("suppliedQuality").getAsInt());
        assertEquals(2, receipt.getAsJsonArray("factChanges").size());
        JsonObject initial = receipt.getAsJsonArray("factChanges").get(0).getAsJsonObject();
        assertEquals("PROVISION_RECORDED", initial.get("reason").getAsString());
        JsonObject correction = receipt.getAsJsonArray("factChanges").get(1).getAsJsonObject();
        assertEquals("FALSE_FACT_CORRECTED", correction.get("reason").getAsString());
        assertEquals(750, correction.getAsJsonObject("previous").get("suppliedQuality").getAsInt());
        assertEquals(700, correction.getAsJsonObject("current").get("suppliedQuality").getAsInt());
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-selected-current-quality");
        throw failure;
      }
    }
  }

  @Test
  void stockVersionConflictKeepsQualityAndSelectionUntilAnExplicitRetry() {
    QualityFixture fixture = qualityFixture();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        navigateInUnit(page, "/orders/" + fixture.orderId() + "?tab=materials");
        page.evaluate("window.__krtNoReload = true");
        Locator provision =
            page.locator(
                    "[data-contribution-id='" + fixture.claimId() + "'] details[data-provision-id]")
                .first();
        provision.locator("summary").click();
        provision.locator("[data-open-contribution-correction]").click();
        Locator form = provision.locator("form[data-contribution-correction]");
        form.locator("[data-correction-kind]").selectOption("QUALITY");
        form.locator("[data-correction-supplied-quality]").fill("700");
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        Locator physical = form.locator("[data-correction-quality-stock]");
        physical.locator("summary").click();
        Locator selected =
            physical.locator("[data-quality-stock-row][value='" + fixture.stockId() + "']");
        selected.check();
        String observedVersion = selected.getAttribute("data-version");
        JsonObject note = new JsonObject();
        note.addProperty("note", "E2E concurrent stock note");
        note.add("version", stockRow(fixture.stockId()).get("version"));
        assertEquals(
            200,
            seeder.putForStatus(
                ACTOR,
                ACTOR_PASSWORD,
                "/api/v1/inventory/" + fixture.stockId() + "/note",
                note.toString()));
        String freshVersion = stockRow(fixture.stockId()).get("version").getAsString();
        assertNotEquals(observedVersion, freshVersion);
        assertEquals(409, saveCorrection(page, form));
        assertThat(form.locator("[data-correction-message]")).not().isEmpty();
        page.waitForCondition(() ->
            form.getAttribute("data-refreshing") == null
                && form.getAttribute("data-attribution-loading") == null);
        assertThat(form.locator("[data-correction-supplied-quality]")).hasValue("700");
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        assertThat(selected).isChecked();
        JsonObject beforeRetry = claimHistory(fixture.orderId());
        JsonObject unchanged = beforeRetry.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(750, unchanged.get("suppliedQuality").getAsInt());
        assertEquals(1, unchanged.getAsJsonArray("factChanges").size());
        assertEquals(6.0, stockRow(fixture.stockId()).get("amount").getAsDouble());
        assertThat(selected).hasAttribute("data-version", freshVersion);
        assertEquals(200, saveCorrection(page, form));
        JsonObject after = claimHistory(fixture.orderId());
        assertEquals(3.0, after.get("creditedAmount").getAsDouble());
        JsonObject receipt = after.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(700, receipt.get("suppliedQuality").getAsInt());
        assertEquals(2, receipt.getAsJsonArray("factChanges").size());
        JsonObject source = stockRow(fixture.stockId());
        assertEquals(750, source.get("quality").getAsInt());
        assertEquals(3.0, source.get("amount").getAsDouble());
        assertEquals("E2E concurrent stock note", source.get("note").getAsString());
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-quality-stock-conflict");
        throw failure;
      }
    }
  }

  /** A failed required refresh keeps the correction recoverable through deliberate Save actions. */
  @ParameterizedTest(name = "failed {0} refresh keeps correction recoverable")
  @ValueSource(strings = {"quality-options", "attribution-options"})
  void failedRequiredRefreshKeepsQualityCorrectionUntilReadOnlyRecoveryAndExplicitSave(
      String failedEndpoint) {
    QualityFixture fixture = qualityFixture();
    String locationId =
        stockRow(fixture.stockId()).getAsJsonObject("location").get("id").getAsString();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        navigateInUnit(page, "/orders/" + fixture.orderId() + "?tab=materials");
        page.evaluate("window.__krtNoReload = true");
        Locator provision =
            page.locator(
                    "[data-contribution-id='" + fixture.claimId() + "'] details[data-provision-id]")
                .first();
        provision.locator("summary").click();
        provision.locator("[data-open-contribution-correction]").click();
        Locator form = provision.locator("form[data-contribution-correction]");
        form.locator("[data-correction-kind]").selectOption("QUALITY");
        form.locator("[data-correction-supplied-quality]").fill("700");
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        Locator physical = form.locator("[data-correction-quality-stock]");
        physical.locator("summary").click();
        Locator selected =
            physical.locator("[data-quality-stock-row][value='" + fixture.stockId() + "']");
        selected.check();
        page.waitForCondition(() ->
            form.getAttribute("data-attribution-loading") == null
                && form.getAttribute("data-quality-stock-loading") == null);
        String observedVersion = selected.getAttribute("data-version");
        JsonObject note = new JsonObject();
        note.addProperty("note", "E2E concurrent stock note");
        note.add("version", stockRow(fixture.stockId()).get("version"));
        assertEquals(
            200,
            seeder.putForStatus(
                ACTOR,
                ACTOR_PASSWORD,
                "/api/v1/inventory/" + fixture.stockId() + "/note",
                note.toString()));
        String freshVersion = stockRow(fixture.stockId()).get("version").getAsString();
        assertNotEquals(observedVersion, freshVersion);
        String provisionUrl =
            STACK.baseUrl()
                + "/orders/"
                + fixture.orderId()
                + "/claims/provisions/"
                + form.getAttribute("data-credit-id");
        String refreshUrl = provisionUrl + "/" + failedEndpoint;
        String readFailure = "E2E " + failedEndpoint + " refresh unavailable";
        JsonObject problem = new JsonObject();
        problem.addProperty("type", "about:blank");
        problem.addProperty("title", "Temporary read failure");
        problem.addProperty("status", 503);
        problem.addProperty("detail", readFailure);
        AtomicBoolean failedReadPending = new AtomicBoolean(true);
        page.route(
            refreshUrl + "**",
            route -> {
              if (route.request().method().equals("GET")
                  && failedReadPending.compareAndSet(true, false)) {
                route.fulfill(
                    new Route.FulfillOptions()
                        .setStatus(503)
                        .setContentType("application/problem+json")
                        .setBody(problem.toString()));
              } else route.resume();
            });
        assertEquals(409, saveCorrection(page, form));
        page.waitForCondition(() ->
            !failedReadPending.get()
                && form.getAttribute("data-refreshing") == null
                && form.getAttribute("data-attribution-loading") == null
                && form.getAttribute("data-quality-stock-loading") == null);
        Locator message = form.locator("[data-correction-message]");
        assertThat(message).hasText(readFailure);
        assertRetainedQualityDraft(form, fixture);
        assertUnchangedQualityFacts(fixture);

        AtomicBoolean recoveryWrite = new AtomicBoolean(false);
        page.onRequest(request -> {
          if (request.url().equals(provisionUrl + "/correction") && request.method().equals("POST"))
            recoveryWrite.set(true);
        });
        assertEquals(
            200,
            page.waitForResponse(
                    response ->
                        response.url().startsWith(refreshUrl)
                            && response.request().method().equals("GET")
                            && response.status() == 200,
                    () -> form.locator("button[type='submit']").click())
                .status());
        page.waitForCondition(() ->
            form.getAttribute("data-refreshing") == null
                && form.getAttribute("data-attribution-loading") == null
                && form.getAttribute("data-quality-stock-loading") == null
                && freshVersion.equals(selected.getAttribute("data-version")));
        assertThat(message).hasText(form.getAttribute("data-conflict"));
        assertRetainedQualityDraft(form, fixture);
        assertUnchangedQualityFacts(fixture);
        assertThat(selected).hasAttribute("data-version", freshVersion);
        assertFalse(
            recoveryWrite.get(), "Recovering required reads must not submit the correction");

        assertEquals(200, saveCorrection(page, form));
        JsonObject after = claimHistory(fixture.orderId());
        assertEquals(3.0, after.get("creditedAmount").getAsDouble());
        JsonObject receipt = after.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(700, receipt.get("suppliedQuality").getAsInt());
        assertEquals(2, receipt.getAsJsonArray("factChanges").size());
        JsonObject source = stockRow(fixture.stockId());
        assertEquals(750, source.get("quality").getAsInt());
        assertEquals(3.0, source.get("amount").getAsDouble());
        assertEquals("E2E concurrent stock note", source.get("note").getAsString());
        JsonArray rows =
            JsonParser.parseString(
                    seeder.getBody(
                        ACTOR,
                        ACTOR_PASSWORD,
                        "/api/v1/inventory/material/" + material + "?size=100"))
                .getAsJsonObject()
                .getAsJsonArray("content");
        int correctedRows = 0;
        for (JsonElement value : rows) {
          JsonObject row = value.getAsJsonObject();
          if (!locationId.equals(row.getAsJsonObject("location").get("id").getAsString())
              || row.get("quality").getAsInt() != 700) continue;
          correctedRows++;
          assertEquals(3.0, row.get("amount").getAsDouble());
        }
        assertEquals(1, correctedRows);
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-quality-refresh-failure-" + failedEndpoint);
        throw failure;
      }
    }
  }

  /** A delayed older quality read cannot invalidate a newer deliberate attribution or submit it. */
  @Test
  void lateQualityAttributionResponseKeepsNewerTargetAndPhysicalSelectionUntilExplicitSave() {
    JsonObject qualityTier = new JsonObject();
    qualityTier.addProperty("code", "E2E_QUALITY_800");
    qualityTier.addProperty("minQuality", 800);
    qualityTier.addProperty("labelDe", "E2E Mindestqualitaet 800");
    qualityTier.addProperty("labelEn", "E2E minimum quality 800");
    qualityTier.addProperty("sortOrder", 800);
    qualityTier.addProperty("active", true);
    seeder.seedEntity(
        ADMIN, ADMIN_PASSWORD, "/api/v1/catalog/admin/quality-tiers", qualityTier.toString());
    QualityFixture fixture = qualityFixture(true);
    JsonObject promise = new JsonObject();
    promise.addProperty("materialId", material);
    promise.add(
        "qualityRequirement", claimBucket(fixture.orderId(), 800).get("qualityRequirement"));
    promise.addProperty("claimingOrgUnitId", unit);
    promise.addProperty("amount", 4);
    String higherClaim =
        seeder.seedEntity(
            ACTOR,
            ACTOR_PASSWORD,
            "/api/v1/orders/" + fixture.orderId() + "/claims",
            promise.toString());
    String historyPath = "/api/v1/orders/" + fixture.orderId() + "/claims/history";
    JsonArray historyBefore =
        JsonParser.parseString(seeder.getBody(ACTOR, ACTOR_PASSWORD, historyPath)).getAsJsonArray();
    JsonObject stockBefore = stockRow(fixture.stockId());
    String locationId = stockBefore.getAsJsonObject("location").get("id").getAsString();
    Path state =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), ACTOR, ACTOR_PASSWORD);
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(state))) {
      Page page = context.newPage();
      try {
        navigateInUnit(page, "/orders/" + fixture.orderId() + "?tab=materials");
        page.evaluate("window.__krtNoReload = true");
        Locator provision =
            page.locator(
                    "[data-contribution-id='" + fixture.claimId() + "'] details[data-provision-id]")
                .first();
        provision.locator("summary").click();
        provision.locator("[data-open-contribution-correction]").click();
        Locator form = provision.locator("form[data-contribution-correction]");
        form.locator("[data-correction-kind]").selectOption("QUALITY");
        assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
        assertThat(form.locator("[data-correction-amount]")).hasValue("3");
        page.waitForCondition(() -> form.getAttribute("data-attribution-loading") == null);
        String provisionUrl =
            STACK.baseUrl()
                + "/orders/"
                + fixture.orderId()
                + "/claims/provisions/"
                + form.getAttribute("data-credit-id");
        AtomicInteger correctionWrites = new AtomicInteger();
        page.onRequest(request -> {
          if (request.url().equals(provisionUrl + "/correction") && request.method().equals("POST"))
            correctionWrites.incrementAndGet();
        });
        String olderUrl = provisionUrl + "/attribution-options?suppliedQuality=700";
        AtomicReference<Route> olderRoute = new AtomicReference<>();
        AtomicReference<APIResponse> olderResponse = new AtomicReference<>();
        page.route(
            olderUrl,
            route -> {
              olderResponse.set(route.fetch());
              olderRoute.set(route);
            });
        form.locator("[data-correction-supplied-quality]").fill("700");
        page.waitForCondition(() -> olderRoute.get() != null);
        assertEquals(200, olderResponse.get().status());
        assertFalse(
            olderResponse.get().text().contains("value=\"" + higherClaim + "\""),
            "The actual 700 fragment must exclude the 800-minimum target");
        form.locator("[data-correction-supplied-quality]").fill("900");
        Locator higherOption =
            form.locator("[data-correction-target] option[value='" + higherClaim + "']");
        assertThat(higherOption).isEnabled();
        page.waitForCondition(() -> form.getAttribute("data-attribution-loading") == null);
        form.locator("[data-correction-target]").selectOption(higherClaim);
        form.locator("[data-correction-amount]").fill("2");
        Locator physical = form.locator("[data-correction-quality-stock]");
        physical.locator("summary").click();
        Locator selected =
            physical.locator("[data-quality-stock-row][value='" + fixture.stockId() + "']");
        selected.check();
        page.waitForCondition(() -> form.getAttribute("data-quality-stock-loading") == null);
        String selectedVersion = selected.getAttribute("data-version");
        assertEquals(0, correctionWrites.get());

        page.waitForResponse(
                response -> response.url().equals(olderUrl),
                () ->
                    olderRoute
                        .get()
                        .fulfill(new Route.FulfillOptions().setResponse(olderResponse.get())))
            .finished();
        page.evaluate(
            "() => new Promise(resolve => requestAnimationFrame(() =>"
                + " requestAnimationFrame(() => resolve(null))))");
        olderResponse.get().dispose();
        assertThat(form.locator("[data-correction-kind]")).hasValue("QUALITY");
        assertThat(form.locator("[data-correction-supplied-quality]")).hasValue("900");
        assertThat(form.locator("[data-correction-target]")).hasValue(higherClaim);
        assertThat(higherOption).isEnabled();
        assertEquals(
            Boolean.TRUE,
            form.locator("[data-correction-target]").evaluate("element => element.validity.valid"));
        assertThat(form.locator("[data-correction-amount]")).hasValue("2");
        assertThat(selected).isChecked();
        assertThat(selected).hasAttribute("data-version", selectedVersion);
        assertEquals(0, correctionWrites.get(), "A delayed read must not submit a correction");
        assertEquals(
            historyBefore,
            JsonParser.parseString(seeder.getBody(ACTOR, ACTOR_PASSWORD, historyPath))
                .getAsJsonArray());
        assertEquals(stockBefore, stockRow(fixture.stockId()));
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));

        assertEquals(200, saveCorrection(page, form));
        assertEquals(1, correctionWrites.get());
        assertEquals(
            0.0,
            claimHistory(fixture.orderId(), fixture.claimId()).get("creditedAmount").getAsDouble());
        JsonObject corrected = claimHistory(fixture.orderId(), higherClaim);
        assertEquals(2.0, corrected.get("creditedAmount").getAsDouble());
        JsonObject receipt = corrected.getAsJsonArray("provisions").get(0).getAsJsonObject();
        assertEquals(900, receipt.get("suppliedQuality").getAsInt());
        assertEquals(2, receipt.getAsJsonArray("factChanges").size());
        JsonObject fact = receipt.getAsJsonArray("factChanges").get(1).getAsJsonObject();
        assertEquals("FALSE_FACT_CORRECTED", fact.get("reason").getAsString());
        assertEquals(750, fact.getAsJsonObject("previous").get("suppliedQuality").getAsInt());
        assertEquals(900, fact.getAsJsonObject("current").get("suppliedQuality").getAsInt());
        assertEquals(higherClaim, fact.getAsJsonObject("current").get("claimId").getAsString());
        assertEquals(2.0, fact.getAsJsonObject("current").get("creditedAmount").getAsDouble());
        assertEquals(750, stockRow(fixture.stockId()).get("quality").getAsInt());
        assertEquals(3.0, stockRow(fixture.stockId()).get("amount").getAsDouble());
        JsonArray rows =
            JsonParser.parseString(
                    seeder.getBody(
                        ACTOR,
                        ACTOR_PASSWORD,
                        "/api/v1/inventory/material/" + material + "?size=100"))
                .getAsJsonObject()
                .getAsJsonArray("content");
        var correctedStock =
            rows.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(row ->
                    locationId.equals(row.getAsJsonObject("location").get("id").getAsString())
                        && row.get("quality").getAsInt() == 900)
                .toList();
        assertEquals(1, correctedStock.size());
        assertEquals(3.0, correctedStock.get(0).get("amount").getAsDouble());
        assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "material-late-quality-attribution");
        throw failure;
      }
    }
  }

  private static void assertRetainedQualityDraft(Locator form, QualityFixture fixture) {
    assertThat(form.locator("[data-correction-kind]")).hasValue("QUALITY");
    assertThat(form.locator("[data-correction-supplied-quality]")).hasValue("700");
    assertThat(form.locator("[data-correction-target]")).hasValue(fixture.claimId());
    assertThat(form.locator("[data-correction-amount]")).hasValue("3");
    assertThat(form.locator("[data-quality-stock-row][value='" + fixture.stockId() + "']"))
        .isChecked();
  }

  private static void assertUnchangedQualityFacts(QualityFixture fixture) {
    JsonObject history = claimHistory(fixture.orderId());
    assertEquals(3.0, history.get("creditedAmount").getAsDouble());
    JsonObject receipt = history.getAsJsonArray("provisions").get(0).getAsJsonObject();
    assertEquals(750, receipt.get("suppliedQuality").getAsInt());
    assertEquals(1, receipt.getAsJsonArray("factChanges").size());
    JsonObject stockRow = stockRow(fixture.stockId());
    assertEquals(750, stockRow.get("quality").getAsInt());
    assertEquals(6.0, stockRow.get("amount").getAsDouble());
  }

  private static JsonObject claimHistory(String orderId) {
    return JsonParser.parseString(
            seeder.getBody(ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + orderId + "/claims/history"))
        .getAsJsonArray()
        .get(0)
        .getAsJsonObject();
  }

  private static QualityFixture qualityFixture() {
    return qualityFixture(false);
  }

  private static QualityFixture qualityFixture(boolean higherQualityDemand) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String targetOrder =
        higherQualityDemand
            ? qualityRaceOrder(suffix)
            : seeder.createJobOrder(
                ACTOR,
                ACTOR_PASSWORD,
                responsible,
                unit,
                "E2E original quality " + suffix,
                material,
                650,
                10);
    JsonObject bucket = claimBucket(targetOrder, 650);
    JsonObject promise = new JsonObject();
    promise.addProperty("materialId", material);
    promise.add("qualityRequirement", bucket.get("qualityRequirement"));
    promise.addProperty("claimingOrgUnitId", unit);
    promise.addProperty("amount", 4);
    String targetClaim =
        seeder.seedEntity(
            ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + targetOrder + "/claims", promise.toString());
    JsonObject item = new JsonObject();
    item.addProperty("materialId", material);
    item.addProperty(
        "locationId", seeder.createLocation(ADMIN, ADMIN_PASSWORD, "E2E quality " + suffix));
    item.addProperty("owningOrgUnitId", unit);
    item.addProperty("quality", 750);
    item.addProperty("amount", 6);
    item.addProperty("personal", false);
    String targetStock =
        seeder.seedEntity(ACTOR, ACTOR_PASSWORD, "/api/v1/inventory", item.toString());
    JsonObject stockRow = stockRow(targetStock);
    JsonObject claimRow =
        JsonParser.parseString(
                seeder.getBody(
                    ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + targetOrder + "/claims/history"))
            .getAsJsonArray()
            .get(0)
            .getAsJsonObject();
    JsonObject provide = new JsonObject();
    provide.addProperty("claimId", targetClaim);
    provide.add("claimVersion", claimRow.get("version"));
    JsonObject allocation = new JsonObject();
    allocation.addProperty("field", "JOB_ORDER");
    allocation.addProperty("targetId", targetOrder);
    allocation.addProperty("amount", 3);
    allocation.add("version", stockRow.get("version"));
    allocation.add("provision", provide);
    assertEquals(
        200,
        seeder.postForStatus(
            ACTOR,
            ACTOR_PASSWORD,
            "/api/v1/inventory/" + targetStock + "/allocation",
            allocation.toString()));
    return new QualityFixture(targetOrder, targetClaim, targetStock);
  }

  private static String qualityRaceOrder(String suffix) {
    JsonObject create = new JsonObject();
    create.addProperty("responsibleOrgUnitId", responsible);
    create.addProperty("requestingOrgUnitId", unit);
    create.addProperty("handle", "E2E delayed quality " + suffix);
    JsonArray requirements = new JsonArray();
    for (int minimumQuality : new int[] {650, 800}) {
      JsonObject requirement = new JsonObject();
      requirement.addProperty("materialId", material);
      requirement.addProperty("minQuality", minimumQuality);
      requirement.addProperty("amount", 10);
      requirements.add(requirement);
    }
    create.add("materials", requirements);
    return seeder.seedEntity(ACTOR, ACTOR_PASSWORD, "/api/v1/orders", create.toString());
  }

  private static JsonObject claimBucket(String orderId, int minimumQuality) {
    return JsonParser.parseString(
            seeder.getBody(ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + orderId + "/claims"))
        .getAsJsonArray()
        .asList()
        .stream()
        .map(JsonElement::getAsJsonObject)
        .filter(bucket ->
            bucket.getAsJsonObject("qualityTier").get("minQuality").getAsInt() == minimumQuality)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The required quality bucket is absent"));
  }

  private static JsonObject claimHistory(String orderId, String claimId) {
    return JsonParser.parseString(
            seeder.getBody(ACTOR, ACTOR_PASSWORD, "/api/v1/orders/" + orderId + "/claims/history"))
        .getAsJsonArray()
        .asList()
        .stream()
        .map(JsonElement::getAsJsonObject)
        .filter(row -> claimId.equals(row.get("id").getAsString()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The retained contribution is absent"));
  }

  private static JsonObject stockRow(String stockId) {
    JsonArray rows =
        JsonParser.parseString(
                seeder.getBody(
                    ACTOR, ACTOR_PASSWORD, "/api/v1/inventory/material/" + material + "?size=100"))
            .getAsJsonObject()
            .getAsJsonArray("content");
    for (JsonElement value : rows) {
      JsonObject row = value.getAsJsonObject();
      if (stockId.equals(row.get("id").getAsString())) return row;
    }
    throw new IllegalStateException(
        "Fixture stock is absent from the authorized material drilldown");
  }

  /** Independently provided physical stock and its retained public receipt. */
  private record QualityFixture(String orderId, String claimId, String stockId) {}

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
