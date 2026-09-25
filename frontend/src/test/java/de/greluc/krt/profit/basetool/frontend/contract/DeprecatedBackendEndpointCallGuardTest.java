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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails the build when the web frontend calls a backend operation the backend has deprecated.
 *
 * <p><b>Why this exists.</b> The 17 legacy mission endpoints carried {@code @ApiDeprecation} with a
 * sunset of 2026-10-20 for months, and the frontend kept calling twelve of them — two of them live
 * JavaScript paths (the payout preference and the owner change) — while every check stayed green:
 * the {@code Deprecation} / {@code Sunset} headers are only ever read by a human, and nothing read
 * them. A deprecation nobody's build notices is a date on which something breaks (BE-SIMP-02).
 *
 * <p><b>How it decides.</b> The deprecated operations are read from the committed API document
 * ({@code backend/src/main/resources/api/openapi.json}, {@code deprecated: true} — which {@code
 * OpenApiDeprecationConfig} sets from {@code @ApiDeprecation}), so a new deprecation is guarded the
 * moment the document is regenerated, with no list to maintain here. Every {@code
 * backendApiClient.<verb>(…)} call in the frontend's main sources is parsed down to its path
 * template — string literals kept, every concatenated expression turned into a {@code {}} slot, the
 * query string dropped — and matched, verb included, against the deprecated templates.
 *
 * <p><b>What it cannot see.</b> A path built somewhere else and handed to the client as a variable
 * ({@code backendApiClient.get(url, …)}) has no literal to read and is skipped. That is why the
 * scan asserts a floor on the calls it did resolve: a parser that silently stopped recognising
 * calls would otherwise pass for the wrong reason.
 */
class DeprecatedBackendEndpointCallGuardTest {

  /** The verbs {@code BackendApiClient} exposes, which are also the HTTP methods they send. */
  private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");

  /**
   * Fewer resolved calls than this means the parser broke, not that the frontend got smaller: there
   * are well over two hundred literal backend calls in the main sources.
   */
  private static final int MIN_RESOLVED_CALLS = 150;

  /** One call site: the HTTP verb, the normalised path template, and where it is. */
  private record Call(String verb, String template, String location) {}

