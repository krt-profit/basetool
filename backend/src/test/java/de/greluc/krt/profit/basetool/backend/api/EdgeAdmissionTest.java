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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the API vhost's admission (REQ-API-021, REQ-OPS-042, guard G-08): the committed map, its
 * include and the nightly probe table are the generator's output, and the map admits exactly the
 * frozen set, the two anonymous reads and the retired operations — no prefix, no other verb.
 */
class EdgeAdmissionTest {

  /** How many operations the vhost admits at least: the frozen set's floor. */
  private static final int ADMITTED_FLOOR = 246;

  /** How many requests the refusal matrix holds at least. */
  private static final int REFUSED_FLOOR = 1_500;

  /** Exchange and connected-apps paths no controller serves yet, probed under every verb. */
  private static final List<String> UNBUILT_EXCHANGE_PATHS =
      List.of(
          "/api/v1/exchange",
          "/api/v1/exchange/",
          "/api/v1/exchange/me/a-resource-not-built-yet",
          "/api/v1/exchange/v2/me/blueprints",
          "/api/v1/connected-apps/",
          "/api/v1/connected-apps/versekit/undo",
          "/api/v1/connected-apps/a-control-not-built-yet",
          "/api/v1/connected-apps/admin/a-page-not-built-yet");

  /**
   * Verifies that the committed admission map is exactly what the generator renders.
   *
   * @throws IOException if a file or resource cannot be read
   */
  @Test
  @DisplayName("the committed admission map is the generator's output")
  void theCommittedMapIsTheGeneratorsOutput() throws IOException {
    assertThat(committed(EdgeAdmission.MAP_FILE))
        .as(
            "%s differs from what the frozen set renders. Never edit it by hand: run ./gradlew"
                + " :backend:generateEdgeAdmission and review the diff",
            EdgeAdmission.MAP_FILE)
        .isEqualTo(EdgeAdmission.load().renderMap());
  }

  /**
   * Verifies that the committed server-level include is exactly what the generator renders.
   *
   * @throws IOException if the file cannot be read
   */
  @Test
  @DisplayName("the committed server include is the generator's output")
  void theCommittedIncludeIsTheGeneratorsOutput() throws IOException {
    assertThat(committed(EdgeAdmission.INCLUDE_FILE))
        .as(
            "%s differs from the generated refusal. It holds no admission of its own: run"
                + " ./gradlew :backend:generateEdgeAdmission",
            EdgeAdmission.INCLUDE_FILE)
        .isEqualTo(EdgeAdmission.renderInclude());
  }

  /**
   * Verifies that the nightly probe's API vhost table is exactly what the generator renders.
   *
   * @throws IOException if a file or resource cannot be read
   */
  @Test
  @DisplayName("the nightly probe's table is the generator's output")
  void theProbeTableIsTheGeneratorsOutput() throws IOException {
    String workflow = committed(EdgeAdmission.PROBE_WORKFLOW);
    assertThat(workflow)
        .as(
            "%s's API vhost table differs from what the frozen set renders: run ./gradlew"
                + " :backend:generateEdgeAdmission",
            EdgeAdmission.PROBE_WORKFLOW)
        .isEqualTo(EdgeAdmission.load().renderProbeWorkflow(workflow));
  }

  /**
   * Verifies that the model admits exactly the frozen set, the two anonymous reads and the retired
   * operations, in both directions, and that the committed map admits exactly the model.
   *
   * @throws IOException if a file or resource cannot be read
   */
  @Test
  @DisplayName("admitted equals frozen plus the two anonymous reads plus retired, both directions")
  void theAdmittedSetIsExactlyTheFrozenSetTheAnonymousReadsAndTheRetired() throws IOException {
    EdgeAdmission admission = EdgeAdmission.load();
    Set<String> expected = new TreeSet<>(ExternalContractTest.frozenOperations());
    expected.addAll(EdgeAdmission.ANONYMOUS_READS);
    expected.addAll(EdgeAdmission.retiredOperations());

    assertThat(new TreeSet<>(admission.keys())).isEqualTo(expected);
    assertThat(admission.operations())
        .as("the admitted set shrank below the frozen set's floor")
        .hasSizeGreaterThanOrEqualTo(ADMITTED_FLOOR);
    assertThat(admission.differencesFrom(committedEntries()))
        .as(
            "%s does not admit exactly the frozen set, the anonymous reads and the retired"
                + " operations",
            EdgeAdmission.MAP_FILE)
        .isEmpty();
  }

