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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Exercises member catalogue evidence and source-driven fragment updates on the isolated stack. */
@Tag("e2e")
class CatalogueEnrichmentE2eTest {
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String MEMBER = "test-member";
  private static final String MEMBER_PASSWORD = "test-member-pw";
  private static final String MATERIAL_ID = "0e2e0000-0000-4000-8000-000000009101";
  private static final String SHIP_TYPE_ID = "0e2e0000-0000-4000-8000-000000009201";
  private static final String ITEM_ID = "0e2e0000-0000-4000-8000-000000009301";
  private static final String PRODUCT_NAME = "E2E Catalogue Evidence Rifle";
  private static final String SHIP_NAME = "E2E Catalogue Ship " + UUID.randomUUID();
  private static Playwright playwright;
  private static Browser browser;
  private static BackendSeeder seeder;
  private static Path memberSession;
  private static LettuceConnectionFactory redis;

  /** Seeds source-owned facts with the existing SQL harness and authenticates a regular member. */
  @BeforeAll
  static void setUp() {
    assumeTrue(STACK.managesStack(), "Source fixtures require the isolated E2E stack");
    playwright = Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, true);
    seeder = new BackendSeeder();
    String memberId = seeder.getUserId(MEMBER, MEMBER_PASSWORD);
    seeder.assignStaffelMembership(
        System.getProperty("e2e.username", "test-admin"),
        System.getProperty("e2e.password", "test-admin-pw"),
        memberId,
        "00000000-0000-0000-0000-000000000001",
        false,
        false);
    seeder.seedSql("/catalogue-enrichment-e2e-seed.sql");
    seeder.seedShip(MEMBER, MEMBER_PASSWORD, SHIP_NAME, SHIP_TYPE_ID, "LTI");
    memberSession =
        E2eSupport.authenticatedStorageState(browser, STACK.baseUrl(), MEMBER, MEMBER_PASSWORD);
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration("localhost", 6379);
    configuration.setUsername(RedisAclTemplate.BACKEND_USER);
    configuration.setPassword(
        RedisPassword.of(RedisAclTemplate.E2E_PASSWORDS.get("REDIS_BACKEND_PASSWORD")));
    redis = new LettuceConnectionFactory(configuration);
    redis.afterPropertiesSet();
    redis.start();
  }

  /** Releases browser resources and the connection to the harness Redis instance. */
  @AfterAll
  static void tearDown() {
    if (redis != null) {
      redis.destroy();
    }
    if (browser != null) {
      browser.close();
    }
    if (playwright != null) {
      playwright.close();
    }
  }

  /**
   * Preserves source attribution, unknown relationships and empty recipes through a live refresh.
   */
  @Test
  void memberFactsAndRecipeFallback_refreshAfterCommittedSourceChangesWithoutReload() {
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setLocale("en-GB")
                .setStorageStatePath(memberSession))) {
      Page material = context.newPage();
      Page item = context.newPage();
      Page hangar = context.newPage();
      Page recipe = context.newPage();
      try {
        E2eSupport.navigate(material, STACK.baseUrl() + "/materials/" + MATERIAL_ID + "?lang=en");
        Locator mining = material.getByTestId("material-wiki-mining");
        assertThat(mining).containsText("Mining and cargo");
        assertThat(mining).containsText("E2E Mining Belt");
        assertThat(mining).containsText("E2E Parent Planet");
        assertThat(mining).containsText("E2E deposit");
        mining.evaluate("node => node.setAttribute('data-source-snapshot', 'old')");

        E2eSupport.navigate(item, STACK.baseUrl() + "/inventory/game-item/" + ITEM_ID + "?lang=en");
        Locator details = item.getByTestId("item-wiki-catalogue");
        assertThat(details).containsText("E2E-BUILD-A");
        assertThat(item.getByTestId("item-catalogue-geometry")).containsText("0.0002");
        assertThat(details).containsText("No relationships reported.");
        assertThat(details).containsText("This relationship has not been loaded yet.");
        assertThat(details).containsText("<em>E2E source tag</em>");
        assertThat(details.locator("em")).hasCount(0);
        Locator evidence = item.getByTestId("item-derived-uex-evidence");
        assertThat(evidence).containsText("E2E Derived Terminal");
        assertThat(evidence).containsText("not an independent price confirmation");
        assertThat(evidence).containsText("E2E-OLDER-BUILD");

        E2eSupport.navigate(hangar, STACK.baseUrl() + "/hangar?lang=en");
        Locator facts =
            hangar
                .getByTestId("hangar-ship-row")
                .filter(new Locator.FilterOptions().setHasText(SHIP_NAME))
                .getByTestId("hangar-catalogue-facts");
        assertThat(facts).containsText("Inventory: 0.71 SCU");
        assertThat(facts).containsText("E2E-BUILD-A");

        E2eSupport.navigate(recipe, STACK.baseUrl() + "/personal-inventory/blueprints?lang=en");
        recipe
            .locator("#krt-bp-master-rows .master-row")
            .filter(new Locator.FilterOptions().setHasText(PRODUCT_NAME))
            .click();
        assertThat(recipe.locator("#krt-bp-detail-recipe")).containsText("E2E unlock mission");
        assertThat(recipe.locator("#krt-bp-detail-recipe .krt-bp-recipe-empty"))
            .hasText("No recipe data available.");

        List<Page> viewers = List.of(material, item, hangar, recipe);
        for (Page viewer : viewers) {
          viewer.waitForCondition(() ->
              Boolean.TRUE.equals(
                  viewer.evaluate(
                      "!!window.krtLiveSync?.subscribedTopics().includes('catalogue')")));
          viewer.evaluate("window.__catalogueNoReload = true");
        }
        seeder.seedSql("/catalogue-enrichment-e2e-update.sql");
        StringRedisTemplate messages = new StringRedisTemplate(redis);
        Long receivers =
            messages.convertAndSend(
                "basetool:livesync:changed",
                "{\"v\":1,\"origin\":\"catalogue-e2e-source\",\"topic\":\"catalogue\","
                    + "\"sections\":[\"catalogue\"]}");
        assertTrue(
            receivers != null && receivers > 0, "the frontend must consume the source signal");

        assertThat(item.getByTestId("item-wiki-catalogue")).containsText("E2E-BUILD-B");
        assertThat(facts).containsText(Pattern.compile("Inventory: 1[.,]42 SCU"));
        assertThat(facts).containsText("E2E-BUILD-B");
        assertThat(recipe.locator("#krt-bp-detail-recipe")).containsText("E2E unlock mission");
        assertThat(recipe.locator("#krt-bp-detail-recipe")).containsText("E2E-BUILD-B");
        assertThat(recipe.locator("#krt-bp-detail-recipe .krt-bp-recipe-empty")).isVisible();
        assertThat(material.getByTestId("material-wiki-mining"))
            .not()
            .hasAttribute("data-source-snapshot", "old");
        for (Page viewer : viewers) {
          assertEquals(
              Boolean.TRUE,
              viewer.evaluate("window.__catalogueNoReload === true"),
              "source notifications must update member fragments in place");
          assertThat(viewer.locator(".notification-toast.error-toast")).hasCount(0);
        }
        E2eSupport.capture(material, "catalogue-enrichment-mining");
        E2eSupport.capture(item, "catalogue-enrichment-item");
        E2eSupport.capture(hangar, "catalogue-enrichment-hangar");
        E2eSupport.capture(recipe, "catalogue-enrichment-recipe");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(material, "catalogue-enrichment-mining");
        E2eSupport.dump(item, "catalogue-enrichment-item");
        E2eSupport.dump(hangar, "catalogue-enrichment-hangar");
        E2eSupport.dump(recipe, "catalogue-enrichment-recipe");
        throw failure;
      }
    }
  }
}
