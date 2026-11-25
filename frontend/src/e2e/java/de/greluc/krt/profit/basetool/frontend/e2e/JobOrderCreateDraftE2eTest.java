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
import com.microsoft.playwright.options.SelectOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Reloads a half-filled job-order create form and restores the offered draft (REQ-FE-041): the
 * material form with two rows, which the save then clears, and the item form, whose offer switches
 * the page into item mode and whose line comes back with its blueprint-derived quality.
 */
@Tag("e2e")
class JobOrderCreateDraftE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");
  private static final String IRIDIUM_ID = "00000000-0000-0000-0000-000000000001";

  /** The bootstrap-seeded orderable item whose blueprint derives one recipe material. */
  private static final String ORDERABLE_ITEM_NAME = "E2E Orderable Widget";

  /** Counts the drafts the tab holds. */
  private static final String DRAFT_COUNT =
      "() => Object.keys(sessionStorage).filter((k) => k.startsWith('krt-draft:')).length";

  private static Playwright playwright;
  private static Browser browser;

  /** Launches the browser and seeds the membership and a job-order material. */
  @BeforeAll
  static void setUp() {
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      seeder.ensureJobOrderMaterial(USERNAME, PASSWORD, "E2E Job Material");
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
   * Fills the material form with two rows, reloads, restores the offered draft, compares every
   * value and saves; the save removes the draft.
   */
  @Test
  void materialFormComesBackWithItsRowsAndTheSaveClearsTheDraft() {
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = newContext(baseUrl)) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        page.locator("#responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#requestingOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#handle").fill("E2E Draft Contact");
        Locator rows = page.locator("#materials-container .material-row");
        E2eSupport.selectComboboxFirstOption(rows.nth(0).locator(".krt-combobox__input"));
        rows.nth(0).locator("[data-role='material-amount']").fill("100");
        page.getByTestId("order-add-material").click();
        E2eSupport.selectComboboxFirstOption(rows.nth(1).locator(".krt-combobox__input"));
        rows.nth(1).locator("[data-role='material-amount']").fill("40");
        page.locator("#comment").fill("Draft comment");
        page.locator("#handle").click();

        Map<String, String> typed =
            capture(
                page,
                List.of(
                    "#responsibleOrgUnitId",
                    "#requestingOrgUnitId",
                    "#handle",
                    "#comment",
                    "[name='materials[0].materialId']",
                    "[name='materials[0].amount']",
                    "[name='materials[1].materialId']",
                    "[name='materials[1].amount']"));
        page.waitForFunction(
            "() => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
                + " && k.includes('job-order-material-create')"
                + " && sessionStorage.getItem(k).includes('\"rows\":2')"
                + " && sessionStorage.getItem(k).includes('Draft comment'))");

        page.reload();
        page.waitForLoadState();
        Locator offer = page.locator("#mode-material [data-testid='draft-offer']");
        assertThat(offer).isVisible();
        assertThat(rows).hasCount(1);
        page.locator("#mode-material [data-testid='draft-restore']").click();

        assertThat(offer).isHidden();
        assertThat(rows).hasCount(2);
        typed.forEach((field, value) -> assertThat(page.locator(field)).hasValue(value));

        E2eSupport.awaitFormPost(page, () -> page.getByTestId("order-submit").click());
        page.waitForURL(url -> !url.contains("/orders/create"));
        page.waitForLoadState();
        assertEquals(0, ((Number) page.evaluate(DRAFT_COUNT)).intValue());
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-create-draft-material");
        throw failure;
      }
    }
  }

  /**
   * Fills the item form with the seeded orderable item, reloads, and restores the offered draft:
   * the page switches to item mode, the line comes back with its item, amount and the quality of
   * its derived material.
   */
  @Test
  void itemFormIsRevealedAndItsLineComesBackWithTheDerivedQuality() {
    assumeTrue(STACK.managesStack(), "needs the bootstrap-seeded orderable item / item picker");
    String baseUrl = STACK.baseUrl();
    try (BrowserContext context = newContext(baseUrl)) {
      Page page = context.newPage();
      page.onDialog(dialog -> dialog.accept());
      try {
        E2eSupport.navigate(page, baseUrl + "/orders/create");
        page.getByTestId("order-mode-item").check();
        page.locator("#item-responsibleOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#item-requestingOrgUnitId").selectOption(IRIDIUM_ID);
        page.locator("#item-handle").fill("E2E Item Draft Contact");
        Locator itemCombobox = page.getByTestId("order-item-combobox").first();
        itemCombobox.click();
        itemCombobox.fill(ORDERABLE_ITEM_NAME);
        page.locator("li[role='option']")
            .filter(new Locator.FilterOptions().setHasText(ORDERABLE_ITEM_NAME))
            .first()
            .click();
        Locator quality = page.locator("select[name='items[0].materials[0].quality']");
        quality.waitFor();
        if (quality.locator("option").count() > 1) {
          quality.selectOption(new SelectOption().setIndex(1));
        }
        String chosenQuality = quality.inputValue();
        page.locator("#item-handle").click();
        page.waitForFunction(
            "(q) => Object.keys(sessionStorage).some((k) => k.startsWith('krt-draft:')"
                + " && k.includes('job-order-item-create')"
                + " && sessionStorage.getItem(k).includes('E2E Item Draft Contact')"
                + " && sessionStorage.getItem(k).includes(JSON.stringify(q)))",
            chosenQuality);

        page.reload();
        page.waitForLoadState();
        assertThat(page.locator("#mode-item")).isVisible();
        Locator offer = page.locator("#mode-item [data-testid='draft-offer']");
        assertThat(offer).isVisible();
        page.locator("#mode-item [data-testid='draft-restore']").click();

        assertThat(offer).isHidden();
        assertThat(page.locator("#item-handle")).hasValue("E2E Item Draft Contact");
        assertThat(page.locator("#item-lines .item-line")).hasCount(1);
        assertThat(page.getByTestId("order-item-combobox").first()).hasValue(ORDERABLE_ITEM_NAME);
        assertThat(page.locator("select[name='items[0].materials[0].quality']"))
            .hasValue(chosenQuality);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "joborder-create-draft-item");
        throw failure;
      }
    }
  }

  /**
   * Opens a signed-in browser context.
   *
   * @param baseUrl the frontend origin
   * @return the context; the caller closes it
   */
  private static BrowserContext newContext(String baseUrl) {
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);
    return browser.newContext(
        new Browser.NewContextOptions()
            .setIgnoreHTTPSErrors(true)
            .setStorageStatePath(storageState));
  }

  /**
   * Reads the shown value of each field.
   *
   * @param page the page
   * @param fields the field selectors
   * @return the value per selector, in order
   */
  private static Map<String, String> capture(Page page, List<String> fields) {
    Map<String, String> values = new LinkedHashMap<>();
    fields.forEach(field -> values.put(field, page.locator(field).inputValue()));
    return values;
  }
}