  /**
   * Verifies that the committed map admits every call of every app build the server still serves
   * (REQ-API-016).
   *
   * @throws IOException if a call list or the map cannot be read
   */
  @Test
  @DisplayName("every call of every served app build is admitted")
  void everyCallOfEveryServedAppBuildIsAdmitted() throws IOException {
    EdgeAdmission admission = EdgeAdmission.load();
    List<AppCallList.Release> releases =
        AppCallList.load(repositoryRoot().resolve("backend/src/test/resources/api/app-calls"));
    List<String> calls = new ArrayList<>();
    releases.forEach(release -> release.calls().forEach(call -> calls.add(call.key())));
    assertThat(new LinkedHashSet<>(calls)).hasSizeGreaterThanOrEqualTo(243);

    List<Pattern> entries = committedEntries();
    List<String> refused =
        admission.operations().stream()
            .filter(operation -> calls.contains(operation.key()))
            .filter(
                operation ->
                    entries.stream()
                        .noneMatch(
                            entry ->
                                entry
                                    .matcher(
                                        operation.method() + ":" + operation.samples().getFirst())
                                    .matches()))
            .map(EdgeAdmission.Operation::key)
            .toList();
    Set<String> unknown = new TreeSet<>(calls);
    unknown.removeAll(admission.keys());

    assertThat(unknown).as("app calls the admission does not know").isEmpty();
    assertThat(refused).as("app calls the committed map refuses").isEmpty();
  }

  /**
   * Verifies that no exchange or connected-apps path is admitted under any verb: the exchange layer
   * answers only the gateway and the member controls only the browser (REQ-XCH-001, ADR-0216).
   * Probes every such path the committed {@code openapi.json} and relay document hold, plus paths
   * no controller serves yet, against the committed map.
   *
   * @throws IOException if the map or the document cannot be read
   */
  @Test
  @DisplayName("no exchange or connected-apps path is admitted by the API vhost")
  void theExchangeStaysOffTheApiVhost() throws IOException {
    JsonNode document = CommittedOpenApi.merged();
    List<String> documented =
        document.get("paths").propertyNames().stream()
            .filter(EdgeAdmissionTest::isExchangeOrConnectionPath)
            .map(path -> path.replaceAll("\\{[A-Za-z]+}", EdgeAdmission.SAMPLE_UUID))
            .toList();
    assertThat(documented)
        .as("the committed documents hold no exchange path — have they or their prefixes moved?")
        .contains("/api/v1/exchange/me/blueprints", "/api/v1/connected-apps");

    Set<String> probes = new TreeSet<>(documented);
    probes.addAll(UNBUILT_EXCHANGE_PATHS);
    List<Pattern> entries = committedEntries();
    List<String> admitted = new ArrayList<>();
    for (String path : probes) {
      for (String verb : EdgeAdmission.VERBS) {
        if (entries.stream().anyMatch(entry -> entry.matcher(verb + ":" + path).matches())) {
          admitted.add(verb + " " + path);
        }
      }
    }

    assertThat(admitted)
        .as(
            "%s must never admit the exchange layer or the member's connection controls",
            EdgeAdmission.MAP_FILE)
        .isEmpty();
  }

  /**
   * Verifies that every request of the refusal matrix — other verbs on admitted paths, near-miss
   * spellings, the paths the removed prefix rules opened — is refused by the committed map, and
   * that the probe's rows agree with it.
   *
   * @throws IOException if a file or resource cannot be read
   */
  @Test
  @DisplayName("the committed map refuses every other verb, near-miss and former prefix path")
  void theCommittedMapRefusesEverythingElse() throws IOException {
    EdgeAdmission admission = EdgeAdmission.load();
    List<Pattern> entries = committedEntries();
    List<EdgeAdmission.Request> refused = admission.refusedRequests();
    assertThat(refused).hasSizeGreaterThanOrEqualTo(REFUSED_FLOOR);

    List<String> admittedAnyway =
        refused.stream()
            .filter(request -> matchesAny(entries, request))
            .map(EdgeAdmission.Request::row)
            .toList();
    assertThat(admittedAnyway).isEmpty();

    assertThat(admission.refusedRequests()).containsAll(EdgeAdmission.NEAR_MISSES);

    List<String> disagreeing = new ArrayList<>();
    for (EdgeAdmission.Request row :
        EdgeAdmission.parseProbeRows(committed(EdgeAdmission.PROBE_WORKFLOW))) {
      boolean admitted = matchesAny(entries, row);
      if (admitted == (row.status() == 404)) {
        disagreeing.add(row.row());
      }
    }
    assertThat(disagreeing)
        .as("probe rows whose expectation contradicts the committed map")
        .isEmpty();
  }

