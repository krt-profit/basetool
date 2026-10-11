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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Holds the committed REST API cut map ({@code api/api-cut-map.txt}, plan §7.9) to the committed
 * OpenAPI document: every wave is either entirely still in place or entirely moved, the map names
 * the contract tier and the app's call of every operation as the document and the app call list do,
 * and no operation under a legacy root escapes the map.
 *
 * <p>A map line reads {@code <wave> <VERB> <old path> <new path|-> <tier> <app|->}; {@code -} as
 * the new path is a deletion.
 */
class ApiCutMapTest {

  /** The only targets that are not new: a fold into an existing or a shared collection. */
  private static final Set<String> FOLDS = Set.of("GET /api/v1/missions", "GET /api/v1/game-items");

  /** Roots an operation leaves with the cut; one still documented there must be in the map. */
  private static final Pattern LEGACY_ROOT =
      Pattern.compile(
          "^/api/v[12]/(admin/.*|org-hierarchy/.*|org-units/bank/.*|finance-entries(/.*)?"
              + "|material-requests(/.*)?|notification-rules(/.*)?|sync-reports(/.*)?"
              + "|announcement(/admin)?|leitung/.*|me/(active-org-unit|org-units)|system/ping"
              + "|hangar/users/.*"
              + "|hangar/import/fleetview|users/search-bank(/.*)?|.*/slim|missions/search"
              + "|users/[^/]+/memberships(/detail)?|inventory/mission/.*"
              + "|refinery-orders/(mission/.*|locations/[^/]+/yields)"
              + "|orders/[^/]+/inventory/.*|missions/[^/]+/participants/add"
              + "|users/me/(payout-preference|blueprint-sharing|read-announcement/.*"
              + "|memberships|pickable-org-units|org-unit-ids)"
              + "|orders/item-catalog.*|inventory/item-catalog|kommando-groups/.*)$");

  /** The verbs the document and the map use. */
  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** One line of the map. */
  private record Entry(
      int wave, String verb, String oldPath, String newPath, String tier, boolean app) {

    /**
     * Whether the entry deletes the operation.
     *
     * @return {@code true} when the new path is {@code -}
     */
    boolean deleted() {
      return newPath == null;
    }

    /**
     * The key the document is searched by.
     *
     * @return {@code VERB path} of the old operation
     */
    String oldKey() {
      return verb + " " + oldPath;
    }
  }

  /** Every line of the map parses into its six fields, in a known wave, tier and verb. */
  @Test
  @DisplayName("the map parses and names only known waves, verbs and tiers")
  void theMapParses() throws IOException {
    List<Entry> entries = map();

    assertThat(entries).hasSizeGreaterThanOrEqualTo(184);
    assertThat(entries).allSatisfy(e -> assertThat(e.wave()).isBetween(1, 6));
    assertThat(entries).allSatisfy(e -> assertThat(VERBS).contains(e.verb()));
    assertThat(entries).allSatisfy(e -> assertThat(e.tier()).isIn("T1", "T2"));
    assertThat(entries).allSatisfy(e -> assertThat(e.oldPath()).startsWith("/api/v"));
    assertThat(entries)
        .allSatisfy(e -> assertThat(e.deleted() || e.newPath().startsWith("/api/v1/")).isTrue());
    assertThat(entries).extracting(Entry::oldKey).doesNotHaveDuplicates();
  }

  /** A wave that moved leaves nothing of itself behind, and one that did not is whole. */
  @Test
  @DisplayName("every wave is entirely in place or entirely moved")
  void everyWaveIsAllOrNothing() throws IOException {
    List<Entry> entries = map();

    assertThat(entries.stream().map(Entry::wave).distinct()).hasSize(6);
    assertThat(mixedWaves(entries, documentedOperations())).isEmpty();
  }

  /**
   * The wave check and the escape check fail on a planted half-moved wave and a planted leftover.
   */
  @Test
  @DisplayName("the wave and escape checks fail on planted documents")
  void theChecksCanFail() {
    List<Entry> entries =
        List.of(
            new Entry(1, "GET", "/api/v1/admin/a", "/api/v1/x/admin/a", "T2", false),
            new Entry(1, "GET", "/api/v1/admin/b", "/api/v1/x/admin/b", "T2", false));

    assertThat(mixedWaves(entries, Set.of("GET /api/v1/admin/a"))).containsExactly(1);
    assertThat(mixedWaves(entries, Set.of("GET /api/v1/admin/a", "GET /api/v1/admin/b"))).isEmpty();
    assertThat(mixedWaves(entries, Set.of())).isEmpty();
    assertThat(escapes(Set.of("GET /api/v1/admin/c"), Set.of("GET /api/v1/admin/a")))
        .containsExactly("GET /api/v1/admin/c");
    assertThat(escapes(Set.of("GET /api/v1/admin/a"), Set.of("GET /api/v1/admin/a"))).isEmpty();
  }

