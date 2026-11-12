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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
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
        planner.locator("#mission-create-objective-add").click();
        Locator goal = planner.locator("#mission-create-objective-list .ae-title");
        goal.fill("Manual");
        Locator select = planner.locator("#mission-create-template-select");
        assertThat(
                select.locator("option").filter(new Locator.FilterOptions().setHasText(template)))
            .hasCount(1);
        select.selectOption(new SelectOption().setLabel(template));
        planner.locator(".krt-confirm-cancel").click();
        assertThat(goal).hasValue("Manual");

        select.selectOption(new SelectOption().setLabel(template));
        planner.locator(".krt-confirm-ok").click();
        assertThat(goal).hasValue("Rally");
        assertThat(planner.locator("#mission-create-step-list .ae-title")).hasValue("Hold");
        Locator unit = planner.locator("#mission-create-unit-list [data-plan-field='name']");
        assertThat(unit).hasValue("Scout");
        unit.fill("Pathfinder");

        Locator calendarLink = planner.locator("#mission-calendar-link");
        calendarLink.fill("http://example.invalid");
        E2eSupport.clickSubmitClearingFooter(planner.getByTestId("mission-save"));
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

        E2eSupport.clickSubmitClearingFooter(planner.getByTestId("mission-save"));
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

        E2eSupport.clickSubmitClearingFooter(planner.getByTestId("mission-save"));
        assertThat(detach).isVisible();
        detach.click();
        E2eSupport.clickSubmitClearingFooter(planner.getByTestId("mission-save"));
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
}
