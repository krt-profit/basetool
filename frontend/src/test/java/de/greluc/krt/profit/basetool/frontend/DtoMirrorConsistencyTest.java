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

package de.greluc.krt.profit.basetool.frontend;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static-analysis safety net for the recurring Backend-DTO &harr; Frontend-DTO mirror drift bug:
 * the backend evolves a record by adding a field, the frontend mirror is forgotten, a Thymeleaf
 * template later references the field and the page 500s with {@code Property or field 'X' cannot be
 * found} - typically only in a conditional render branch that everyday testing misses. Recurring
 * incident history lives in {@code CHANGELOG.md} (multiple "warf 500" entries) and is the reason
 * the {@code feedback_backend_frontend_dto_mirror} memory entry exists.
 *
 * <p>The check runs at source level (no compile-time cross-module dependency between frontend and
 * backend exists), enumerates every {@code *Dto.java} record in the frontend dto package, looks for
 * a same-named file in the backend dto package and compares the parsed record component names.
 * "Backend has component, frontend missing" is the failure direction - that is the one that
 * produces an HTTP 500 at render time. The opposite direction (frontend has component, backend
 * missing) is loud-but-harmless dead code and is reported as a soft warning via stdout rather than
 * a build break, because some frontend records carry deliberately client-side fields that the
 * backend never speaks (e.g. derived display helpers expressed as record components by accident).
 *
 * <p>Files where either side is not actually a {@code record} declaration (e.g. plain enum mirrors)
 * are silently skipped, as are frontend DTOs without a backend counterpart by name (request-only
 * payloads, frontend-private wrappers).
 *
 * <p>If you intentionally want to omit a backend field from the frontend mirror, list the pair in
 * {@link #ALLOWED_BACKEND_ONLY_FIELDS} with a short reason so future drift on the SAME field stays
 * silent but a NEW backend-only field still trips the test.
 */
class DtoMirrorConsistencyTest {

  private static final Path FRONTEND_DTO_DIR =
      resolveModuleRelative("src/main/java/de/greluc/krt/profit/basetool/frontend/model/dto");
  private static final Path BACKEND_DTO_DIR =
      resolveModuleRelative(
          "../backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/dto");

  /**
   * Per-DTO whitelist of backend-only record components that the frontend deliberately does not
   * mirror. Keep the value list explicit so a NEW backend-only field on the same DTO still trips
   * the assertion - we want the whitelist to grow only on conscious decisions.
   *
   * <p>Empty as of PR #229: the three known asymmetries that existed when the test was introduced
   * (OperationDto createdAt/updatedAt, StarSystemDto's six backend extras, JobOrderHandoverItemDto
   * locationName) were all fixed by mirroring the fields. Add new entries only when there is a real
   * reason a backend field cannot/should not surface on the frontend mirror.
   */
  private static final Map<String, Set<String>> ALLOWED_BACKEND_ONLY_FIELDS = Map.of();

  /**
   * Resolves a path that is given relative to the frontend module root. When Gradle runs the test
   * the working directory is the frontend module root; when a developer accidentally runs the test
   * from the repository root (e.g. via the IDE), the {@code frontend/} prefix branch keeps the
   * lookup working.
   */
  private static Path resolveModuleRelative(String relative) {
    Path direct = Paths.get(relative);
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromRepoRoot = Paths.get("frontend").resolve(relative);
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return direct;
  }

  @Test
  void everyFrontendDtoMirrorMustNotMissBackendRecordComponents() throws IOException {
    assertTrue(
        Files.isDirectory(FRONTEND_DTO_DIR),
        "Frontend DTO directory not found at " + FRONTEND_DTO_DIR.toAbsolutePath());
    assertTrue(
        Files.isDirectory(BACKEND_DTO_DIR),
        "Backend DTO directory not found at " + BACKEND_DTO_DIR.toAbsolutePath());

    List<String> drifts = new ArrayList<>();
    List<String> softWarnings = new ArrayList<>();
    int paired = 0;

    try (Stream<Path> stream = Files.list(FRONTEND_DTO_DIR)) {
      List<Path> frontendDtos =
          stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      for (Path frontendFile : frontendDtos) {
        String filename = frontendFile.getFileName().toString();
        Path backendFile = BACKEND_DTO_DIR.resolve(filename);
        if (!Files.exists(backendFile)) {
          continue;
        }
        List<String> frontendComponents =
            extractRecordComponentNames(Files.readString(frontendFile));
        List<String> backendComponents = extractRecordComponentNames(Files.readString(backendFile));
        if (frontendComponents == null || backendComponents == null) {
          continue;
        }
        paired++;

        Set<String> missingOnFrontend = new LinkedHashSet<>(backendComponents);
        missingOnFrontend.removeAll(frontendComponents);
        Set<String> allowed = ALLOWED_BACKEND_ONLY_FIELDS.getOrDefault(filename, Set.of());
        missingOnFrontend.removeAll(allowed);
        if (!missingOnFrontend.isEmpty()) {
          drifts.add(
              filename
                  + " — backend record has components the frontend mirror is missing: "
                  + missingOnFrontend
                  + ". A Thymeleaf template that references any of these will 500 at render time."
                  + " Add them to the frontend record (preferred) or, if intentional, list them in"
                  + " ALLOWED_BACKEND_ONLY_FIELDS with a rationale.");
        }

        Set<String> extraOnFrontend = new LinkedHashSet<>(frontendComponents);
        extraOnFrontend.removeAll(backendComponents);
        if (!extraOnFrontend.isEmpty()) {
          softWarnings.add(filename + " — frontend-only record components: " + extraOnFrontend);
        }
      }
    }

    assertTrue(paired > 0, "No paired DTOs found - directory layout or detection logic broke.");

    if (!softWarnings.isEmpty()) {
      System.out.println("DTO mirror soft warnings (frontend-only fields):");
      softWarnings.forEach(w -> System.out.println("  " + w));
    }

    if (!drifts.isEmpty()) {
      fail(
          "DTO mirror drift detected (this is the recurring 'Property or field cannot be found'"
              + " Thymeleaf bug class — see CHANGELOG.md and the"
              + " feedback_backend_frontend_dto_mirror memory entry):\n"
              + "  "
              + String.join("\n  ", drifts));
    }
  }

  /**
   * Parses a Java source file and returns the record-component names of the first {@code public
   * record Foo(...)} declaration found, or {@code null} if the file does not declare a record at
   * top level. Annotations, generics and nested parenthesised expressions inside component
   * declarations are skipped via depth-tracked scanning - the regex anchor only spots the start of
   * the record header, the rest is bracket-balanced.
   */
  private static List<String> extractRecordComponentNames(String source) {
    Pattern anchor = Pattern.compile("public\\s+record\\s+(\\w+)\\s*(?:<[^>]+>)?\\s*\\(");
    Matcher matcher = anchor.matcher(source);
    if (!matcher.find()) {
      return null;
    }
    int cursor = matcher.end();
    int depth = 1;
    int headerEnd = cursor;
    while (headerEnd < source.length() && depth > 0) {
      char c = source.charAt(headerEnd);
      switch (c) {
        case '(' -> depth++;
        case ')' -> depth--;
        default -> {}
      }
      headerEnd++;
    }
    if (depth != 0) {
      return null;
    }
    String body = source.substring(cursor, headerEnd - 1);
    return splitTopLevelByComma(body).stream()
        .map(DtoMirrorConsistencyTest::extractParameterName)
        .filter(name -> name != null && !name.isEmpty())
        .toList();
  }

  /**
   * Splits the record header body on commas that sit at depth 0 - parens, angle brackets and square
   * brackets all count as nesting depth so generic types ({@code List<Map<String, Integer>>}) and
   * annotation arguments ({@code @JsonProperty(value = "x")}) do not produce false splits.
   */
  private static List<String> splitTopLevelByComma(String body) {
    List<String> result = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '<' || c == '(' || c == '[') {
        depth++;
      } else if (c == '>' || c == ')' || c == ']') {
        depth--;
      } else if (c == ',' && depth == 0) {
        result.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    if (!cur.isEmpty()) {
      result.add(cur.toString());
    }
    return result;
  }

  /**
   * Reduces a single component declaration ({@code "@NotNull String name"}, {@code "List<UUID>
   * ids"}, ...) to its parameter name. Strips leading annotations (with or without arguments) and
   * then takes the last whitespace-separated token of what remains as the parameter name.
   */
  private static String extractParameterName(String component) {
    String stripped = stripLeadingAnnotations(component.trim());
    if (stripped.isEmpty()) {
      return null;
    }
    String[] tokens = stripped.split("\\s+");
    if (tokens.length == 0) {
      return null;
    }
    return tokens[tokens.length - 1].trim();
  }

  /**
   * Drops zero or more leading {@code @Annotation} or {@code @Annotation(args)} tokens from the
   * front of {@code s} so the subsequent split-on-whitespace yields {@code Type name} without
   * annotation noise interfering. Cursor walks character by character to respect parenthesised
   * argument lists.
   */
  private static String stripLeadingAnnotations(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) == '@') {
      i++;
      while (i < s.length()
          && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
        i++;
      }
      if (i < s.length() && s.charAt(i) == '(') {
        int depth = 1;
        i++;
        while (i < s.length() && depth > 0) {
          char c = s.charAt(i);
          if (c == '(') {
            depth++;
          } else if (c == ')') {
            depth--;
          }
          i++;
        }
      }
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
    return s.substring(i);
  }

  /**
   * Exposes the parsed record components of one source file for debug-time inspection. Not used by
   * the production assertion above; kept as a public-test artefact so a future Claude session that
   * needs to debug a false positive can break here and inspect the intermediate representation
   * without having to re-derive the parser.
   */
  @SuppressWarnings("unused")
  static Map<String, List<String>> debugDumpParsedComponents() throws IOException {
    Map<String, List<String>> out = new LinkedHashMap<>();
    try (Stream<Path> stream = Files.list(FRONTEND_DTO_DIR)) {
      for (Path p : stream.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
        out.put(p.getFileName().toString(), extractRecordComponentNames(Files.readString(p)));
      }
    }
    return out;
  }
}