  /**
   * An operation still at its old path has the tier and the app call the map says; one that moved
   * stands at its new path with the tier it had.
   */
  @Test
  @DisplayName("the map agrees with the document on tier and the app's calls")
  void theMapAgreesWithTheDocument() throws IOException {
    JsonNode paths = CommittedOpenApi.published().path("paths");
    Set<String> appCalls = appCalls();
    List<String> disagreements = new ArrayList<>();
    for (Entry entry : map()) {
      JsonNode operation = paths.path(entry.oldPath()).path(entry.verb().toLowerCase());
      if (!operation.isMissingNode()) {
        if (!entry.tier().equals(operation.path("x-contract-tier").asString(""))) {
          disagreements.add(entry.oldKey() + " tier " + operation.path("x-contract-tier"));
        }
        if (entry.app() != appCalls.contains(entry.oldKey())) {
          disagreements.add(entry.oldKey() + " app flag");
        }
        continue;
      }
      if (entry.deleted()) {
        continue;
      }
      JsonNode moved = paths.path(entry.newPath()).path(entry.verb().toLowerCase());
      if (moved.isMissingNode()
          || !entry.tier().equals(moved.path("x-contract-tier").asString(""))) {
        disagreements.add(entry.verb() + " " + entry.newPath() + " is not the moved operation");
      }
    }

    assertThat(disagreements).isEmpty();
  }

  /** A target that does not exist yet is free, except the two named folds. */
  @Test
  @DisplayName("an unmoved wave's targets are free except the two named folds")
  void targetsAreFreeExceptTheFolds() throws IOException {
    Set<String> documented = documentedOperations();
    List<String> collisions = new ArrayList<>();
    for (Entry entry : map()) {
      if (entry.deleted() || !documented.contains(entry.oldKey())) {
        continue;
      }
      String target = entry.verb() + " " + entry.newPath();
      if (documented.contains(target) && !FOLDS.contains(target)) {
        collisions.add(target);
      }
    }

    assertThat(collisions).isEmpty();
  }

  /** An operation under a legacy root is in the map, so the cut cannot leave one behind. */
  @Test
  @DisplayName("no documented operation under a legacy root is missing from the map")
  void noLegacyOperationEscapesTheMap() throws IOException {
    Set<String> mapped = new TreeSet<>();
    map().forEach(e -> mapped.add(e.oldKey()));

    assertThat(escapes(documentedOperations(), mapped)).isEmpty();
  }

  /**
   * Finds the waves whose operations are partly moved.
   *
   * @param entries the map
   * @param documented the operations the document holds
   * @return the numbers of the waves with both a moved and an unmoved operation
   */
  private static Set<Integer> mixedWaves(List<Entry> entries, Set<String> documented) {
    Map<Integer, Set<Boolean>> states = new LinkedHashMap<>();
    for (Entry entry : entries) {
      states
          .computeIfAbsent(entry.wave(), _ -> new HashSet<>())
          .add(documented.contains(entry.oldKey()));
    }
    Set<Integer> mixed = new TreeSet<>();
    states.forEach(
        (wave, state) -> {
          if (state.size() > 1) {
            mixed.add(wave);
          }
        });
    return mixed;
  }

  /**
   * Finds the operations under a legacy root that the map does not list.
   *
   * @param documented the operations the document holds
   * @param mapped the old operations of the map
   * @return the escaped operations
   */
  private static List<String> escapes(Set<String> documented, Set<String> mapped) {
    List<String> escaped = new ArrayList<>();
    for (String operation : documented) {
      String path = operation.substring(operation.indexOf(' ') + 1);
      if (LEGACY_ROOT.matcher(path).matches() && !mapped.contains(operation)) {
        escaped.add(operation);
      }
    }
    return escaped;
  }

  /**
   * Reads the committed map.
   *
   * @return its entries in file order
   * @throws IOException if the resource is unreadable
   */
  private static List<Entry> map() throws IOException {
    List<Entry> entries = new ArrayList<>();
    for (String line : lines("/api/api-cut-map.txt")) {
      String[] f = line.split(" ");
      assertThat(f).as("fields of '%s'", line).hasSize(6);
      entries.add(
          new Entry(
              Integer.parseInt(f[0]),
              f[1],
              f[2],
              "-".equals(f[3]) ? null : f[3],
              f[4],
              "app".equals(f[5])));
    }
    return entries;
  }

  /**
   * Reads the operations the app calls today.
   *
   * @return {@code VERB path} of every call in the last released list
   * @throws IOException if the resource is unreadable
   */
  private static Set<String> appCalls() throws IOException {
    Set<String> calls = new HashSet<>();
    for (String line : lines("/api/api-cut-app-calls-baseline.txt")) {
      String[] f = line.split(" ");
      calls.add(f[0] + " " + f[1]);
    }
    return calls;
  }

  /**
   * Lists the committed document's operations.
   *
   * @return {@code VERB path} for every operation
   */
  private static Set<String> documentedOperations() {
    Set<String> operations = new TreeSet<>();
    for (Map.Entry<String, JsonNode> path :
        CommittedOpenApi.published().path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        String verb = operation.getKey().toUpperCase();
        if (VERBS.contains(verb)) {
          operations.add(verb + " " + path.getKey());
        }
      }
    }
    return operations;
  }

  /**
   * Reads the non-blank lines of a classpath resource.
   *
   * @param resource the resource path
   * @return its lines
   * @throws IOException if the resource is missing or unreadable
   */
  private static List<String> lines(String resource) throws IOException {
    try (InputStream in = ApiCutMapTest.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank())
          .toList();
    }
  }
}
