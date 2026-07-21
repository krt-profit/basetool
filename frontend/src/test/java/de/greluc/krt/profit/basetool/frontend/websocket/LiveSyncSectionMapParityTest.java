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

package de.greluc.krt.profit.basetool.frontend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Build-time enforcement of the REQ-FE-015 three-mirror-points rule for live-sync section maps: the
 * server-side {@link LiveSyncTopicClass} whitelist and the page's JS seam map must name exactly the
 * same section keys. A key added on one side without the other silently strands peers stale
 * (REQ-FE-010) — this test turns that drift into a red build.
 *
 * <p>Reads the shipped JS module from the classpath ({@code src/main/resources} is on the test
 * runtime classpath), extracts the seam-map keys, and asserts set-equality with the registry.
 */
class LiveSyncSectionMapParityTest {

  /**
   * Matches one entry of a seam map — a plain, single- or double-quoted key followed by {@code
   * : { container}. Anchoring on {@code container} avoids matching unrelated nested objects.
   */
  private static final Pattern SEAM_ENTRY =
      Pattern.compile("(?:'([\\w-]+)'|\"([\\w-]+)\"|([\\w-]+))\\s*:\\s*\\{\\s*container");

  @Test
  void missionSeamMap_matchesTheMissionTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/mission-detail.js", "MISSION_SECTIONS");
    assertThat(jsKeys)
        .as("MISSION_SECTIONS keys in mission-detail.js vs LiveSyncTopicClass.MISSION whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MISSION.allowedSections());
  }

