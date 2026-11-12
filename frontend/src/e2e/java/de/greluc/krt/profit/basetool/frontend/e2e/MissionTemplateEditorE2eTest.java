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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Exercises template authoring, shared updates and application in the browser. */
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
          String topic =
              "mission-templates:" + peer.locator("#mission-template-scope").inputValue();
          peer.waitForFunction(
              "topic => window.krtLiveSync?.subscribedTopics().includes(topic)", topic);

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
          Locator objectives = writer.locator("[data-template-rows='objectives']");
          Locator titles = objectives.locator("input[name='title']");
          writer.locator("[data-template-add='objectives']").click();
          titles.first().fill("Rally at the jump point");
          writer.locator("[data-template-add='objectives']").click();
          Locator scout = objectives.locator("[data-planning-row]").last();
          Locator scoutTitle = scout.locator("input[name='title']");
          scoutTitle.fill("Scout ahead");
          assertThat(scout.getByTestId("reorder-up"))
              .hasAttribute("aria-label", java.util.regex.Pattern.compile(".*Scout ahead.*"));
          scoutTitle.press("Alt+ArrowUp");
          assertThat(titles.first()).hasValue("Scout ahead");
          assertThat(titles.first()).isFocused();
          assertThat(objectives.getByTestId("reorder-up").first())
              .hasAttribute("aria-disabled", "true");
          assertThat(writer.locator("#mission-template-form").getByTestId("reorder-live"))
              .containsText("Scout ahead");
          writer.getByTestId("mission-template-save").click();
          assertThat(writer.locator("#mission-template-editor")).isHidden();
          writer
              .getByRole(AriaRole.ROW)
              .filter(new Locator.FilterOptions().setHasText(name))
              .locator("[data-template-edit]")
              .click();
          assertThat(titles.first()).hasValue("Scout ahead");
          assertThat(titles.last()).hasValue("Rally at the jump point");

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

  /** Applies, reapplies and detaches a revision while keeping the planner's open draft. */
  @Test
  void applyingATemplateKeepsTheOpenCreateDraft() {
    assumeTrue(STACK.managesStack(), "Requires the isolated test stack");
    var seeder = new BackendSeeder();
    seeder.ensureIridiumMembership(USERNAME, PASSWORD);
    String iridium = "00000000-0000-0000-0000-000000000001";
    String suffix = UUID.randomUUID().toString();
    String template = "E2E application " + suffix;
    String mission = "E2E planned " + suffix;
    String operation = seeder.createOperation(USERNAME, PASSWORD, "E2E operation " + suffix);
    String secondOwner =
        seeder.createSquadron(USERNAME, PASSWORD, "E2E template " + suffix, suffix.substring(0, 6));
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, STACK.managesStack())) {
      var auth = E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), USERNAME, PASSWORD);
      var options =
          new Browser.NewContextOptions().setIgnoreHTTPSErrors(true).setStorageStatePath(auth);
      try (BrowserContext editorContext = browser.newContext(options);
          BrowserContext plannerContext = browser.newContext(options)) {
        Page editor = editorContext.newPage();
        Page planner = plannerContext.newPage();
        E2eSupport.navigate(editor, STACK.baseUrl() + "/mission-templates");
        editor.locator("#mission-template-scope").selectOption(iridium);
        editor.getByTestId("mission-template-create").click();
        editor.locator("#mission-template-name").fill(template);
        editor.locator("[data-template-add='objectives']").click();
        editor.locator("[data-template-rows='objectives'] input").fill("Rally");
        editor.locator("[data-template-add='steps']").click();
        editor.locator("[data-template-rows='steps'] input[name='title']").fill("Hold");
        editor.locator("[data-template-add='units']").click();
        editor.locator("[data-template-rows='units'] input[name='name']").fill("Scout");
        editor.getByTestId("mission-template-save").click();
        assertThat(editor.locator("#mission-template-editor")).isHidden();

        seeder.assignTwoStaffelMemberships(
            USERNAME, PASSWORD, seeder.getUserId(USERNAME, PASSWORD), iridium, secondOwner);
        E2eSupport.navigate(planner, STACK.baseUrl() + "/missions/new?operationId=" + operation);
        Locator owner = planner.locator("#owningOrgUnitId");
        owner.selectOption(iridium);
        assertThat(planner.locator("#mission-operation")).hasValue(operation);
        planner.getByTestId("mission-name-input").fill(mission);
        planner
            .getByTestId("mission-start-date")
            .fill(java.time.LocalDate.now().plusDays(7).toString());
        planner.getByTestId("mission-start-time").fill("12:00");
        Locator addGoal = planner.locator("#mission-create-objective-add");
        Locator goals = planner.locator("#mission-create-objective-list");
        addGoal.click();
        Locator goal = goals.locator(".ae-title").first();
        goal.fill("Manual");
        addGoal.click();
        goals.locator(".ae-title").last().fill("Manual first");
        goals.getByTestId("reorder-up").last().click();
        assertThat(goal).hasValue("Manual first");
        Locator select = planner.locator("#mission-create-template-select");
        assertThat(
                select.locator("option").filter(new Locator.FilterOptions().setHasText(template)))
            .hasCount(1);
        select.selectOption(new SelectOption().setLabel(template));
        planner.locator(".krt-confirm-cancel").click();
        assertThat(goal).hasValue("Manual first");

        select.selectOption(new SelectOption().setLabel(template));
        planner.locator(".krt-confirm-ok").click();
        assertThat(goal).hasValue("Rally");
        assertThat(planner.locator("#mission-create-step-list .ae-title")).hasValue("Hold");
        Locator unit = planner.locator("#mission-create-unit-list [data-plan-field='name']");
        assertThat(unit).hasValue("Scout");
        unit.fill("Pathfinder");

        Locator calendarLink = planner.locator("#mission-calendar-link");
        calendarLink.fill("http://example.invalid");
        assertEquals(422, E2eSupport.submitMissionDraft(planner));
        assertThat(planner.locator("[data-error-for='calendarLink']")).isVisible();
        assertThat(calendarLink).hasValue("http://example.invalid");
        assertThat(goal).hasValue("Rally");
        assertThat(unit).hasValue("Pathfinder");
        calendarLink.fill("");

        owner.selectOption(secondOwner);
        assertThat(
                select.locator("option").filter(new Locator.FilterOptions().setHasText(template)))
            .hasCount(0);
        assertThat(select).hasValue("");
        assertThat(goal).hasValue("Rally");
        assertThat(unit).hasValue("Pathfinder");
        assertThat(planner.getByTestId("mission-name-input")).hasValue(mission);
        owner.selectOption(iridium);
        select.selectOption(new SelectOption().setLabel(template));
        planner.locator(".krt-confirm-ok").click();
        assertThat(unit).hasValue("Scout");
        unit.fill("Pathfinder");

        editor
            .getByRole(AriaRole.ROW)
            .filter(new Locator.FilterOptions().setHasText(template))
            .locator("[data-template-edit]")
            .click();
        editor.locator("[data-template-rows='objectives'] input").fill("Rally again");
        editor.getByTestId("mission-template-save").click();
        assertThat(editor.locator("#mission-template-editor")).isHidden();

        assertEquals(409, E2eSupport.submitMissionDraft(planner));
        Locator detach = planner.locator("#mission-create-template-detach");
        assertThat(detach).isVisible();
        assertThat(goal).hasValue("Rally");
        assertThat(unit).hasValue("Pathfinder");
        assertThat(planner.getByTestId("mission-name-input")).hasValue(mission);

        planner.locator("#mission-create-template-reapply").click();
        planner.locator(".krt-confirm-ok").click();
        assertThat(goal).hasValue("Rally again");
        assertThat(unit).hasValue("Scout");
        unit.fill("Pathfinder");

        editor
            .getByRole(AriaRole.ROW)
            .filter(new Locator.FilterOptions().setHasText(template))
            .locator("[data-template-edit]")
            .click();
        editor.locator("[data-template-delete]").click();
        editor.locator(".krt-confirm-ok").click();
        assertThat(editor.getByText(template)).hasCount(0);

        assertEquals(409, E2eSupport.submitMissionDraft(planner));
        assertThat(detach).isVisible();
        detach.click();
        assertEquals(201, E2eSupport.submitMissionDraft(planner));
        planner.waitForURL(url -> url.contains("?tab=verw"));
        assertThat(planner.locator("#mission-objective-list .ae-title")).hasValue("Rally again");
        assertThat(planner.locator(".mission-unit-box .uname")).containsText("Pathfinder");
        assertThat(planner.locator("#mission-operation")).hasValue(operation);
        assertThat(planner.getByTestId("mission-name-input")).hasValue(mission);
      } finally {
        seeder.ensureIridiumMembership(USERNAME, PASSWORD);
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
          assertEquals(0, readTemplates(seeder, destination).size());

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
          assertThat(unit.locator("[name='frequency']")).hasValue(Pattern.compile("^127[.,]46$"));

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

  private static JsonArray readTemplates(BackendSeeder seeder, String destination) {
    return JsonParser.parseString(
            seeder.getBody(
                USERNAME, PASSWORD, "/api/v1/mission-templates?owningOrgUnitId=" + destination))
        .getAsJsonObject()
        .getAsJsonArray("content");
  }

  private static JsonObject readSavedTemplate(BackendSeeder seeder, String destination) {
    var list = readTemplates(seeder, destination);
    assertEquals(1, list.size());
    String id = list.get(0).getAsJsonObject().get("id").getAsString();
    return JsonParser.parseString(
            seeder.getBody(USERNAME, PASSWORD, "/api/v1/mission-templates/" + id))
        .getAsJsonObject();
  }
}
