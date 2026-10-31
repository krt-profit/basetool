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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.support.ClasspathAssets;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the REQ-FE-015 mirror rule: the {@link LiveSyncTopicClass} whitelists and the pages' JS
 * seam maps name exactly the same section keys.
 */
class LiveSyncSectionMapParityTest {

  /**
   * Matches one entry of a seam map — a plain, single- or double-quoted key followed by {@code
   * : { container}. Anchoring on {@code container} avoids matching unrelated nested objects.
   */
  private static final Pattern SEAM_ENTRY =
      Pattern.compile("(?:'([\\w-]+)'|\"([\\w-]+)\"|([\\w-]+))\\s*:\\s*\\{\\s*container");

  /** The one {@code inventory_all} section the Lager pages render; the levels tab adds its own. */
  private static final String LAGER_STOCK_SECTION = "stock";

  @Test
  void missionSeamMap_matchesTheMissionTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/mission/mission-detail.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in mission-detail.js vs LiveSyncTopicClass.MISSION whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MISSION.allowedSections());
  }

  @Test
  void exchangeClientsSeamMap_matchesTheAdminExchangeClientsTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/exchange/admin-exchange-clients.js", "sections");
    assertThat(jsKeys)
        .as(
            "sections keys in admin-exchange-clients.js vs"
                + " LiveSyncTopicClass.EXCHANGE_CLIENTS whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.EXCHANGE_CLIENTS.allowedSections());
  }

  @Test
  void exchangeClientsPage_broadcastsEveryWriteWithWhitelistedKeys() throws IOException {
    String js = readResource("/static/js/exchange/admin-exchange-clients.js");
    assertThat(js)
        .as("a bulk undo start must refresh the peers' client table and run list")
        .contains("clients.refresh(['registry', 'undoRuns'])")
        .as("a registry write must refresh the peers' client table")
        .contains("refresh: ['registry']")
        .as("the page must receive the peers' changes")
        .contains("topic: 'exchange-clients'");
  }

  @Test
  void supplyPages_coverExactlyTheSupplyNeedsWhitelist() throws IOException {
    Set<String> sections = new LinkedHashSet<>();
    String needs = readResource("/static/js/supply/supply-needs.js");
    String mine = readResource("/static/js/supply/supply-mine.js");
    String stats = readResource("/static/js/supply/supply-stats.js");
    String common = readResource("/static/js/supply/supply-common.js");
    assertThat(needs).contains("section: 'needs'");
    sections.add("needs");
    assertThat(mine).contains("section: 'mine'");
    sections.add("mine");
    assertThat(stats).contains("topic: 'supply-needs'").contains("stats: { container");
    sections.add("stats");
    assertThat(common)
        .as("a donation write must refresh the peers' statistics")
        .contains("publish(STATS_TOPIC, ['stats'])");

    assertThat(sections)
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.SUPPLY_NEEDS.allowedSections());
  }

  @Test
  void hangarSeamMap_matchesTheOwnHangarTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/hangar/hangar.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in hangar.js vs LiveSyncTopicClass.HANGAR_OWN whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.HANGAR_OWN.allowedSections());
  }

  @Test
  void memberHangarSeamMap_matchesTheOwnHangarTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/hangar/member-hangar.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in member-hangar.js vs LiveSyncTopicClass.HANGAR_OWN" + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.HANGAR_OWN.allowedSections());
  }

  @Test
  void unitHangarSeamMap_matchesBothUnitHangarTopicWhitelists() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/hangar/hangar-squadron.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in hangar-squadron.js vs LiveSyncTopicClass.HANGAR_UNIT whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.HANGAR_UNIT.allowedSections());
    assertThat(jsKeys)
        .as("sections keys in hangar-squadron.js vs LiveSyncTopicClass.HANGAR_UNITS whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.HANGAR_UNITS.allowedSections());
  }

  @Test
  void blueprintSeamMap_matchesTheOwnBlueprintsTopicWhitelist() throws IOException {
    Set<String> jsKeys =
        seamMapKeys("/static/js/personalinventory/personal-inventory-blueprints.js", "sections");
    assertThat(jsKeys)
        .as(
            "sections keys in personal-inventory-blueprints.js vs"
                + " LiveSyncTopicClass.BLUEPRINTS_OWN whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BLUEPRINTS_OWN.allowedSections());
  }

  @Test
  void operationSeamMap_matchesTheOperationTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/operation/operation-detail.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in operation-detail.js vs LiveSyncTopicClass.OPERATION" + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.OPERATION.allowedSections());
  }

  @Test
  void ordersQueueSeamMaps_partitionTheOrdersQueueTopicWhitelist() throws IOException {
    Set<String> queueKeys = seamMapKeys("/static/js/joborder/orders-index.js", "sections");
    Set<String> demandKeys =
        seamMapKeys("/static/js/joborder/orders-material-demand.js", "sections");
    Set<String> whitelist = LiveSyncTopicClass.ORDERS_QUEUE.allowedSections();

    assertThat(queueKeys)
        .as("sections keys in orders-index.js vs the ORDERS_QUEUE whitelist")
        .isSubsetOf(whitelist);
    assertThat(demandKeys)
        .as("sections keys in orders-material-demand.js vs the ORDERS_QUEUE whitelist")
        .isSubsetOf(whitelist);

    Set<String> union = new LinkedHashSet<>(queueKeys);
    union.addAll(demandKeys);
    assertThat(union)
        .as("every ORDERS_QUEUE section is rendered by exactly one of the two pages")
        .containsExactlyInAnyOrderElementsOf(whitelist);
  }

  @Test
  void orderDetailCrossPublish_keepsTheDemandOverviewInSync() throws IOException {
    String js = readResource("/static/js/joborder/orders-detail.js");
    assertSendChangedKeysWhitelisted(
        js, "'orders'", LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
    assertThat(js)
        .as("the order detail must poke both folds of the orders room on a queue-visible mutation")
        .contains("publish('orders', ['queue', 'demand'])");
  }

  @Test
  void inventoryPages_pokeTheDemandOverviewWhenOrderLinkedStockChanges() throws IOException {
    for (String module : new String[] {"/static/js/inventory/inventory-common.js"}) {
      String js = readResource(module);
      assertSendChangedKeysWhitelisted(
          js, "'orders'", LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
      assertThat(js)
          .as(
              "%s must poke the cross-order demand overview after an order-linked stock write",
              module)
          .contains("publish('orders', ['demand'])");
    }
  }

  @Test
  void orderDetailSeamMap_matchesTheOrderTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/joborder/orders-detail.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in orders-detail.js vs LiveSyncTopicClass.ORDER whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.ORDER.allowedSections());
  }

  @Test
  void refineryOrderSeamMap_matchesTheRefineryOrderTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/refinery/refinery-orders-details.js", "sections");
    assertThat(jsKeys)
        .as(
            "sections keys in refinery-orders-details.js vs"
                + " LiveSyncTopicClass.REFINERY_ORDER whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.REFINERY_ORDER.allowedSections());
  }

  @Test
  void refineryOrderCrossPublishes_onlyEverSendWhitelistedKeys() throws IOException {
    String js = readResource("/static/js/refinery/refinery-orders-details.js");
    assertSendChangedKeysWhitelisted(
        js, "`order:\\$\\{jobOrderId\\}`", LiveSyncTopicClass.ORDER.allowedSections());
    assertThat(js)
        .as("the refinery detail page must not duplicate the server-side inventory/queue publishes")
        .doesNotContain("sendChanged('inventory'")
        .doesNotContain("sendChanged('refinery'")
        .doesNotContain("publish('inventory'")
        .doesNotContain("publish('refinery'");
  }

  /**
   * Asserts that every section key of each {@code sendChanged(<topicExpression>, [...])} call in
   * {@code js} is whitelisted, and that at least one such call exists.
   *
   * @param js the module source to scan
   * @param topicExpressionRegex the regex matching the call's topic argument as written
   * @param whitelist the topic class's accepted section keys
   */
  private static void assertSendChangedKeysWhitelisted(
      String js, String topicExpressionRegex, Set<String> whitelist) {
    Matcher matcher =
        Pattern.compile(
                "(?:sendChanged|publish)\\(\\s*"
                    + topicExpressionRegex
                    + "\\s*,\\s*\\[([^\\]]*)\\]")
            .matcher(js);
    int callsSeen = 0;
    while (matcher.find()) {
      callsSeen++;
      for (String rawKey : matcher.group(1).split(",")) {
        String key = rawKey.trim().replaceAll("^['\"]|['\"]$", "");
        if (key.isEmpty()) {
          continue;
        }
        assertThat(whitelist)
            .as(
                "cross-published sendChanged key '%s' (topic %s) must be whitelisted",
                key, topicExpressionRegex)
            .contains(key);
      }
    }
    assertThat(callsSeen)
        .as("at least one sendChanged(%s, …) call must exist", topicExpressionRegex)
        .isPositive();
  }

  @Test
  void materialCollectionSeamMap_isASubsetOfTheOrderTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/joborder/material-collection.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in material-collection.js vs" + " LiveSyncTopicClass.ORDER whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDER.allowedSections());
  }

  @Test
  void itemCollectionSeamMap_isASubsetOfTheOrderTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/joborder/item-collection.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in item-collection.js vs LiveSyncTopicClass.ORDER" + " whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDER.allowedSections());
  }

  @Test
  void inventoryInputOrderSeamMap_isASubsetOfTheOrdersQueueWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-input.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in inventory-input.js vs the ORDERS_QUEUE" + " whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
  }

  @Test
  void inventoryPageOrderSeamMaps_areASubsetOfTheOrdersQueueWhitelist() throws IOException {
    assertThat(seamMapKeys("/static/js/inventory/inventory-admin.js", "sections", 1))
        .as("sections keys in inventory-admin.js vs the ORDERS_QUEUE whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
    assertThat(seamMapKeys("/static/js/inventory/inventory-my.js", "sections", 1))
        .as("sections keys in inventory-my.js vs the ORDERS_QUEUE whitelist")
        .isSubsetOf(LiveSyncTopicClass.ORDERS_QUEUE.allowedSections());
  }

  @Test
  void stockLevelSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/stock-levels.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in stock-levels.js vs LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.INVENTORY_ALL.allowedSections());
  }

  @Test
  void inventoryAllSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-admin.js", "sections", 0);
    assertThat(jsKeys)
        .as(
            "sections keys in inventory-admin.js vs LiveSyncTopicClass.INVENTORY_ALL"
                + " whitelist")
        .containsExactly(LAGER_STOCK_SECTION);
  }

  @Test
  void inventoryMySeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-my.js", "sections", 0);
    assertThat(jsKeys)
        .as("sections keys in inventory-my.js vs LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactly(LAGER_STOCK_SECTION);
  }

  @Test
  void inventoryIndexSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-index.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in inventory-index.js vs" + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactly(LAGER_STOCK_SECTION);
  }

  @Test
  void inventoryMaterialSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-material.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in inventory-material.js vs" + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactly(LAGER_STOCK_SECTION);
  }

  @Test
  void inventoryGameItemSeamMap_matchesTheInventoryAllTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/inventory/inventory-game-item.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in inventory-game-item.js vs" + " LiveSyncTopicClass.INVENTORY_ALL")
        .containsExactly(LAGER_STOCK_SECTION);
  }

  @Test
  void missionsListSeamMap_matchesTheMissionsListTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/mission/missions.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in missions.js vs LiveSyncTopicClass.MISSIONS_LIST whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MISSIONS_LIST.allowedSections());
  }

  @Test
  void refinerySeamMap_matchesTheRefineryTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/refinery/refinery-orders-index.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in refinery-orders-index.js vs LiveSyncTopicClass.REFINERY")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.REFINERY.allowedSections());
  }

  @Test
  void membersSeamMap_matchesTheMembersTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/identity/members.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in members.js vs LiveSyncTopicClass.MEMBERS whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MEMBERS.allowedSections());
  }

  @Test
  void orgStructureSeamMaps_partitionTheOrgStructureTopicWhitelist() throws IOException {
    Set<String> editorKeys = seamMapKeys("/static/js/orgunit/admin-org-structure.js", "sections");
    Set<String> chartKeys = seamMapKeys("/static/js/orgchart/org-chart.js", "sections");
    Set<String> whitelist = LiveSyncTopicClass.ORG_STRUCTURE.allowedSections();

    assertThat(editorKeys)
        .as("sections keys in admin-org-structure.js vs the ORG_STRUCTURE whitelist")
        .isSubsetOf(whitelist);
    assertThat(chartKeys)
        .as("sections keys in org-chart.js vs the ORG_STRUCTURE whitelist")
        .isSubsetOf(whitelist);

    Set<String> union = new LinkedHashSet<>(editorKeys);
    union.addAll(chartKeys);
    assertThat(union)
        .as("every ORG_STRUCTURE section is rendered by exactly one of the two pages")
        .containsExactlyInAnyOrderElementsOf(whitelist);
  }

  @Test
  void orgStructureCrossPublishConstants_nameTheOtherPagesSection() throws IOException {
    String editorJs = readResource("/static/js/orgunit/admin-org-structure.js");
    String chartJs = readResource("/static/js/orgchart/org-chart.js");
    Set<String> whitelist = LiveSyncTopicClass.ORG_STRUCTURE.allowedSections();

    Matcher alsoBroadcast =
        Pattern.compile("alsoBroadcast:\\s*\\['([\\w-]+)'\\]").matcher(editorJs);
    assertThat(alsoBroadcast.find()).as("alsoBroadcast: ['<key>'] present").isTrue();
    assertThat(whitelist)
        .as("alsoBroadcast key in admin-org-structure.js is a whitelisted key")
        .contains(alsoBroadcast.group(1));
    assertThat(whitelist)
        .as("ORG_CHART_UNITS_SECTION in org-chart.js is a whitelisted key")
        .contains(constantValue(chartJs, "ORG_CHART_UNITS_SECTION"));
  }

  /**
   * Reads the string literal assigned to a top-level {@code const <name> = '<value>';} in a JS
   * module.
   *
   * @param js the module source
   * @param constantName the constant to read
   * @return the assigned string value
   */
  private static String constantValue(String js, String constantName) {
    Matcher matcher =
        Pattern.compile("\\b" + Pattern.quote(constantName) + "\\s*=\\s*['\"]([\\w-]+)['\"]")
            .matcher(js);
    assertThat(matcher.find()).as("%s = '<value>' assignment present", constantName).isTrue();
    return matcher.group(1);
  }

  @Test
  void bankStaffAccountSeamMap_matchesTheBankAccountTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank/bank-livesync.js", "sections", 2);
    assertThat(jsKeys)
        .as("sections keys in bank-livesync.js vs LiveSyncTopicClass.BANK_ACCOUNT" + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_ACCOUNT.allowedSections());
  }

  @Test
  void orgUnitAccountSeamMap_matchesTheBankAccountTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank/bank-livesync.js", "sections", 3);
    assertThat(jsKeys)
        .as("sections keys in bank-livesync.js vs LiveSyncTopicClass.BANK_ACCOUNT" + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_ACCOUNT.allowedSections());
  }

  @Test
  void bankStaffSeamMap_matchesTheBankStaffTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank/bank-livesync.js", "sections", 0);
    assertThat(jsKeys)
        .as("sections keys in bank-livesync.js vs LiveSyncTopicClass.BANK_STAFF" + " whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.BANK_STAFF.allowedSections());
  }

  @Test
  void orgUnitBankSeamMap_matchesTheOrgUnitBankTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/bank/bank-livesync.js", "sections", 1);
    assertThat(jsKeys)
        .as("sections keys in bank-livesync.js vs LiveSyncTopicClass.ORGUNIT_BANK" + " whitelist")
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

  @Test
  void materialboardSeamMap_matchesTheMaterialboardTopicWhitelist() throws IOException {
    Set<String> jsKeys = seamMapKeys("/static/js/materialexchange/materialboerse.js", "sections");
    assertThat(jsKeys)
        .as("sections keys in materialboerse.js vs LiveSyncTopicClass.MATERIALBOARD whitelist")
        .containsExactlyInAnyOrderElementsOf(LiveSyncTopicClass.MATERIALBOARD.allowedSections());
  }

  /**
   * Verifies that every Materialbörse {@code sendChanged} call sends only keys in {@link
   * LiveSyncTopicClass#MATERIALBOARD}'s whitelist.
   */
  @Test
  void materialboardBroadcasts_onlyEverSendWhitelistedKeys() throws IOException {
    Set<String> whitelist = LiveSyncTopicClass.MATERIALBOARD.allowedSections();
    Pattern sendChanged =
        Pattern.compile(
            "(?:sendChanged|publish)\\(\\s*(?:'materialboard'|\"materialboard\"|MATERIALBOARD_TOPIC)\\s*,"
                + "\\s*\\[([^\\]]*)\\]");
    int callsSeen = 0;
    for (String module :
        List.of(
            "/static/js/materialexchange/materialboerse.js",
            "/static/js/materialexchange/materialboerse-release.js",
            "/static/js/materialexchange/materialgesuch-modal.js",
            "/static/js/inventory/inventory-materialboerse.js",
            "/static/js/inventory/inventory-common.js")) {
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
   * Verifies that every section key in a template's {@code data-livesync="topic/sec,…"} attribute
   * is inside its topic class's whitelist.
   *
   * @throws IOException if a template cannot be read
   * @throws URISyntaxException if the templates classpath root cannot be resolved
   */
  @Test
  void dataLivesyncBroadcastKeys_areAllWithinTheirTopicClassWhitelist()
      throws IOException, URISyntaxException {
    Pattern attribute = Pattern.compile("data-livesync=\"([^\"]*)\"");
    URL anchor = LiveSyncSectionMapParityTest.class.getResource("/templates/bank/bank-grants.html");
    assertThat(anchor).as("/templates/bank/bank-grants.html classpath resource").isNotNull();
    Path templatesRoot = ClasspathAssets.rootOf(anchor);
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

  private static final Pattern JOINS_ROOM = Pattern.compile("(?m)^\\s+topic(?::|\\(\\))");

  @Test
  void everyDeclaredTopicClassNamesAnExistingClassAndEveryLiveSeamDeclaresOne() throws IOException {
    Set<String> classes = new LinkedHashSet<>();
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      classes.add(topicClass.name());
    }
    Set<String> declaredClasses = new LinkedHashSet<>();
    Set<String> seamsWithoutTopicClass = new LinkedHashSet<>();
    Pattern declaration = Pattern.compile("\\btopicClass:\\s*'([A-Z_]+)'");
    Path scripts = ClasspathAssets.scripts();
    try (Stream<Path> files = Files.walk(scripts)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".js")).toList()) {
        String js = Files.readString(file, StandardCharsets.UTF_8);
        if (!js.contains("krtPageSections.create(")) {
          continue;
        }
        Matcher matcher = declaration.matcher(js);
        while (matcher.find()) {
          declaredClasses.add(matcher.group(1));
        }
        if (!js.contains("topicClass:") && JOINS_ROOM.matcher(js).find()) {
          seamsWithoutTopicClass.add(scripts.relativize(file).toString().replace('\\', '/'));
        }
      }
    }

    assertThat(classes)
        .as("every declared topicClass names a LiveSyncTopicClass")
        .containsAll(declaredClasses);
    assertThat(seamsWithoutTopicClass)
        .as("a page module that joins a live-sync room declares its topicClass")
        .isEmpty();
  }

  /**
   * Resolves a {@code data-livesync} topic token to its {@link LiveSyncTopicClass} by prefix and
   * whether it carries an id segment, e.g. {@code bank:@account} to {@link
   * LiveSyncTopicClass#BANK_ACCOUNT} and {@code bank} to {@link LiveSyncTopicClass#BANK_STAFF}.
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
   * @param resource the classpath path of the JS module (e.g. {@code
   *     /static/js/mission/mission-detail.js})
   * @param variableName the seam-map variable (e.g. {@code MISSION_SECTIONS})
   * @return the seam-map section keys
   * @throws IOException if the resource cannot be read
   */
  private static Set<String> seamMapKeys(String resource, String variableName) throws IOException {
    return seamMapKeys(resource, variableName, 0);
  }

  /**
   * Reads the section keys of the {@code occurrence}-th (zero-based) declaration of the seam map in
   * a module that declares several.
   *
   * @param resource the classpath resource of the module
   * @param variableName the seam-map variable or property name
   * @param occurrence which declaration of that name to read
   * @return the section keys in declaration order
   * @throws IOException if the resource cannot be read
   */
  private static Set<String> seamMapKeys(String resource, String variableName, int occurrence)
      throws IOException {
    String js = readResource(resource);
    String objectLiteral = extractObjectLiteral(js, variableName, occurrence);
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
   * Returns the brace-balanced object-literal body assigned to {@code variableName}, excluding the
   * outer braces, anchored on the {@code <NAME> = &#123;} assignment or the {@code <NAME>: &#123;}
   * property.
   *
   * @param js the full module source
   * @param variableName the seam-map variable name
   * @return the object-literal body between its outer braces
   */
  private static String extractObjectLiteral(String js, String variableName, int occurrence) {
    Matcher declaration =
        Pattern.compile("\\b" + Pattern.quote(variableName) + "\\s*[=:]\\s*\\{").matcher(js);
    boolean found = false;
    for (int i = 0; i <= occurrence; i++) {
      found = declaration.find();
    }
    assertThat(found)
        .as(
            "%s = { or %s: { declaration present (not just a comment mention)",
            variableName, variableName)
        .isTrue();
    int open = declaration.end() - 1;
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