  @Test
  void operationSeamMap_matchesTheOperationTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/operation-detail.js", "OPERATION_SECTIONS");
    assertThat(jsKeys)
        .as(
            "OPERATION_SECTIONS keys in operation-detail.js vs LiveSyncTopicClass.OPERATION"
                + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.OPERATION.allowedSections());
  }

  @Test
  void ordersQueueSeamMap_matchesTheOrdersQueueTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/orders-index.js", "ORDERS_SECTIONS");
    assertThat(jsKeys)
        .as("ORDERS_SECTIONS keys in orders-index.js vs LiveSyncTopicClass.ORDERS_QUEUE whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
  }

  @Test
  void orderDetailSeamMap_matchesTheOrderTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/orders-detail.js", "ORDER_SECTIONS");
    assertThat(jsKeys)
        .as("ORDER_SECTIONS keys in orders-detail.js vs LiveSyncTopicClass.ORDER whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.ORDER.allowedSections());
  }

  @Test
  void materialCollectionSeamMap_isASubsetOfTheOrderTopicWhitelist() throws IOException {
    // #1309: the standalone material-collection page joins order:{id} to refresh its table on a
    // delivered flip / row move, but renders only a SUBSET of the ORDER sections (it reuses the
    // existing `materials` key), so it cannot match the full whitelist like orders-detail. Assert
    // every key it names is a real ORDER section — a stray/typo key the relay would silently drop
    // (stranding peers stale, REQ-FE-010) still fails the build.
    Set<String> jsKeys =
        seamMapKeys("/static/js/material-collection.js", "MATERIAL_COLLECTION_SECTIONS");
    assertThat(jsKeys)
        .as(
            "MATERIAL_COLLECTION_SECTIONS keys in material-collection.js vs"
                + " LiveSyncTopicClass.ORDER whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDER.allowedSections());
  }

  @Test
  void inventoryAllSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    // #1307: the shared Lager's INVENTORY_ALL_SECTIONS receiver map must mirror the
    // LiveSyncTopicClass.INVENTORY_ALL whitelist (the broadcast derives its keys from this map).
    Set<String> jsKeys = seamMapKeys("/static/js/inventory-admin.js", "INVENTORY_ALL_SECTIONS");
    assertThat(jsKeys)
        .as(
            "INVENTORY_ALL_SECTIONS keys in inventory-admin.js vs LiveSyncTopicClass.INVENTORY_ALL"
                + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void inventoryMySeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    // #1309: the personal Lager joins the same `inventory` room, so its receiver map must mirror
    // the
    // same whitelist (a different container, the same `stock` key).
    Set<String> jsKeys = seamMapKeys("/static/js/inventory-my.js", "INVENTORY_MY_SECTIONS");
    assertThat(jsKeys)
        .as("INVENTORY_MY_SECTIONS keys in inventory-my.js vs LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void inventoryIndexSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    // #1309: the aggregated overview joins the same `inventory` room (receive-only).
    Set<String> jsKeys = seamMapKeys("/static/js/inventory-index.js", "INVENTORY_INDEX_SECTIONS");
    assertThat(jsKeys)
        .as(
            "INVENTORY_INDEX_SECTIONS keys in inventory-index.js vs"
                + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void inventoryMaterialSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    // #1309: the per-material drilldown joins the same `inventory` room (receive-only).
    Set<String> jsKeys =
        seamMapKeys("/static/js/inventory-material.js", "INVENTORY_MATERIAL_SECTIONS");
    assertThat(jsKeys)
        .as(
            "INVENTORY_MATERIAL_SECTIONS keys in inventory-material.js vs"
                + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void inventoryGameItemSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    // REQ-INV-030: the per-game-item drilldown joins the same `inventory` room (receive-only) —
    // no new section keys were added for the item views, the single `stock` seam covers both
    // catalogs.
    Set<String> jsKeys =
        seamMapKeys("/static/js/inventory-game-item.js", "INVENTORY_GAME_ITEM_SECTIONS");
    assertThat(jsKeys)
        .as(
            "INVENTORY_GAME_ITEM_SECTIONS keys in inventory-game-item.js vs"
                + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void bankStaffAccountSeamMap_matchesTheBankAccountTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank.js", "BANK_ACCOUNT_SECTIONS");
    assertThat(jsKeys)
        .as("BANK_ACCOUNT_SECTIONS keys in bank.js vs LiveSyncTopicClass.BANK_ACCOUNT whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_ACCOUNT.allowedSections());
  }

  @Test
  void orgUnitAccountSeamMap_matchesTheBankAccountTopicWhitelist() throws IOException {
    // The org-unit account-detail variant carries the SAME bank:{id} section keys as the staff map,
    // just different sibling containers — so it too must match the BANK_ACCOUNT whitelist.
    Set<String> jsKeys = seamMapKeys("/static/js/bank.js", "ORGUNIT_ACCOUNT_SECTIONS");
    assertThat(jsKeys)
        .as("ORGUNIT_ACCOUNT_SECTIONS keys in bank.js vs LiveSyncTopicClass.BANK_ACCOUNT whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_ACCOUNT.allowedSections());
  }

  @Test
  void bankStaffSeamMap_matchesTheBankStaffTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank.js", "BANK_STAFF_SECTIONS");
    assertThat(jsKeys)
        .as("BANK_STAFF_SECTIONS keys in bank.js vs LiveSyncTopicClass.BANK_STAFF whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_STAFF.allowedSections());
  }

  @Test
  void orgUnitBankSeamMap_matchesTheOrgUnitBankTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank.js", "ORGUNIT_BANK_SECTIONS");
    assertThat(jsKeys)
        .as("ORGUNIT_BANK_SECTIONS keys in bank.js vs LiveSyncTopicClass.ORGUNIT_BANK whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.ORGUNIT_BANK.allowedSections());
  }

  /**
   * The materialboard broadcast whitelist is exactly the offers ({@code board}) and requests
   * ({@code requests}) section keys (F4; REQ-MARKET-010/018).
   */
  @Test
  void materialboardTopicClass_pinsTheBoardAndRequestsSections() {
    assertThat(LiveSyncTopicClass.MATERIALBOARD.allowedSections())
        .as("MATERIALBOARD whitelist is exactly {board, requests}")
        .containsExactlyInAnyOrder("board", "requests");
  }

  /**
   * The Materialbörse board uses {@code subscribe(topic, {onChanged})} + {@code swapList} rather
   * than a {@code {container}} seam map, so the generic seam-map parity tests do not cover it (F4).
   * Pin its <em>broadcast</em> side directly: every {@code
   * krtLiveSync.sendChanged('materialboard'|MATERIALBOARD_TOPIC, [...])} call across the three
   * materialboerse modules must send only keys in {@link LiveSyncTopicClass#MATERIALBOARD}'s
   * whitelist — so a stray out-of-whitelist key (silently dropped by the relay → stale peer) fails
   * the build instead.
   */
  @Test
  void materialboardBroadcasts_onlyEverSendWhitelistedKeys() throws IOException {
    Set<String> whitelist = LiveSyncTopicClass.MATERIALBOARD.allowedSections();
    Pattern sendChanged =
        Pattern.compile(
            "sendChanged\\(\\s*(?:'materialboard'|\"materialboard\"|MATERIALBOARD_TOPIC)\\s*,"
                + "\\s*\\[([^\\]]*)\\]");
    int callsSeen = 0;
    for (String module :
        List.of(
            "/static/js/materialboerse.js",
            "/static/js/materialboerse-release.js",
            // REQ-MARKET-018: the Gesuche create/edit modal broadcasts the `requests` section key.
            "/static/js/materialgesuch-modal.js",
            "/static/js/inventory-materialboerse.js",
            // #1309: a stock-reducing inventory write clamps offers, so it pokes the board too.
            "/static/js/inventory-admin.js",
            "/static/js/inventory-my.js")) {
      Matcher matcher = sendChanged.matcher(readResource(module));
      while (matcher.find()) {
        callsSeen++;
        for (String rawKey : matcher.group(1).split(",")) {
          String key = rawKey.trim().replaceAll("^['\"]|['\"]$", "");
          if (key.isEmpty()) {
            continue;
          }
          assertThat(whitelist)
              .as("materialboard sendChanged key '%s' in %s must be whitelisted", key, module)
              .contains(key);
        }
      }
    }
    assertThat(callsSeen)
        .as("at least one materialboard sendChanged(...) call must exist (guards a silent rename)")
        .isPositive();
  }

  /**
   * Build-enforces the <em>broadcast</em> side of the three-mirror-points rule for the bank surface
   * (F5). Unlike the JS {@code *_SECTIONS} receiver maps (covered above), the bank publish side is
   * driven by {@code data-livesync="topic/sec,sec …"} HTML attributes evaluated in {@code
   * publishBankLiveSync} — so a stray out-of-whitelist section there is silently dropped by the
   * server with no red build. This scans every template for those attributes and asserts each
   * section key is inside its topic class's whitelist.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void dataLivesyncBroadcastKeys_areAllWithinTheirTopicClassWhitelist()
      throws IOException, URISyntaxException {
    Pattern attribute = Pattern.compile("data-livesync=\"([^\"]*)\"");
    // Anchor on a KNOWN template file rather than the bare `/templates` directory resource
    // (getResource on a directory is not portable); its parent is the real templates root on the
    // classpath, and Files.walk covers the subdirectories (admin/, fragments/, …).
    URL anchor = LiveSyncSectionMapParityTest.class.getResource("/templates/bank-grants.html");
    assertThat(anchor).as("/templates/bank-grants.html classpath resource").isNotNull();
    Path templatesRoot = Paths.get(anchor.toURI()).getParent();
    int entriesChecked = 0;
    try (Stream<Path> tree = Files.walk(templatesRoot)) {
      List<Path> templates =
          tree.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".html"))
              .toList();
      for (Path template : templates) {
        String html = Files.readString(template, StandardCharsets.UTF_8);
        Matcher matcher = attribute.matcher(html);
        while (matcher.find()) {
          for (String entry : matcher.group(1).trim().split("\\s+")) {
            if (entry.isEmpty()) {
              continue;
            }
            int slash = entry.indexOf('/');
            assertThat(slash)
                .as("data-livesync entry '%s' in %s has a topic/sections shape", entry, template)
                .isPositive();
            String topic = entry.substring(0, slash);
            LiveSyncTopicClass topicClass = resolveTopicClass(topic);
            assertThat(topicClass)
                .as("data-livesync topic '%s' in %s resolves to a known class", topic, template)
                .isNotNull();
            for (String section : entry.substring(slash + 1).split(",")) {
              if (section.isEmpty()) {
                continue;
              }
              entriesChecked++;
              assertThat(topicClass.allowedSections())
                  .as(
                      "data-livesync section '%s' (topic %s) in %s must be whitelisted",
                      section, topic, template)
                  .contains(section);
            }
          }
        }
      }
    }
    assertThat(entriesChecked)
        .as("at least one data-livesync section must be checked (guards a silent attribute rename)")
        .isPositive();
  }

  /**
   * Resolves a {@code data-livesync} topic token (its account-id placeholder stripped) to its
   * {@link LiveSyncTopicClass} by prefix and whether it carries an id segment — so {@code
   * bank:@account} resolves to the scoped {@link LiveSyncTopicClass#BANK_ACCOUNT} while the bare
   * {@code bank} resolves to {@link LiveSyncTopicClass#BANK_STAFF}.
   *
   * @param topic the topic token (e.g. {@code bank:@account}, {@code bank}, {@code orgunit-bank})
   * @return the matching class, or {@code null} if none matches
   */
  private static LiveSyncTopicClass resolveTopicClass(String topic) {
    int colon = topic.indexOf(':');
    String prefix = colon < 0 ? topic : topic.substring(0, colon);
    boolean scoped = colon >= 0;
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      if (topicClass.prefix().equals(prefix) && topicClass.scoped() == scoped) {
        return topicClass;
      }
    }
    return null;
  }

  /**
   * Extracts the top-level keys of a JS object literal assigned to {@code variableName} in the
   * given classpath resource.
   *
   * @param resource the classpath path of the JS module (e.g. {@code /static/js/mission-detail.js})
   * @param variableName the seam-map variable (e.g. {@code MISSION_SECTIONS})
   * @return the seam-map section keys
   * @throws IOException if the resource cannot be read
   */
  private static Set<String> seamMapKeys(String resource, String variableName) throws IOException {
    String js = readResource(resource);
    String objectLiteral = extractObjectLiteral(js, variableName);
    Set<String> keys = new LinkedHashSet<>();
    Matcher matcher = SEAM_ENTRY.matcher(objectLiteral);
    while (matcher.find()) {
      String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      if (key == null) {
        key = matcher.group(3);
      }
      keys.add(key);
    }
    assertThat(keys).as("no seam-map keys found for %s in %s", variableName, resource).isNotEmpty();
    return keys;
  }

  /**
   * Returns the brace-balanced object-literal body assigned to {@code variableName} (excluding the
   * outer braces), so nested objects do not terminate the scan early.
   *
   * <p>Anchored on the <em>assignment</em> — the first {@code <NAME> = &#123;} — not on the first
   * bare occurrence of the name (F6). The seam-map name typically appears first in a preceding
   * comment; a plain {@code indexOf(name)} then {@code indexOf('{')} would silently mis-scan the
   * wrong span if that comment ever contained a brace (e.g. an {@code operation:&#123;id&#125;}
   * example), extracting garbage instead of failing loudly.
   *
   * @param js the full module source
   * @param variableName the seam-map variable name
   * @return the object-literal body between its outer braces
   */
  private static String extractObjectLiteral(String js, String variableName) {
    Matcher declaration =
        Pattern.compile("\\b" + Pattern.quote(variableName) + "\\s*=\\s*\\{").matcher(js);
    assertThat(declaration.find())
        .as("%s = { assignment present (not just a comment mention)", variableName)
        .isTrue();
    int open = declaration.end() - 1; // the '{' the assignment regex matched
    int depth = 0;
    for (int i = open; i < js.length(); i++) {
      char c = js.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return js.substring(open + 1, i);
        }
      }
    }
    throw new AssertionError("Unbalanced braces for " + variableName);
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = LiveSyncSectionMapParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