  @Test
  void theFrontendCallsNoDeprecatedBackendOperation() throws IOException {
    Set<String> deprecated = deprecatedOperations(readOpenApi());
    List<Call> calls = new ArrayList<>();
    try (Stream<Path> files = Files.walk(frontendMainSources())) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        calls.addAll(backendCalls(Files.readString(file), file.getFileName().toString()));
      }
    }

    assertThat(calls)
        .as("the scan resolved too few backendApiClient calls; the parser is broken")
        .hasSizeGreaterThanOrEqualTo(MIN_RESOLVED_CALLS);

    List<String> offenders =
        calls.stream()
            .filter(c -> deprecated.contains(c.verb() + " " + c.template()))
            .map(c -> c.verb() + " " + c.template() + " at " + c.location())
            .toList();
    assertThat(offenders)
        .as(
            "the frontend calls backend operations marked deprecated in openapi.json; move each to"
                + " the replacement its @ApiDeprecation names before the sunset removes it")
        .isEmpty();
  }

  @Test
  void theParserRecognisesAConcatenatedPathAndMatchesItAgainstATemplate() {
    String source =
        """
        backendApiClient.put(
            "/api/v1/missions/" + missionUuid + "/owner/" + userUuid, null, Void.class);
        backendApiClient.get("/api/v1/missions/" + id + "/units?size=1000", TYPE);
        """;

    List<Call> calls = backendCalls(source, "Sample.java");

    assertThat(calls)
        .extracting(c -> c.verb() + " " + c.template())
        .containsExactly("PUT /api/v1/missions/{}/owner/{}", "GET /api/v1/missions/{}/units");
    assertThat(
            deprecatedOperations(
                JsonMapper.builder()
                    .build()
                    .readTree(
                        "{\"paths\":{\"/api/v1/missions/{id}/owner/{userId}\":{\"put\":"
                            + "{\"deprecated\":true},\"get\":{}}}}")))
        .containsExactly("PUT /api/v1/missions/{}/owner/{}");
  }

  /**
   * Collects every operation the document marks {@code deprecated}, as {@code VERB template} with
   * each path variable written {@code {}}.
   *
   * @param openApi the parsed API document
   * @return the deprecated operations
   */
  private static Set<String> deprecatedOperations(JsonNode openApi) {
    Set<String> found = new TreeSet<>();
    for (Map.Entry<String, JsonNode> path : openApi.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        if (operation.getValue().path("deprecated").asBoolean(false)) {
          found.add(
              operation.getKey().toUpperCase(Locale.ROOT)
                  + " "
                  + path.getKey().replaceAll("\\{[^}]*}", "{}"));
        }
      }
    }
    return found;
  }

  /**
   * Finds every {@code backendApiClient.<verb>(} call in a source file and reduces its first
   * argument to a path template.
   *
   * @param source the Java source
   * @param fileName the file name, for the failure message
   * @return the calls whose first argument starts with an {@code /api/} string literal
   */
  private static List<Call> backendCalls(String source, String fileName) {
    List<Call> calls = new ArrayList<>();
    int from = 0;
    while (true) {
      int at = source.indexOf("backendApiClient.", from);
      if (at < 0) {
        return calls;
      }
      int nameStart = at + "backendApiClient.".length();
      int paren = source.indexOf('(', nameStart);
      from = nameStart;
      if (paren < 0) {
        return calls;
      }
      String verb = source.substring(nameStart, paren).trim();
      if (!VERBS.contains(verb)) {
        continue;
      }
      String template = firstArgumentTemplate(source, paren + 1);
      if (template != null && template.startsWith("/api/")) {
        int line = (int) source.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
        calls.add(new Call(verb.toUpperCase(Locale.ROOT), template, fileName + ":" + line));
      }
    }
  }

  /**
   * Reads a call's first argument up to its top-level comma or closing parenthesis and renders it
   * as a template: string literals verbatim, each other {@code +} operand as {@code {}}, anything
   * after a {@code ?} dropped.
   *
   * @param source the Java source
   * @param start the index just after the call's opening parenthesis
   * @return the template, or {@code null} when the argument contains no string literal at all
   */
  private static String firstArgumentTemplate(String source, int start) {
    StringBuilder template = new StringBuilder();
    StringBuilder operand = new StringBuilder();
    boolean sawLiteral = false;
    int depth = 0;
    int i = start;
    while (i < source.length()) {
      char c = source.charAt(i);
      if (c == '"') {
        int end = i + 1;
        StringBuilder literal = new StringBuilder();
        while (end < source.length() && source.charAt(end) != '"') {
          if (source.charAt(end) == '\\') {
            end++;
          }
          literal.append(source.charAt(end));
          end++;
        }
        template.append(literal);
        sawLiteral = true;
        operand.setLength(0);
        i = end + 1;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (c == ',' && depth == 0) {
        break;
      } else if (c == '+' && depth == 0) {
        if (!operand.toString().isBlank()) {
          template.append("{}");
        }
        operand.setLength(0);
        i++;
        continue;
      }
      operand.append(c);
      i++;
    }
    if (!operand.toString().isBlank()) {
      template.append("{}");
    }
    if (!sawLiteral) {
      return null;
    }
    int query = template.indexOf("?");
    return query < 0 ? template.toString() : template.substring(0, query);
  }

  /**
   * Locates the frontend's main Java sources from either the module or the repository root.
   *
   * @return the source root
   */
  private static Path frontendMainSources() {
    Path relative = Paths.get("src", "main", "java");
    Path direct = relative;
    if (Files.isDirectory(direct.resolve("de"))
        && Files.exists(Paths.get("src", "main", "resources", "templates"))) {
      return direct;
    }
    return Paths.get("frontend").resolve(relative);
  }

  /**
   * Reads the committed backend API document by walking up from the working directory.
   *
   * @return the parsed document
   */
  private static JsonNode readOpenApi() throws IOException {
    Path relative = Paths.get("backend", "src", "main", "resources", "api", "openapi.json");
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return JsonMapper.builder().build().readTree(Files.readString(candidate));
      }
    }
    throw new UncheckedIOException(
        new IOException("backend/src/main/resources/api/openapi.json not found"));
  }
}
