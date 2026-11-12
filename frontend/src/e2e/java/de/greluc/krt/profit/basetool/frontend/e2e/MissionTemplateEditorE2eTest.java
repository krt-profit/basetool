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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verifies that mission template management keeps a peer's open editor intact. */
@Tag("e2e")
class MissionTemplateEditorE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = "test-admin";
  private static final String PASSWORD = "test-admin-pw";

  /** Creates a template and observes the shared list refresh while another editor stays open. */
  @Test
  void createRefreshesThePeerListWithoutReplacingItsOpenEditor() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    new BackendSeeder().ensureIridiumMembership(USERNAME, PASSWORD);
    String name = "E2E template " + UUID.randomUUID();
    String draft = "Unsaved template";
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, STACK.managesStack())) {
      var auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
      var options =
          new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth);
      try (BrowserContext writerContext = browser.newContext(options);
          BrowserContext peerContext = browser.newContext(options)) {
        Page writer = writerContext.newPage();
        Page peer = peerContext.newPage();
        try {
          E2eSupport.navigate(writer, STACK.baseUrl() + "/missions");
          writer.getByTestId("page-tabs").locator("a[href='/mission-templates']").click();
          E2eSupport.navigate(peer, STACK.baseUrl() + "/mission-templates");
          peer.waitForFunction(
              "window.krtLiveSync.subscribedTopics().some(topic => "
                  + "topic.startsWith('mission-templates:'))");

          peer.getByTestId("mission-template-create").click();
          peer.locator("#mission-template-name").fill(draft);
          peer.evaluate("window.__krtNoReload = true");

          writer.getByTestId("mission-template-create").click();
          writer.locator("#mission-template-name").fill(name);
          writer.getByTestId("mission-template-save").click();

          Locator results = peer.locator("#mission-template-list");
          assertThat(results.getByText(name)).hasCount(1);
          assertThat(peer.locator("#mission-template-name")).hasValue(draft);
          assertEquals(Boolean.TRUE, peer.evaluate("window.__krtNoReload === true"));
          assertThat(writer.locator("#mission-template-editor")).isHidden();

          writer.getByTestId("mission-template-create").click();
          writer.locator("#mission-template-name").fill(name);
          writer.getByTestId("mission-template-save").click();
          assertThat(writer.locator("#mission-template-name-error")).isVisible();
          assertThat(writer.locator("#mission-template-name")).hasValue(name);
          writer.locator("[data-template-cancel]").click();
          writer.locator(".krt-confirm-ok").click();

          writer
              .getByRole(AriaRole.ROW)
              .filter(new Locator.FilterOptions().setHasText(name))
              .locator("[data-template-edit]")
              .click();
          writer.locator("[data-template-add='objectives']").click();
          writer.locator("[data-template-rows='objectives'] input").fill("Rally at the jump point");
          writer.getByTestId("mission-template-save").click();
          assertThat(writer.locator("#mission-template-editor")).isHidden();
          writer
              .getByRole(AriaRole.ROW)
              .filter(new Locator.FilterOptions().setHasText(name))
              .locator("[data-template-edit]")
              .click();
          assertThat(writer.locator("[data-template-rows='objectives'] input"))
              .hasValue("Rally at the jump point");

          writer.locator("[data-template-delete]").click();
          writer.locator(".krt-confirm-ok").click();
          assertThat(results.getByText(name)).hasCount(0);
          assertThat(peer.locator("#mission-template-name")).hasValue(draft);
        } catch (RuntimeException | AssertionError failure) {
          E2eSupport.dump(writer, "mission-template-writer");
          E2eSupport.dump(peer, "mission-template-peer");
          throw failure;
        }
      }
    }
  }

  /** Starts with the plan of an operation-linked mission and saves it under a different owner. */
  @Test
  void saveAsTemplateCopiesPlanningFieldsToAnEditableIndependentDraft() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    BackendSeeder seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String sourceName = "Source mission " + suffix;
    String templateName = "Reusable plan " + suffix;
    String destination =
        seeder.createSquadron(USERNAME, PASSWORD, "Template destination " + suffix, "T" + suffix);
    String shipType = seeder.findShipTypeIdByName(USERNAME, PASSWORD, "E2E Ship Type");
    assertNotNull(shipType);
    String missionId = seedSourcePlan(seeder, suffix, sourceName, shipType);
    var source =
        JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, "/api/v1/missions/" + missionId))
            .getAsJsonObject();
    assertNotEquals(destination, source.getAsJsonObject("owningSquadron").get("id").getAsString());
    String missionUrl = STACK.baseUrl() + "/missions/" + missionId;

    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, STACK.managesStack())) {
      var auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
      var options =
          new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth);
      try (BrowserContext context = browser.newContext(options)) {
        Page page = context.newPage();
        try {
          E2eSupport.navigate(page, missionUrl);
          page.getByTestId("mission-save-as-template").click();
          Locator editor = page.locator("#mission-template-editor");
          Locator name = editor.locator("#mission-template-name");
          assertThat(name).hasValue(sourceName);
          assertThat(editor.locator("[data-template-delete]")).isHidden();
          editor.locator("[data-template-cancel]").click();
          assertThat(editor).isHidden();
          assertEquals(
              0,
              JsonParser.parseString(
                      seeder.getBody(
                          USERNAME,
                          PASSWORD,
                          "/api/v1/mission-templates?owningOrgUnitId=" + destination))
                  .getAsJsonArray()
                  .size());

          E2eSupport.navigate(page, missionUrl);
          page.getByTestId("mission-save-as-template").click();
          assertThat(name).hasValue(sourceName);
          assertThat(editor.locator("[data-template-rows='objectives'] input").nth(0))
              .hasValue("Survey wreck");
          assertThat(editor.locator("[data-template-rows='objectives'] select").nth(0))
              .hasValue("SECONDARY");
          assertThat(editor.locator("[data-template-rows='objectives'] input").nth(1))
              .hasValue("Recover cargo");
          assertThat(editor.locator("[data-template-rows='steps'] [name='meta']").nth(0))
              .hasValue("At 18:00");
          Locator unit = editor.locator("[data-template-rows='units'] li");
          assertThat(unit.locator("[name='shipTypeId']")).hasValue(shipType);
          assertThat(unit.locator("[name='highValueUnit']")).isChecked();
          assertThat(unit.locator("[name='frequency']")).hasValue("127.46");

          editor.locator("#mission-template-owner").selectOption(destination);
          name.fill(templateName);
          unit.locator("[name='note']").fill("Check alternate landing zone");
          page.evaluate("window.__krtNoReload = true");
          editor.getByTestId("mission-template-save").click();
          assertThat(editor).isHidden();
          assertThat(page.locator("#mission-template-list").getByText(templateName)).isVisible();
          assertEquals(Boolean.TRUE, page.evaluate("window.__krtNoReload === true"));

          JsonObject saved = readSavedTemplate(seeder, destination);
          assertEquals(templateName, saved.get("name").getAsString());
          assertEquals(destination, saved.get("owningOrgUnitId").getAsString());
          var objectives = saved.getAsJsonArray("objectives");
          assertEquals(
              "Survey wreck", objectives.get(0).getAsJsonObject().get("title").getAsString());
          assertEquals("PRIMARY", objectives.get(1).getAsJsonObject().get("kind").getAsString());
          var step = saved.getAsJsonArray("steps").get(0).getAsJsonObject();
          assertEquals(Set.of("title", "meta"), step.keySet());
          assertEquals("At 18:00", step.get("meta").getAsString());
          var slot = saved.getAsJsonArray("units").get(0).getAsJsonObject();
          assertEquals(
              Set.of("name", "shipTypeId", "note", "highValueUnit", "frequency"), slot.keySet());
          assertEquals("Check alternate landing zone", slot.get("note").getAsString());
          assertEquals(shipType, slot.get("shipTypeId").getAsString());
          assertTrue(slot.get("highValueUnit").getAsBoolean());
          assertEquals(127.46, slot.get("frequency").getAsDouble());
        } catch (RuntimeException | AssertionError failure) {
          E2eSupport.dump(page, "mission-template-from-mission");
          throw failure;
        }
      }
    }
  }

  private static String seedSourcePlan(
      BackendSeeder seeder, String suffix, String sourceName, String shipType) {
    String operationId = seeder.createOperation(USERNAME, PASSWORD, "Source operation " + suffix);
    String missionId =
        seeder.seedEntity(
            USERNAME,
            PASSWORD,
            "/api/v1/missions",
            """
            {
              "name":"%s","status":"PLANNED","isInternal":false,
              "plannedStartTime":"%s","operationId":"%s",
              "objectives":[{"title":"Survey wreck","kind":"SECONDARY"},{"title":"Recover cargo","kind":"PRIMARY"}],
              "steps":[{"title":"Launch","meta":"At 18:00"},{"title":"Extract"}]
            }
            """
                .formatted(sourceName, Instant.now().plus(Duration.ofDays(7)), operationId));
    String path = "/api/v1/missions/" + missionId;
    String responsibleUserId = seeder.getUserId(USERNAME, PASSWORD);
    seeder.addRegisteredParticipant(USERNAME, PASSWORD, missionId, responsibleUserId);
    String ship = seeder.seedShip(USERNAME, PASSWORD, "Scout hull " + suffix, shipType, "LTI");
    seeder.postBody(
        USERNAME,
        PASSWORD,
        path + "/units",
        """
        {"name":"Scout","note":"Check landing zone","highValueUnit":true,"frequency":127.46,
         "shipTypeId":"%s","shipId":"%s","responsibleUserId":"%s"}
        """
            .formatted(shipType, ship, responsibleUserId));
    var source = JsonParser.parseString(seeder.getBody(USERNAME, PASSWORD, path)).getAsJsonObject();
    String stepId = source.getAsJsonArray("steps").get(0).getAsJsonObject().get("id").getAsString();
    assertEquals(
        200,
        seeder.patchForStatus(
            USERNAME,
            PASSWORD,
            path + "/steps/" + stepId + "/done",
            """
            {"done":true,"stepsVersion":%s}
            """
                .formatted(source.get("stepsVersion").getAsLong())));
    return missionId;
  }

  private static JsonObject readSavedTemplate(BackendSeeder seeder, String destination) {
    var list =
        JsonParser.parseString(
                seeder.getBody(
                    USERNAME, PASSWORD, "/api/v1/mission-templates?owningOrgUnitId=" + destination))
            .getAsJsonArray();
    assertEquals(1, list.size());
    String id = list.get(0).getAsJsonObject().get("id").getAsString();
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/mission-templates/" + id))
        .getAsJsonObject();
  }
}
