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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds every catch arm of the frontend's domain packages that can catch a session gate's refusal
 * to handing it off (REQ-SEC-028, ADR-0385).
 *
 * <p>An arm can catch the refusal when it catches {@code BackendServiceException}, {@code
 * RuntimeException}, {@code Exception} or {@code Throwable}, alone or in a multi-catch. Its first
 * statement must hand the refusal off: {@link SectionFallback#rethrowHandOff}, {@code return
 * BackendErrorResponses.propagateBackendError}, which calls it first, or a rethrow of the caught
 * exception. A first statement {@link SectionFallback#rethrowIfReauthenticationRequired} counts as
 * well, because {@link SessionLossOnlyGuardConfinementTest} confines it to the layout chrome. Every
 * other such arm counts against its file's allowance, which must match exactly. The kernel is out
 * of scope; its shared fallbacks hand off in their own tests. The scanner is proven against planted
 * sources.
 */
class SessionGateHandOffArmTest {

  private static final String PACKAGE_ROOT = "src/main/java/de/greluc/krt/profit/basetool/frontend";

  /** The caught types, by simple name, that a {@code SessionGateRefusalException} is one of. */
  private static final Set<String> GATE_REFUSAL_SUPERTYPES =
      Set.of("BackendServiceException", "RuntimeException", "Exception", "Throwable");

  private static final Pattern CATCH_ARM =
      Pattern.compile(
          "catch\\s*\\(\\s*(?:final\\s+)?([\\w.]+(?:\\s*\\|\\s*[\\w.]+)*)\\s+(\\w+)\\s*\\)\\s*\\{");

  /** The first statements that hand the caught refusal off, {@code %s} standing for its name. */
  private static final List<String> HAND_OFF_STATEMENTS =
      List.of(
          "(?:SectionFallback\\.)?rethrowHandOff\\(\\s*%s\\s*\\)\\s*;",
          "(?:SectionFallback\\.)?rethrowIfReauthenticationRequired\\(\\s*%s\\s*\\)\\s*;",
          "return\\s+(?:BackendErrorResponses\\.)?propagateBackendError\\(\\s*%s\\s*\\)\\s*;",
          "throw\\s+%s\\s*;");

  /** The files whose arms answer a gate's refusal themselves, by root-relative path. */
  private static final Map<String, Allowance> ALLOWED =
      Map.of(
          "notification/web/NotificationPageController.java",
          new Allowance(
              5,
              "the stream's token read and SSE writes call no backend; the stream hands a gate's"
                  + " refusal on as the gate's SSE event"));

  @Test
  void everyDomainArmThatCanCatchAGateRefusalHandsItOff() throws IOException {
    Map<String, List<Integer>> actual = scanDomainSources(resolveModuleRelative(PACKAGE_ROOT));

    assertThat(grown(actual, ALLOWED))
        .as(
            "catch arms that answer a session gate's refusal themselves; call"
                + " SectionFallback.rethrowHandOff(e) as the arm's first statement, or catch the"
                + " specific exception")
        .isEmpty();
    assertThat(shrunk(actual, ALLOWED))
        .as("files below their allowance; lower it (delete the entry at 0)")
        .isEmpty();
  }

  @Test
  void theScannerFindsEveryArmThatAnswersAGateRefusalItself() {
    String planted =
        String.join(
            "\n",
            "try { a(); } catch (BackendServiceException e) { toast(); }",
            "catch (IllegalStateException | RuntimeException ex) { log.warn(\"x\", ex); }",
            "catch (final Exception e) { log.debug(\"x\"); SectionFallback.rethrowHandOff(e); }",
            "catch (Throwable _) { }",
            "catch (java.lang.Exception e) { fallback(); }",
            "catch (BackendServiceException e) { rethrowHandOff(other); }",
            "catch (BackendServiceException e) { SectionFallback.rethrowHandOff(e); toast(); }",
            "catch (Exception e) { /* hand off */ rethrowHandOff(e); }",
            "catch (BackendServiceException e) { return propagateBackendError(e); }",
            "catch (RuntimeException e) { throw e; }",
            "catch (RuntimeException e) { SectionFallback.rethrowIfReauthenticationRequired(e); }",
            "catch (IllegalArgumentException e) { toast(); }",
            "catch (ExceptionInInitializerError e) { toast(); }",
            "// catch (Exception e) { toast(); }",
            "String s = \"catch (Exception e) { toast(); }\";");

    assertThat(localArmLines(planted)).containsExactly(1, 2, 3, 4, 5, 6);
  }

  @Test
  void aGrownAndAShrunkFileAreEachReported() {
    Map<String, Allowance> allowed =
        Map.of("a/web/A.java", new Allowance(2, "a"), "b/web/B.java", new Allowance(3, "b"));
    Map<String, List<Integer>> actual =
        Map.of(
            "a/web/A.java",
            List.of(10, 20, 30),
            "b/web/B.java",
            List.of(5),
            "c/C.java",
            List.of(7));

    assertThat(grown(actual, allowed))
        .containsOnlyKeys("a/web/A.java", "c/C.java")
        .containsEntry("c/C.java", "lines [7], allowed 0 (none)");
    assertThat(shrunk(actual, allowed)).containsOnlyKeys("b/web/B.java");
  }

  @Test
  void onlyDomainPackagesAreScanned() {
    assertThat(isDomainSource("inventory/web/InventoryWriteController.java")).isTrue();
    assertThat(isDomainSource("supply/web/SupplyFeatureState.java")).isTrue();
    assertThat(isDomainSource("kernel/web/BackendErrorResponses.java")).isFalse();
    assertThat(isDomainSource("FrontendApplication.java")).isFalse();
    assertThat(isDomainSource("inventory/web/package-info.txt")).isFalse();
  }

  /**
   * Lists the files with more local arms than allowed; a file without an allowance may hold none.
   *
   * @param actual the local arms' lines by file
   * @param allowed the allowances by file
   * @return the lines and the allowance by file
   */
  private static Map<String, String> grown(
      Map<String, List<Integer>> actual, Map<String, Allowance> allowed) {
    Map<String, String> grown = new TreeMap<>();
    actual.forEach((file, lines) -> {
      Allowance allowance = allowed.getOrDefault(file, Allowance.NONE);
      if (lines.size() > allowance.arms()) {
        grown.put(file, "lines " + lines + ", allowed " + allowance);
      }
    });
    return grown;
  }

  /**
   * Lists the files with fewer local arms than allowed, a vanished file counting as none.
   *
   * @param actual the local arms' lines by file
   * @param allowed the allowances by file
   * @return the count and the allowance by file
   */
  private static Map<String, String> shrunk(
      Map<String, List<Integer>> actual, Map<String, Allowance> allowed) {
    Map<String, String> shrunk = new TreeMap<>();
    allowed.forEach((file, allowance) -> {
      int count = actual.getOrDefault(file, List.of()).size();
      if (count < allowance.arms()) {
        shrunk.put(file, count + " < " + allowance);
      }
    });
    return shrunk;
  }

  /**
   * Finds the local arms of every domain source under the package root.
   *
   * @param root the frontend package root
   * @return the local arms' lines by root-relative path with forward slashes, files without any
   *     left out
   * @throws IOException when the tree cannot be walked
   */
  private static Map<String, List<Integer>> scanDomainSources(Path root) throws IOException {
    assertThat(Files.isDirectory(root)).as("source root not found at " + root).isTrue();
    Map<String, List<Integer>> arms = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      files
          .filter(Files::isRegularFile)
          .forEach(file -> {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (!isDomainSource(relative)) {
              return;
            }
            List<Integer> lines = localArmLines(read(file));
            if (!lines.isEmpty()) {
              arms.put(relative, lines);
            }
          });
    }
    return arms;
  }

  /**
   * Decides whether a root-relative path names a Java source of a domain package: one below a
   * top-level package other than {@code kernel}.
   *
   * @param relative the path relative to the frontend package root, with forward slashes
   * @return {@code true} for a {@code .java} file in a domain package
   */
  private static boolean isDomainSource(String relative) {
    String[] segments = relative.split("/");
    return relative.endsWith(".java") && segments.length > 1 && !"kernel".equals(segments[0]);
  }

  /**
   * Finds the arms of a source that can catch a gate's refusal and do not hand it off first.
   *
   * @param source the Java source text
   * @return the 1-based lines of their {@code catch} keywords, in source order
   */
  private static List<Integer> localArmLines(String source) {
    String code = blankCommentsAndLiterals(source);
    Matcher arm = CATCH_ARM.matcher(code);
    List<Integer> lines = new ArrayList<>();
    while (arm.find()) {
      boolean catchesGateRefusal =
          Arrays.stream(arm.group(1).split("\\|"))
              .map(String::trim)
              .map(type -> type.substring(type.lastIndexOf('.') + 1))
              .anyMatch(GATE_REFUSAL_SUPERTYPES::contains);
      if (catchesGateRefusal && !handsOffFirst(code.substring(arm.end()), arm.group(2))) {
        lines.add(lineOf(code, arm.start()));
      }
    }
    return lines;
  }

  /**
   * Tells whether an arm's body starts with a statement that hands the caught refusal off.
   *
   * @param body the source after the arm's opening brace, comments and literals blanked
   * @param variable the caught exception's name
   * @return {@code true} when the first statement is one of {@link #HAND_OFF_STATEMENTS}
   */
  private static boolean handsOffFirst(String body, String variable) {
    String first = body.stripLeading();
    return HAND_OFF_STATEMENTS.stream()
        .map(statement -> Pattern.compile(statement.formatted(Pattern.quote(variable))))
        .anyMatch(statement -> statement.matcher(first).lookingAt());
  }

  /**
   * Replaces every comment and string, text-block and character literal with spaces, keeping line
   * breaks, so neither a commented-out arm nor text in a literal is scanned.
   *
   * @param source the Java source text
   * @return the source of the same length and lines with comments and literals blanked
   */
  private static String blankCommentsAndLiterals(String source) {
    StringBuilder code = new StringBuilder(source.length());
    int index = 0;
    while (index < source.length()) {
      int end = endOfCommentOrLiteral(source, index);
      if (end == index) {
        code.append(source.charAt(index));
        index++;
        continue;
      }
      for (; index < end; index++) {
        code.append(source.charAt(index) == '\n' ? '\n' : ' ');
      }
    }
    return code.toString();
  }

  /**
   * Finds the end of the comment or literal starting at an index.
   *
   * @param source the Java source text
   * @param start the index to look at
   * @return the index after the comment or literal, or {@code start} when none starts there
   */
  private static int endOfCommentOrLiteral(String source, int start) {
    if (source.startsWith("//", start)) {
      int lineEnd = source.indexOf('\n', start);
      return lineEnd < 0 ? source.length() : lineEnd;
    }
    if (source.startsWith("/*", start)) {
      return closedAt(source, source.indexOf("*/", start + 2), 2);
    }
    if (source.startsWith("\"\"\"", start)) {
      return closedAt(source, source.indexOf("\"\"\"", start + 3), 3);
    }
    char quote = source.charAt(start);
    if (quote != '"' && quote != '\'') {
      return start;
    }
    int index = start + 1;
    while (index < source.length() && source.charAt(index) != quote) {
      index += source.charAt(index) == '\\' ? 2 : 1;
    }
    return Math.min(index + 1, source.length());
  }

  /**
   * Turns the index of a closing delimiter into the index after it, an unclosed one ending the
   * source.
   *
   * @param source the Java source text
   * @param close the delimiter's index, {@code -1} when it is missing
   * @param length the delimiter's length
   * @return the index after the delimiter
   */
  private static int closedAt(String source, int close, int length) {
    return close < 0 ? source.length() : close + length;
  }

  /**
   * Counts the 1-based line of an index.
   *
   * @param source the text
   * @param index the index
   * @return its line
   */
  private static int lineOf(String source, int index) {
    int line = 1;
    for (int i = 0; i < index; i++) {
      if (source.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * Reads a source file as UTF-8.
   *
   * @param file the file
   * @return its text
   */
  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Resolves a module-relative path, tolerating a run from the repository root.
   *
   * @param relative the module-relative path
   * @return the path that exists, or the module-relative one when neither does
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

  /**
   * The arms of one file that answer a gate's refusal themselves.
   *
   * @param arms how many arms
   * @param reason why they must answer it
   */
  private record Allowance(int arms, String reason) {

    /** The allowance of a file the map does not name. */
    static final Allowance NONE = new Allowance(0, "none");

    /**
     * Renders the allowance for a failure message.
     *
     * @return the count followed by the reason in parentheses
     */
    @Override
    public String toString() {
      return arms + " (" + reason + ")";
    }
  }
}
