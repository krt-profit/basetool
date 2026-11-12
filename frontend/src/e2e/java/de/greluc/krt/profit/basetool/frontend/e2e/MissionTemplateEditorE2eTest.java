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
}
