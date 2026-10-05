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

package de.greluc.krt.profit.basetool.frontend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncTopicClass;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails the build when the frontend calls a backend operation the committed {@code openapi.json}
 * does not have, or when a live-sync subscribe probe names anything but an existing {@code GET}
 * (REQ-FE-028).
 *
 * <p>{@link BackendCallScanner} folds every call site into a verb and a path template; the few it
 * cannot fold are listed in {@link #UNRESOLVED_EXCEPTIONS} with the check that covers them instead.
 * The catalogue URIs, the anonymous terms read and the live-sync probe templates are values, not
 * call sites, and are read from their declarations.
 */
class BackendCallExistenceTest {

  /** The number of call sites resolved when the guard was introduced; a smaller scan is broken. */
  private static final int MIN_RESOLVED_CALL_SITES = 546;

  /** The number of live-sync probe templates when the guard was introduced. */
  private static final int MIN_LIVE_SYNC_PROBES = 7;

  /**
   * The call sites the scanner cannot fold, keyed {@code Class#method VERB expression}, each with
   * the reason and the check that covers it instead. The set must match the scan exactly.
   */
  private static final Map<String, String> UNRESOLVED_EXCEPTIONS =
      Map.of(
          "BackendSideChannels#probeStatus GET uri",
          "a LiveSyncTopicClass probe template with the topic id filled in; covered by"
              + " everyLiveSyncProbeTemplateIsAnExistingGet",
          "BackendSideChannels#probeBody GET path",
          "the LiveSyncTopicClass capability probe template; covered by"
              + " everyLiveSyncProbeTemplateIsAnExistingGet");

  private static BackendCallScanner.Result scan;
  private static BackendOperations operations;

  @BeforeAll
  static void scanTheFrontend() {
    scan = BackendCallScanner.scanDirectory(frontendMainSources());
    operations = BackendOperations.committed();
  }

  @Test
  void everyResolvedBackendCallNamesAnExistingOperation() {
    assertThat(scan.callSites())
        .as("the scan resolved fewer call sites than when the guard was introduced; it is broken")
        .isGreaterThanOrEqualTo(MIN_RESOLVED_CALL_SITES);

    List<String> missing = missing(scan.calls(), operations);

    assertThat(missing)
        .as(
            "frontend calls to backend operations that the committed openapi.json does not have;"
                + " fix the path or the verb, or regenerate openapi.json if the backend changed")
        .isEmpty();
  }

  @Test
  void everyExecuteCallDeclaresTheRequestItSends() {
    assertThat(scan.inconsistencies())
        .as("backendApiClient.execute(…) sites whose declared verb or URI is not what they send")
        .isEmpty();
  }

  @Test
  void everyUnresolvedCallSiteIsAReviewedException() {
    List<String> unresolved =
        scan.unresolved().stream().map(BackendCallScanner.Unresolved::key).sorted().toList();

    assertThat(unresolved)
        .as(
            "call sites the scanner cannot fold must be listed in UNRESOLVED_EXCEPTIONS with the"
                + " check that covers them, and stale entries removed; found at %s",
            scan.unresolved().stream().map(BackendCallScanner.Unresolved::location).toList())
        .containsExactlyInAnyOrderElementsOf(UNRESOLVED_EXCEPTIONS.keySet());
  }

  @Test
  void everyCachedCatalogueIsAnExistingGet() {
    List<String> missing = new ArrayList<>();
    for (CachedCatalog catalog : CachedCatalog.values()) {
      String template = BackendCallScanner.canonical(catalog.getUri());
      if (!BackendCallScanner.isResolved(template) || !operations.exists("GET", template)) {
        missing.add(catalog.name() + " GET " + catalog.getUri());
      }
    }

    assertThat(CachedCatalog.values()).isNotEmpty();
    assertThat(missing).as("cached catalogues reading a GET the backend does not have").isEmpty();
  }

  @Test
  void theAnonymousTermsReadIsAnExistingGet() throws ReflectiveOperationException {
    Field field = BackendApiClient.class.getDeclaredField("TERMS_DOCUMENT_URI");
    field.setAccessible(true);
    String uri = (String) field.get(null);

    assertThat(operations.exists("GET", BackendCallScanner.canonical(uri)))
        .as("the anonymous terms read GET %s", uri)
        .isTrue();
  }

  @Test
  void everyLiveSyncProbeTemplateIsAnExistingGet() {
    Map<String, String> probes = liveSyncProbes();

    assertThat(probes)
        .as("fewer live-sync probe templates than when the guard was introduced")
        .hasSizeGreaterThanOrEqualTo(MIN_LIVE_SYNC_PROBES);
    assertThat(missingProbes(probes, operations))
        .as(
            "live-sync probes answered 405 or 404 by the backend deny every subscriber or, for a"
                + " status the authorizer does not treat as a refusal, admit them")
        .isEmpty();
    assertThat(
            UNRESOLVED_EXCEPTIONS.keySet().stream()
                .filter(k -> k.startsWith("BackendSideChannels#probe"))
                .allMatch(k -> k.contains(" GET ")))
        .as("the authorizer sends every probe as a GET")
        .isTrue();
  }

  @Test
  void aCallToARetiredPathOrWithTheWrongVerbIsReported() {
    BackendCallScanner.Result fixture =
        BackendCallScanner.scanSources(Map.of("Fixture.java", FIXTURE_SOURCE));
    BackendOperations fixtureOperations =
        BackendOperations.of(JsonMapper.builder().build().readTree(FIXTURE_OPENAPI));

    assertThat(fixture.calls())
        .extracting(c -> c.verb() + " " + BackendCallScanner.display(c.template()))
        .containsExactlyInAnyOrder(
            "GET /api/v1/missions/{}",
            "GET /api/v1/missions/{}/units",
            "PUT /api/v1/missions/{}/owner/{}",
            "GET /api/v1/missions/{}/crew",
            "GET /api/v1/missions/{}/steps",
            "GET /api/v1/missions/search",
            "GET /api/v1/retired/{}",
            "DELETE /api/v1/missions/{}",
            "POST /api/v1/missions/{}/report",
            "GET /api/v1/missions/{}/crew");
    assertThat(missing(fixture.calls(), fixtureOperations))
        .hasSize(2)
        .anySatisfy(m -> assertThat(m).startsWith("GET /api/v1/retired/{}"))
        .anySatisfy(m -> assertThat(m).startsWith("DELETE /api/v1/missions/{}"));
    assertThat(fixture.unresolved())
        .extracting(BackendCallScanner.Unresolved::key)
        .containsExactly("Fixture#opaque GET uri");
    assertThat(fixture.inconsistencies())
        .singleElement()
        .satisfies(i -> assertThat(i).contains("declares GET").contains("sends POST"));
  }

  @Test
  void aProbeTemplateWithoutAGetIsReported() {
    BackendOperations fixtureOperations =
        BackendOperations.of(JsonMapper.builder().build().readTree(FIXTURE_OPENAPI));

    assertThat(
            missingProbes(
                Map.of(
                    "MISSION authProbePath", "/api/v1/missions/{id}",
                    "OLD authProbePath", "/api/v1/retired/{id}",
                    "WRITE authProbePath", "/api/v1/missions/{id}/owner/{userId}"),
                fixtureOperations))
        .containsExactlyInAnyOrder(
            "OLD authProbePath GET /api/v1/retired/{id}",
            "WRITE authProbePath GET /api/v1/missions/{id}/owner/{userId}");
  }

  @Test
  void anExplicitFormatIndexIsFollowedAndAnOverflowingOneStaysDynamic() {
    assertThat(BackendCallScanner.argumentIndex("2")).isEqualTo(1);
    assertThat(BackendCallScanner.argumentIndex("99999999999")).isEqualTo(-1);

    BackendCallScanner.Result indexed =
        BackendCallScanner.scanSources(Map.of("Indexed.java", INDEXED_SOURCE));

    assertThat(indexed.calls())
        .extracting(c -> c.verb() + " " + BackendCallScanner.display(c.template()))
        .containsExactlyInAnyOrder("GET /api/v1/missions/crew", "GET /api/v1/missions/{}");
  }

  /** A source formatting its paths with explicit argument indexes, one of them out of range. */
  private static final String INDEXED_SOURCE =
      """
      package fixture;

      class Indexed {
        private static final String BASE = "/api/v1/missions";
        private BackendApiClient backendApiClient;

        void indexed() {
          backendApiClient.get("%2$s/%1$s".formatted("crew", BASE), Object.class);
          backendApiClient.get("%1$s/%99999999999$s".formatted(BASE), Object.class);
        }
      }
      """;

  /** A source whose calls cover every folding rule, one retired path and one wrong verb. */
  private static final String FIXTURE_SOURCE =
      """
      package fixture;

      class Fixture {
        private static final String BASE = "/api/v1/missions";
        private BackendApiClient backendApiClient;

        void literal(UUID id) {
          backendApiClient.get(BASE + "/" + id, Object.class);
          backendApiClient.get("/api/v1/missions/{id}/units?size=50", TYPE, id);
          backendApiClient.put(BASE + "/" + id + "/owner/" + id, null, Void.class);
        }

        void local(UUID id, boolean crew) {
          String uri = crew ? path(id, "crew") : path(id, "steps");
          backendApiClient.get(uri, Object.class);
        }

        private static String path(UUID id, String section) {
          return "%s/%s/%s".formatted(BASE, id, section);
        }

        void builder(String q) {
          StringBuilder uri = new StringBuilder(BASE).append("/search?size=10");
          uri.append("&q={q}");
          backendApiClient.get(uri.toString(), TYPE, q);
        }

        void retired(UUID id) {
          backendApiClient.get("/api/v1/retired/" + id, Object.class);
          backendApiClient.delete(UriComponentsBuilder.fromPath(BASE).pathSegment(id.toString())
              .toUriString(), Void.class);
        }

        void execute(UUID id) {
          backendApiClient.execute(
              HttpMethod.GET,
              BASE + "/" + id + "/report",
              webClient -> webClient.post().uri(b -> b.path(BASE + "/{id}/report").build(id)),
              spec -> spec.bodyToMono(byte[].class));
          backendApiClient.execute(
              HttpMethod.GET,
              BASE + "/" + id + "/crew",
              webClient -> webClient.get().uri(BASE + "/" + id + "/crew"),
              spec -> spec.bodyToMono(byte[].class));
        }

        void opaque(String uri) {
          backendApiClient.get(uri, Object.class);
        }
      }
      """;

  /** The operations the fixture is checked against. */
  private static final String FIXTURE_OPENAPI =
      """
      {"paths": {
        "/api/v1/missions/{id}": {"get": {}},
        "/api/v1/missions/{id}/units": {"get": {}},
        "/api/v1/missions/{id}/owner/{userId}": {"put": {}},
        "/api/v1/missions/{id}/{section}": {"get": {}},
        "/api/v1/missions/{id}/report": {"post": {}}
      }}
      """;

  /**
   * Lists the calls no operation serves.
   *
   * @param calls the resolved calls
   * @param index the operations
   * @return one line per call without an operation
   */
  private static List<String> missing(
      List<BackendCallScanner.Call> calls, BackendOperations index) {
    return calls.stream()
        .filter(c -> !index.exists(c.verb(), c.template()))
        .map(BackendCallScanner.Call::describe)
        .sorted()
        .toList();
  }

  /**
   * Lists the probe templates that are no existing {@code GET}.
   *
   * @param probes probe name to template
   * @param index the operations
   * @return one line per probe without a {@code GET}
   */
  private static List<String> missingProbes(Map<String, String> probes, BackendOperations index) {
    return probes.entrySet().stream()
        .filter(
            e -> {
              String template = BackendCallScanner.canonical(e.getValue());
              return !BackendCallScanner.isResolved(template) || !index.exists("GET", template);
            })
        .map(e -> e.getKey() + " GET " + e.getValue())
        .sorted()
        .toList();
  }

  /**
   * Collects every probe template the live-sync topic classes declare.
   *
   * @return {@code CLASS field} to template
   */
  private static Map<String, String> liveSyncProbes() {
    Map<String, String> probes = new TreeMap<>();
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      if (topicClass.authProbePath() != null) {
        probes.put(topicClass.name() + " authProbePath", topicClass.authProbePath());
      }
      if (topicClass.fallbackProbePath() != null) {
        probes.put(topicClass.name() + " fallbackProbePath", topicClass.fallbackProbePath());
      }
    }
    return probes;
  }

  /**
   * Locates the frontend's main Java sources from either the module or the repository root.
   *
   * @return the source root
   */
  private static Path frontendMainSources() {
    Path relative = Paths.get("src", "main", "java");
    if (Files.isDirectory(relative.resolve("de"))
        && Files.exists(Paths.get("src", "main", "resources", "templates"))) {
      return relative;
    }
    return Paths.get("frontend").resolve(relative);
  }
}
