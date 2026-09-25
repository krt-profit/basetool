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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end coverage for the one-click ingest handoff (epic #639, {@code REQ-INGEST-003/-004}):
 * the gateway stages a draft in Redis under {@code ingest:handoff:<sub>:<id>}, the extractor opens
 * {@code ?handoff=<id>}, and the frontend pre-fills the existing review form from the staged draft
 * — single-use and scoped to the browsing user.
 *
 * <p>The gateway is not run here; instead the test reproduces what the gateway does, using the real
 * backend matcher: it posts the {@code RefineryExtract} fixture to {@code
 * /api/v1/refinery-orders/import-extract} (resolving the fixture's names against the seeded
 * catalog) and stages the verbatim draft answer in the same Redis the frontend reads. That keeps
 * the draft shape honest — it is the exact JSON production would stage — without standing up the
 * device grant.
 *
 * <p>Covers the happy path (pre-fill matches the manual-upload result), single-use consumption (a
 * replayed id falls back to the friendly notice), and per-{@code sub} isolation (a handoff staged
 * for another user is invisible and stays unconsumed — no IDOR).
 */
@Tag("e2e")
class IngestHandoffE2eTest {

  /** Provisions (or, in staging mode, targets) the stack for the whole run. */
  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final String USERNAME = System.getProperty("e2e.username", "test-admin");
  private static final String PASSWORD = System.getProperty("e2e.password", "test-admin-pw");

  /**
   * The ephemeral e2e Redis, published to the host by {@code docker-compose.e2e.yml} ({@code
   * redis-dev} → {@code 127.0.0.1:6379}), as the gateway's own ACL user (REQ-SEC-068): staging a
   * handoff here goes through exactly the permissions the real gateway has. Throwaway password from
   * {@code RedisAclTemplate.E2E_PASSWORDS}; never a production endpoint or credential.
   */
  private static final String INGEST_REDIS_URI =
      "redis://"
          + RedisAclTemplate.INGEST_USER
          + ":"
          + RedisAclTemplate.E2E_PASSWORDS.get("REDIS_INGEST_PASSWORD")
          + "@127.0.0.1:6379";

  /**
   * The same Redis as the operator's {@code admin} user, for what the test observes rather than
   * what the gateway does: reading a handoff without consuming it and cleaning one up. The
   * gateway's user may do neither, by design.
   */
  private static final String ADMIN_REDIS_URI =
      "redis://"
          + RedisAclTemplate.ADMIN_USER
          + ":"
          + RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD")
          + "@127.0.0.1:6379";

  private static com.microsoft.playwright.Playwright playwright;
  private static Browser browser;
  private static String materialId;
  private static String sub;
  private static String draftJson;

  /** Launches the browser and, for the ephemeral stack, seeds the catalog, sub, and the draft. */
  @BeforeAll
  static void setUp() throws IOException, URISyntaxException {
    playwright = com.microsoft.playwright.Playwright.create();
    browser = E2eSupport.launchBrowser(playwright, STACK.managesStack());
    if (STACK.managesStack()) {
      BackendSeeder seeder = new BackendSeeder();
      seeder.ensureIridiumMembership(USERNAME, PASSWORD);
      // The fixture's first row "E2E IMPORT MATERIAL" folds onto this RAW material; its
      // misspelled second row stays unmatched. E2eStackExtension seeds the material before any page
      // renders (the picker's catalogue is cached from the first render on); this looks its id up.
      materialId =
          seeder.ensureRefineryMaterial(
              USERNAME, PASSWORD, E2eStackExtension.PICKER_MATERIAL_IMPORT);
      sub = seeder.getUserId(USERNAME, PASSWORD);
      String extract =
          Files.readString(
              Path.of(
                  IngestHandoffE2eTest.class.getResource("/refinery-extract-e2e.json").toURI()));
      // Exactly what the gateway forwards + stages: the backend's verbatim draft for this extract.
      draftJson = seeder.importRefineryExtractDraft(USERNAME, PASSWORD, extract);
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
   * Happy path + single-use: a staged refinery handoff pre-fills the create form exactly like the
   * manual upload, and replaying the same id falls back to the friendly notice (the entry was
   * consumed by the first pickup).
   */
  @Test
  void stagedHandoffPrefillsTheFormAndIsSingleUse() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral stack's Redis + backend seeding");
    String baseUrl = STACK.baseUrl();
    String handoffId = newHandoffId();
    stage(key(sub, handoffId), stagedHandoff(draftJson));
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);

    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create?handoff=" + handoffId);

        // The staged draft pre-fills the form and shows the review banner — same as a manual
        // upload.
        assertThat(page.getByTestId("refinery-import-banner"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.locator("#inputMaterialId_0")).hasValue(materialId);
        assertThat(page.locator("#inputQuantity_0")).hasValue("250");
        assertThat(page.locator("#outputQuantity_0")).hasValue("120");
        assertThat(page.locator("#quality_0")).hasValue("618");
        assertThat(page.locator("#startedAt")).hasValue("2026-06-01T19:39:01Z");
        // The misspelled second row stays unmatched, exactly as in the manual import flow.
        assertThat(page.locator("#inputMaterialId_1")).hasValue("");

        // The pickup is single-use: Redis no longer holds the entry...
        assertNull(
            get(key(sub, handoffId)), "the handoff must be consumed (GETDEL) on first pickup");

        // ...so replaying the same id renders the fresh form plus the friendly not-found notice.
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create?handoff=" + handoffId);
        assertThat(page.getByTestId("refinery-import-error").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.getByTestId("refinery-import-banner")).hasCount(0);
        assertThat(page.locator("#inputMaterialId_0")).hasValue("");
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "ingest-handoff-happy");
        throw failure;
      }
    }
  }

  /**
   * Per-{@code sub} isolation: a handoff staged for a different user is invisible to this user
   * (unknown-id notice, no pre-fill) and is NOT consumed by the foreign read — so there is no IDOR
   * and no way to drain another user's draft. An entirely unknown id behaves the same.
   */
  @Test
  void aHandoffStagedForAnotherUserIsInvisibleAndStaysUnconsumed() {
    assumeTrue(STACK.managesStack(), "needs the ephemeral stack's Redis + backend seeding");
    String baseUrl = STACK.baseUrl();
    String foreignSub = UUID.randomUUID().toString();
    String foreignId = newHandoffId();
    String foreignKey = key(foreignSub, foreignId);
    stage(foreignKey, stagedHandoff(draftJson));
    Path storageState = E2eSupport.authenticatedStorageState(browser, baseUrl, USERNAME, PASSWORD);

    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setStorageStatePath(storageState))) {
      Page page = context.newPage();
      try {
        // Our session's sub does not own this id, so the lookup misses → friendly notice, no
        // banner.
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create?handoff=" + foreignId);
        assertThat(page.getByTestId("refinery-import-error").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.getByTestId("refinery-import-banner")).hasCount(0);
        assertThat(page.locator("#inputMaterialId_0")).hasValue("");

        // The foreign user's draft is untouched — our read never reached their sub-scoped key.
        assertNotNull(get(foreignKey), "a foreign handoff must not be consumable by another user");

        // A wholly unknown id behaves identically (no leak, friendly notice).
        E2eSupport.navigate(page, baseUrl + "/refinery-orders/create?handoff=" + newHandoffId());
        assertThat(page.getByTestId("refinery-import-error").first())
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20_000));
        assertThat(page.getByTestId("refinery-import-banner")).hasCount(0);
      } catch (RuntimeException | AssertionError failure) {
        E2eSupport.dump(page, "ingest-handoff-isolation");
        throw failure;
      } finally {
        del(foreignKey);
      }
    }
  }

  /**
   * A fresh, valid 160-bit-style handoff id (matches the gateway's {@code [A-Za-z0-9_-]} shape).
   */
  private static String newHandoffId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** The shared Redis key schema {@code ingest:handoff:<sub>:<id>}. */
  private static String key(String forSub, String handoffId) {
    return "ingest:handoff:" + forSub + ":" + handoffId;
  }

  /**
   * Wraps a backend draft as the {@link
   * de.greluc.krt.profit.basetool.frontend.model.dto.StagedHandoff} JSON the gateway stores (kind +
   * the verbatim draft as a string).
   */
  private static String stagedHandoff(String draft) {
    JsonObject staged = new JsonObject();
    staged.addProperty("kind", "REFINERY");
    staged.addProperty("draftJson", draft);
    return staged.toString();
  }

  /**
   * Writes a value to the e2e Redis (as the gateway would), using the throwaway dev credentials.
   */
  private static void stage(String redisKey, String value) {
    withRedis(INGEST_REDIS_URI, commands -> commands.set(redisKey, value));
  }

  /** Reads a value from the e2e Redis without consuming it (to assert single-use / isolation). */
  private static String get(String redisKey) {
    return withRedis(ADMIN_REDIS_URI, commands -> commands.get(redisKey));
  }

  /** Deletes a key from the e2e Redis (test cleanup for the unconsumed foreign handoff). */
  private static void del(String redisKey) {
    withRedis(ADMIN_REDIS_URI, commands -> commands.del(redisKey));
  }

  /**
   * Runs one command against a short-lived Lettuce connection to the published e2e Redis.
   *
   * @param uri the connection URI, carrying the ACL user and its throwaway password.
   * @param op the command to run.
   * @param <R> the command's result type.
   * @return the command's result.
   */
  private static <R> R withRedis(
      String uri,
      java.util.function.Function<io.lettuce.core.api.sync.RedisCommands<String, String>, R> op) {
    RedisClient client = RedisClient.create(uri);
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      return op.apply(connection.sync());
    } finally {
      client.shutdown();
    }
  }
}
