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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The call lists the Android app publishes per release, committed under {@code
 * backend/src/test/resources/api/app-calls/} (REQ-API-016, ADR-0234).
 *
 * <p>One file per app build the server still serves, named {@code <versionCode>.txt}, plus {@code
 * unreleased.txt} for the build under development. One line per operation: {@code <VERB> <path>
 * q=<names>|- f=<names>|- s=<sites>}, separated by single spaces; {@code q=} are the query
 * parameters the app sends, {@code f=} the response fields it may read (flat, two levels deep), and
 * {@code s=} the app's own call sites, which the server ignores.
 */
final class AppCallList {

  /** The version a list of the build under development sorts as: after every released build. */
  static final int UNRELEASED = Integer.MAX_VALUE;

  /** The file name of the list of the build under development, without its extension. */
  static final String UNRELEASED_NAME = "unreleased";

  /** The verbs a call may use. */
  private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** Not instantiable. */
  private AppCallList() {}

  /**
   * One operation the app calls.
   *
   * @param method the HTTP verb, upper case
   * @param path the path template exactly as the document writes it
   * @param query the query parameter names the app sends
   * @param fields the response field names the app may read
   */
  record Call(
      @NotNull String method,
      @NotNull String path,
      @NotNull @Unmodifiable Set<String> query,
      @NotNull @Unmodifiable Set<String> fields) {

    /**
     * Identifies the operation as {@code VERB path}.
     *
     * @return the verb and path separated by one space
     */
    @NotNull
    String key() {
      return method + " " + path;
    }
  }

  /**
   * The committed list of one app build.
   *
   * @param name the file name without {@code .txt}: the {@code versionCode} or {@code unreleased}
   * @param versionCode the build's {@code versionCode}, {@link #UNRELEASED} for the build under
   *     development
   * @param calls the operations it calls, in file order
   */
  record Release(@NotNull String name, int versionCode, @NotNull @Unmodifiable List<Call> calls) {}

