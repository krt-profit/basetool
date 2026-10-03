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

package de.greluc.krt.profit.basetool.backend.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.io.Resource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The Android operations a hard-cut wave retired, which answer {@code APP_UPDATE_REQUIRED}
 * (REQ-API-020).
 *
 * <p>One entry per non-blank line: {@code VERB /api/v1/path}, where a path segment may be a {@code
 * {name}} placeholder that matches exactly one segment. Wildcards, paths outside {@code /api/}, the
 * T0 operations and duplicates are refused, so a malformed list fails startup.
 */
public final class RetiredOperations {

  /** The classpath location of the committed list. */
  public static final String LOCATION = "api/retired-operations.txt";

  /** The verbs an entry may name. */
  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** One entry: a verb, one space, an absolute path. */
  private static final Pattern LINE = Pattern.compile("^([A-Za-z]+) (/\\S+)$");

  /** A path segment is a literal or a single-segment placeholder. */
  private static final Pattern SEGMENT =
      Pattern.compile("^([A-Za-z0-9._~-]+|\\{[A-Za-z][A-Za-z0-9]*})$");

  /** The T0 operations, which never break and so are never retired (ADR-0234). */
  private static final List<PathPattern> NEVER_RETIRED =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/v1/app/version-policy"),
          PathPatternParser.defaultInstance.parse("/api/v1/exchange/**"),
          PathPatternParser.defaultInstance.parse("/api/v1/live-sync/**"),
          PathPatternParser.defaultInstance.parse("/api/v1/notifications/stream"));

  /** The parsed entries, in file order. */
  private final List<Entry> entries;

  private RetiredOperations(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /**
   * One retired operation.
   *
   * @param method the upper-case HTTP verb
   * @param path the path template as written in the list
   * @param pattern the compiled template
   */
  public record Entry(@NotNull String method, @NotNull String path, @NotNull PathPattern pattern) {

    /**
     * Renders the entry as it appears in the list.
     *
     * @return {@code VERB /path}
     */
    @NotNull
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /**
   * Returns an empty list, under which nothing answers {@code APP_UPDATE_REQUIRED}.
   *
   * @return the empty list
   */
  @NotNull
  public static RetiredOperations none() {
    return new RetiredOperations(List.of());
  }

  /**
   * Reads the list from a resource.
   *
   * @param resource the list file, UTF-8
   * @return the parsed list
   * @throws IOException if the resource cannot be read
   * @throws IllegalArgumentException if a line is malformed
   */
  @NotNull
  public static RetiredOperations load(@NotNull Resource resource) throws IOException {
    try (InputStream in = resource.getInputStream()) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return parse(text.lines().toList());
    }
  }

  /**
   * Parses the list from its lines; blank lines are skipped.
   *
   * @param lines the lines of the list
   * @return the parsed list
   * @throws IllegalArgumentException naming the first malformed, wildcard, out-of-scope, T0 or
   *     duplicate line
   */
  @NotNull
  public static RetiredOperations parse(@NotNull List<String> lines) {
    List<Entry> parsed = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String raw : lines) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      Entry entry = parseLine(line);
      if (!seen.add(entry.method() + " " + entry.path().replaceAll("\\{[^}]+}", "{}"))) {
        throw new IllegalArgumentException("duplicate retired operation: " + line);
      }
      parsed.add(entry);
    }
    return new RetiredOperations(parsed);
  }

  private static Entry parseLine(String line) {
    Matcher matcher = LINE.matcher(line);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not 'VERB /path': " + line);
    }
    String method = matcher.group(1).toUpperCase(Locale.ROOT);
    String path = matcher.group(2);
    if (!VERBS.contains(method)) {
      throw new IllegalArgumentException("unsupported verb: " + line);
    }
    if (!path.startsWith("/api/")) {
      throw new IllegalArgumentException("outside /api/: " + line);
    }
    for (String segment : path.substring(1).split("/", -1)) {
      if (!SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException("not a literal or {placeholder} segment: " + line);
      }
    }
    PathContainer literal = PathContainer.parsePath(path);
    if (NEVER_RETIRED.stream().anyMatch(t0 -> t0.matches(literal))) {
      throw new IllegalArgumentException("a T0 operation is never retired: " + line);
    }
    return new Entry(method, path, PathPatternParser.defaultInstance.parse(path));
  }

  /**
   * Returns the entries in file order.
   *
   * @return the entries; empty when nothing is retired
   */
  @NotNull
  @Unmodifiable
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Tells whether nothing is retired.
   *
   * @return {@code true} when the list has no entry
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Finds the entry a request matches, by exact verb and full path.
   *
   * @param method the request's HTTP verb
   * @param path the request path without the context path
   * @return the matching entry, or empty
   */
  @NotNull
  public Optional<Entry> match(@NotNull String method, @NotNull PathContainer path) {
    for (Entry entry : entries) {
      if (entry.method().equals(method) && entry.pattern().matches(path)) {
        return Optional.of(entry);
      }
    }
    return Optional.empty();
  }
}