  /**
   * Proves the equality able to fail: a frozen operation dropped from the model leaves its map
   * entry unmatched, and a map that misses an operation or carries an extra admission is reported.
   *
   * @throws IOException if a file or resource cannot be read
   */
  @Test
  @DisplayName("a dropped frozen operation or an unfrozen admission is reported")
  void aDroppedFrozenOperationOrAnUnfrozenAdmissionIsReported() throws IOException {
    EdgeAdmission admission = EdgeAdmission.load();
    List<String> mapLines = committedLines(EdgeAdmission.MAP_FILE);
    String dropped = "GET /api/v1/hangar/my-ships";

    List<String> frozen = new ArrayList<>(ExternalContractTest.frozenOperations());
    assertThat(frozen.remove(dropped)).isTrue();
    EdgeAdmission smaller = EdgeAdmission.of(frozen, EdgeAdmission.retiredOperations(), document());
    assertThat(smaller.differencesFrom(EdgeAdmission.parseMap(mapLines)))
        .singleElement()
        .asString()
        .contains("/api/v1/hangar/my-ships")
        .contains("admits no operation");

    List<String> missing = new ArrayList<>(mapLines);
    assertThat(missing.removeIf(line -> line.contains("\"~^GET:/api/v1/hangar/my-ships$\"")))
        .isTrue();
    assertThat(admission.differencesFrom(EdgeAdmission.parseMap(missing)))
        .singleElement()
        .asString()
        .startsWith(dropped + " is admitted by the model, but no map entry admits it");

    List<String> widened = new ArrayList<>(mapLines);
    widened.add(widened.size() - 1, "  \"~^GET:/api/v1/me/layout$\" 1;");
    assertThat(admission.differencesFrom(EdgeAdmission.parseMap(widened)))
        .singleElement()
        .asString()
        .contains("/api/v1/me/layout")
        .contains("admits no operation");
  }

  /**
   * Proves the parser strict: a prefix, an unanchored, a case-insensitive, an exact-string, an
   * {@code if} rule or a widened default fails the parse instead of being skipped.
   */
  @Test
  @DisplayName("a map line the parser cannot read fails instead of being skipped")
  void aMapLineTheParserCannotReadFails() {
    List<String> unreadable =
        List.of(
            "  \"~^GET:/api/v1/terms/\" 1;",
            "  \"~GET:/api/v1/me/layout$\" 1;",
            "  \"~*^GET:/api/v1/users/me$\" 1;",
            "  \"GET:/api/v1/users/me\" 1;",
            "  \"~^GET:/api/v1/users/me$\" 2;",
            "  \"~^[A-Z]+:/api/v1/users/me$\" 1;",
            "  \"~^GET:/api/v1/users/me$\" $krt_other;",
            "  default 1;",
            "if ($uri ~ \"^/api/v1/me/\") { set $krt_api_admitted 1; }");

    for (String line : unreadable) {
      List<String> lines = List.of(EdgeAdmission.MAP_OPEN, EdgeAdmission.MAP_DEFAULT, line, "}");
      assertThatThrownBy(() -> EdgeAdmission.parseMap(lines))
          .as(line)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("line 3");
    }
  }