  /**
   * Parses one committed list.
   *
   * @param name the file name without {@code .txt}
   * @param lines the file's lines; blank lines are skipped
   * @return the parsed list
   * @throws IllegalArgumentException naming the line when the name is neither a positive {@code
   *     versionCode} nor {@code unreleased}, a line is malformed, or an operation is listed twice
   */
  static @NotNull Release parse(@NotNull String name, @NotNull List<String> lines) {
    int versionCode = versionCode(name);
    List<Call> calls = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.isBlank()) {
        continue;
      }
      String where = "app call list " + name + " line " + (index + 1);
      String[] tokens = line.strip().split(" ");
      if (tokens.length < 4 || !VERBS.contains(tokens[0]) || !tokens[1].startsWith("/api/")) {
        throw new IllegalArgumentException(where + " is not '<VERB> <path> q=… f=… s=…': " + line);
      }
      Call call =
          new Call(tokens[0], tokens[1], names(tokens, "q=", where), names(tokens, "f=", where));
      if (!seen.add(call.key())) {
        throw new IllegalArgumentException(where + " lists " + call.key() + " a second time");
      }
      calls.add(call);
    }
    if (calls.isEmpty()) {
      throw new IllegalArgumentException("app call list " + name + " lists no call");
    }
    return new Release(name, versionCode, List.copyOf(calls));
  }

  /**
   * Loads every committed list of a directory.
   *
   * @param directory the directory holding the {@code .txt} files
   * @return the lists sorted by {@code versionCode}, the unreleased build last
   * @throws IOException if the directory or a file cannot be read
   * @throws IllegalArgumentException if a file is malformed or a non-{@code .txt} file is present
   */
  static @NotNull @Unmodifiable List<Release> load(@NotNull Path directory) throws IOException {
    List<Release> releases = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".txt")) {
          throw new IllegalArgumentException(
              directory + " holds " + fileName + ", which is not an app call list (.txt)");
        }
        releases.add(
            parse(
                fileName.substring(0, fileName.length() - ".txt".length()),
                Files.readAllLines(file, StandardCharsets.UTF_8)));
      }
    }
    releases.sort(Comparator.comparingInt(Release::versionCode));
    return List.copyOf(releases);
  }

  /**
   * Lists every call the frozen set does not cover.
   *
   * <p>A call is covered when the frozen set holds its verb and path and freezes every query
   * parameter it sends. Its response fields are frozen through the list itself: the contract guard
   * requires the document to keep serving them for as long as the list is committed.
   *
   * @param releases the committed lists
   * @param frozenQuery each frozen operation, keyed {@code VERB path}, to the query parameter names
   *     it freezes
   * @return one line per gap naming the list, the call and what is missing, sorted
   */
  static @NotNull @Unmodifiable List<String> uncovered(
      @NotNull List<Release> releases, @NotNull Map<String, Set<String>> frozenQuery) {
    Set<String> gaps = new TreeSet<>();
    for (Release release : releases) {
      for (Call call : release.calls()) {
        Set<String> frozen = frozenQuery.get(call.key());
        if (frozen == null) {
          gaps.add(release.name() + ": " + call.key() + " is not in the frozen set");
          continue;
        }
        Set<String> missing = new TreeSet<>(call.query());
        missing.removeAll(frozen);
        if (!missing.isEmpty()) {
          gaps.add(
              release.name()
                  + ": "
                  + call.key()
                  + " sends query parameters the frozen set does not freeze: "
                  + missing);
        }
      }
    }
    return List.copyOf(gaps);
  }

  /**
   * Lists the conflicts between the committed lists and the declared-break ledger.
   *
   * <p>A list of a build older than a declared break's absorbing {@code versionCode} names a build
   * the raised floor walls off, so it must be deleted with the break; a list of the absorbing build
   * or a newer one must not call an operation the ledger declares gone.
   *
   * @param releases the committed lists
   * @param ledger the parsed ledger
   * @return one line per conflict, sorted
   */
  static @NotNull @Unmodifiable List<String> conflictsWithLedger(
      @NotNull List<Release> releases, @NotNull List<DeclaredBreaks.Entry> ledger) {
    Set<String> conflicts = new TreeSet<>();
    for (DeclaredBreaks.Entry entry : ledger) {
      DeclaredBreaks.Break declared = entry.declared();
      String operation = declared.method() + " " + declared.path();
      for (Release release : releases) {
        if (release.versionCode() < entry.absorbedBy()) {
          conflicts.add(
              release.name()
                  + " is older than build "
                  + entry.absorbedBy()
                  + ", which absorbs '"
                  + declared
                  + "'; that build's floor walls it off, so delete its call list with the break");
          continue;
        }
        boolean calls = release.calls().stream().anyMatch(call -> call.key().equals(operation));
        if (calls && DeclaredBreaks.WHOLE_OPERATION.equals(declared.field())) {
          conflicts.add(
              release.name()
                  + " still calls "
                  + operation
                  + ", which the ledger declares gone from build "
                  + entry.absorbedBy());
        }
      }
    }
    return List.copyOf(conflicts);
  }

  /**
   * Reads a list's {@code versionCode} from its file name.
   *
   * @param name the file name without {@code .txt}
   * @return the positive {@code versionCode}, or {@link #UNRELEASED}
   * @throws IllegalArgumentException if the name is neither
   */
  private static int versionCode(@NotNull String name) {
    if (UNRELEASED_NAME.equals(name)) {
      return UNRELEASED;
    }
    if (!name.matches("[1-9][0-9]{0,8}")) {
      throw new IllegalArgumentException(
          "app call list "
              + name
              + " must be named <versionCode>.txt or "
              + UNRELEASED_NAME
              + ".txt");
    }
    return Integer.parseInt(name);
  }

  /**
   * Reads the comma-separated names of one {@code q=} or {@code f=} token.
   *
   * @param tokens the line's tokens
   * @param prefix {@code q=} or {@code f=}
   * @param where the list and line, for the error message
   * @return the names; empty for {@code -}
   * @throws IllegalArgumentException if the token is missing, repeated or empty
   */
  private static @NotNull @Unmodifiable Set<String> names(
      @NotNull String[] tokens, @NotNull String prefix, @NotNull String where) {
    List<String> matching =
        Arrays.stream(tokens).filter(token -> token.startsWith(prefix)).toList();
    if (matching.size() != 1) {
      throw new IllegalArgumentException(where + " must carry exactly one " + prefix + " token");
    }
    String value = matching.getFirst().substring(prefix.length());
    if ("-".equals(value)) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>(Arrays.asList(value.split(",", -1)));
    if (names.contains("")) {
      throw new IllegalArgumentException(where + " has an empty name in " + prefix);
    }
    return Set.copyOf(names);
  }
}