  /**
   * Proves the generator refuses what it cannot render narrowly: a placeholder without a reviewed
   * shape, a retired operation that is still frozen, and a malformed or repeated operation.
   */
  @Test
  @DisplayName("the generator refuses an unshaped placeholder, a frozen retiree and a duplicate")
  void theGeneratorRefusesWhatItCannotRenderNarrowly() {
    JsonNode document =
        fixture(
            "\"/api/v1/things/{name}\":{\"get\":{\"parameters\":"
                + "[{\"in\":\"path\",\"name\":\"name\",\"schema\":{\"type\":\"string\"}}]}},"
                + "\"/api/v1/things\":{\"get\":{}},");

    assertThatThrownBy(
            () -> EdgeAdmission.of(List.of("GET /api/v1/things/{name}"), List.of(), document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("{name}");
    assertThatThrownBy(
            () ->
                EdgeAdmission.of(
                    List.of("GET /api/v1/things"), List.of("GET /api/v1/things"), document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retired and admitted");
    assertThatThrownBy(
            () ->
                EdgeAdmission.of(
                    List.of("GET /api/v1/things", "GET /api/v1/things"), List.of(), document))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EdgeAdmission.of(List.of("GET /api/v1/missing"), List.of(), document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in openapi.json");
    assertThatThrownBy(
            () -> EdgeAdmission.of(List.of(), List.of("GET /api/v1/things/{slug}"), document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("{slug}");
  }

  /**
   * Verifies that a retired operation is admitted verb-scoped with a uuid shape for its ids, and
   * that the probe expects {@code 410} for it.
   */
  @Test
  @DisplayName("a retired operation is admitted verb-scoped and probed for 410")
  void aRetiredOperationIsAdmittedAndProbedForGone() {
    EdgeAdmission admission =
        EdgeAdmission.of(List.of(), List.of("DELETE /api/v1/widgets/{widgetId}"), fixture(""));
    String sample = "/api/v1/widgets/" + EdgeAdmission.SAMPLE_UUID;

    assertThat(admission.admits("DELETE", sample)).isTrue();
    assertThat(admission.admits("GET", sample)).isFalse();
    assertThat(admission.admits("DELETE", "/api/v1/widgets/not-a-uuid")).isFalse();
    assertThat(admission.probeRows())
        .contains(new EdgeAdmission.Request("DELETE", sample, 410))
        .contains(new EdgeAdmission.Request("GET", sample, 404));
  }

  /**
   * Builds a minimal API document that documents the two anonymous reads plus the given paths.
   *
   * @param paths further {@code "path":{…},} members, each followed by a comma, or empty
   * @return the parsed document
   */
  private static JsonNode fixture(String paths) {
    return new ObjectMapper()
        .readTree(
            "{\"paths\":{"
                + paths
                + "\"/api/v1/app/version-policy\":{\"get\":{}},"
                + "\"/api/v1/terms/document\":{\"get\":{}}}}");
  }

  /**
   * Tells whether a request matches any parsed entry.
   *
   * @param entries the parsed entries
   * @param request the request
   * @return {@code true} when an entry admits its verb and decoded path
   */
  private static boolean matchesAny(List<Pattern> entries, EdgeAdmission.Request request) {
    String subject = request.method() + ":" + EdgeAdmission.decodedPath(request.path());
    return entries.stream().anyMatch(entry -> entry.matcher(subject).matches());
  }

  /**
   * Tells whether a path belongs to the exchange layer, the member's connection controls or the
   * admin registry of connected applications.
   *
   * @param path a documented path
   * @return {@code true} for {@code /api/v1/exchange/**} and {@code /api/v1/connected-apps/**}
   */
  private static boolean isExchangeOrConnectionPath(String path) {
    return path.equals("/api/v1/exchange")
        || path.startsWith("/api/v1/exchange/")
        || path.equals("/api/v1/connected-apps")
        || path.startsWith("/api/v1/connected-apps/");
  }

  /**
   * Parses the committed admission map.
   *
   * @return its entries
   * @throws IOException if the map cannot be read
   */
  private static List<Pattern> committedEntries() throws IOException {
    return EdgeAdmission.parseMap(committedLines(EdgeAdmission.MAP_FILE));
  }

  /**
   * Reads the committed API document.
   *
   * @return the parsed document
   * @throws IOException if the resource cannot be read
   */
  private static JsonNode document() throws IOException {
    try (InputStream in = EdgeAdmissionTest.class.getResourceAsStream("/api/openapi.json")) {
      assertThat(in).isNotNull();
      return new ObjectMapper().readTree(in);
    }
  }

  /**
   * Reads a committed file with LF line endings, whatever the checkout's line-ending setting.
   *
   * @param relative the path relative to the repository root
   * @return the content
   * @throws IOException if the file cannot be read
   */
  static @NotNull String committed(@NotNull String relative) throws IOException {
    return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
  }

  /**
   * Reads a committed file's lines.
   *
   * @param relative the path relative to the repository root
   * @return the lines
   * @throws IOException if the file cannot be read
   */
  private static List<String> committedLines(String relative) throws IOException {
    return committed(relative).lines().toList();
  }

  /**
   * Walks up from the working directory to the directory that holds {@code settings.gradle.kts}.
   *
   * @return the repository root
   */
  static @NotNull Path repositoryRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("no settings.gradle.kts above the working directory").isNotNull();
    return dir;
  }
}
