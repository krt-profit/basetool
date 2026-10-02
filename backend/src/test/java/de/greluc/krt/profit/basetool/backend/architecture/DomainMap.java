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

package de.greluc.krt.profit.basetool.backend.architecture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The parsed domain map: the target module and rank of every backend class (REQ-MOD-001).
 *
 * <p>The source is a line-oriented text file. {@code base} names the root package, {@code module
 * <name> <rank>} declares a module, {@code allow <from> <to>} permits one same-rank dependency, and
 * the ordered rules {@code class}, {@code package}, {@code name} and {@code layer} assign a class
 * to a module; the first matching rule wins. A rule may carry a free-text reason after the module.
 */
public final class DomainMap {

  /** Classpath location of the backend's domain map. */
  public static final String RESOURCE = "architecture/domain-map.txt";

  /** Repository path of the backend's domain map, named in failure messages. */
  public static final String SOURCE_PATH = "backend/src/test/resources/" + RESOURCE;

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static final String IMPL_SUFFIX = "Impl";

  private final String source;
  private final String basePackage;
  private final Map<String, Integer> ranks;
  private final Set<String> allowed;
  private final List<Rule> rules;

  private DomainMap(
      @NotNull String source,
      @NotNull String basePackage,
      @NotNull Map<String, Integer> ranks,
      @NotNull Set<String> allowed,
      @NotNull List<Rule> rules) {
    this.source = source;
    this.basePackage = basePackage;
    this.ranks = Collections.unmodifiableMap(new LinkedHashMap<>(ranks));
    this.allowed = Set.copyOf(allowed);
    this.rules = List.copyOf(rules);
  }

  /** The kind of a rule, which decides what its pattern is matched against. */
  public enum RuleKind {
    /** The pattern equals the simple name of the class. */
    CLASS,
    /** The pattern is a package below the base package; the class lies in it or beneath it. */
    PACKAGE,
    /** The pattern is a regular expression matched at the start of the simple name. */
    NAME,
    /** The pattern equals the first package segment below the base package. */
    LAYER
  }

  /**
   * One assignment rule of the domain map.
   *
   * @param line the 1-based line of the rule in its source
   * @param kind what the pattern is matched against
   * @param pattern the class name, package, regular expression or layer the rule matches
   * @param module the module the rule assigns
   * @param reason the recorded reason, empty when the rule carries none
   * @param regex the compiled pattern of a {@link RuleKind#NAME} rule, {@code null} otherwise
   */
  public record Rule(
      int line,
      @NotNull RuleKind kind,
      @NotNull String pattern,
      @NotNull String module,
      @NotNull String reason,
      @Nullable Pattern regex) {

    /**
     * Tells whether this rule matches a class.
     *
     * @param simpleName the simple name of the class
     * @param relativePackage the class's package below the base package, empty for the base package
     *     itself
     * @return {@code true} when the rule assigns the class
     */
    public boolean matches(@NotNull String simpleName, @NotNull String relativePackage) {
      return switch (kind) {
        case CLASS -> pattern.equals(simpleName);
        case PACKAGE ->
            relativePackage.equals(pattern) || relativePackage.startsWith(pattern + ".");
        case NAME -> regex != null && regex.matcher(stripImplSuffix(simpleName)).lookingAt();
        case LAYER -> firstSegment(relativePackage).equals(pattern);
      };
    }

    /**
     * Renders the rule as its source line for failure messages.
     *
     * @return the kind, pattern and module of the rule with its line number
     */
    @Override
    public @NotNull String toString() {
      return "line "
          + line
          + ": "
          + kind.name().toLowerCase(Locale.ROOT)
          + " "
          + pattern
          + " "
          + module;
    }
  }

  /**
   * Loads the backend's domain map from the test classpath.
   *
   * @return the parsed map
   * @throws IllegalStateException when the resource is missing
   * @throws IllegalArgumentException when the map is malformed
   */
  public static @NotNull DomainMap load() {
    try (InputStream in = DomainMap.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Domain map not on the test classpath: " + RESOURCE);
      }
      return parse(SOURCE_PATH, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses a domain map.
   *
   * @param source the name of the map's origin, used in error messages
   * @param text the map's text
   * @return the parsed map
   * @throws IllegalArgumentException when a line is malformed, a module is declared twice or is
   *     unknown, a class rule repeats, or the base package is missing
   */
  public static @NotNull DomainMap parse(@NotNull String source, @NotNull String text) {
    String base = null;
    Map<String, Integer> ranks = new LinkedHashMap<>();
    Set<String> allowed = new HashSet<>();
    List<String[]> allowRows = new ArrayList<>();
    List<Rule> rules = new ArrayList<>();
    Set<String> classRules = new HashSet<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      int lineNo = i + 1;
      String line = lines[i].strip();
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = WHITESPACE.split(line, 4);
      String directive = parts[0];
      switch (directive) {
        case "base" -> {
          require(parts.length == 2 && base == null, source, lineNo, "one 'base <package>'");
          base = parts[1];
        }
        case "module" -> {
          require(parts.length == 3, source, lineNo, "'module <name> <rank>'");
          require(!ranks.containsKey(parts[1]), source, lineNo, "a module declared once");
          ranks.put(parts[1], parseRank(source, lineNo, parts[2]));
        }
        case "allow" -> {
          require(parts.length == 3, source, lineNo, "'allow <from> <to>'");
          allowRows.add(new String[] {parts[1], parts[2], String.valueOf(lineNo)});
          allowed.add(parts[1] + "->" + parts[2]);
        }
        case "class", "package", "name", "layer" -> {
          require(parts.length >= 3, source, lineNo, "'" + directive + " <pattern> <module>'");
          RuleKind kind = RuleKind.valueOf(directive.toUpperCase(Locale.ROOT));
          require(ranks.containsKey(parts[2]), source, lineNo, "a declared module");
          if (kind == RuleKind.CLASS) {
            require(classRules.add(parts[1]), source, lineNo, "one class rule per class");
          }
          rules.add(
              new Rule(
                  lineNo,
                  kind,
                  parts[1],
                  parts[2],
                  parts.length == 4 ? parts[3] : "",
                  kind == RuleKind.NAME ? compile(source, lineNo, parts[1]) : null));
        }
        default -> require(false, source, lineNo, "a known directive");
      }
    }
    require(base != null, source, 0, "a 'base <package>' line");
    for (String[] row : allowRows) {
      int lineNo = Integer.parseInt(row[2]);
      require(ranks.containsKey(row[0]) && ranks.containsKey(row[1]), source, lineNo, "modules");
      require(
          ranks.get(row[0]).equals(ranks.get(row[1])) && !row[0].equals(row[1]),
          source,
          lineNo,
          "an allow row between two different modules of the same rank");
      require(
          !allowed.contains(row[1] + "->" + row[0]),
          source,
          lineNo,
          "no allow row in both directions");
    }
    return new DomainMap(source, base, ranks, allowed, rules);
  }

  /**
   * The root package every assigned class lies in.
   *
   * @return the base package
   */
  public @NotNull String basePackage() {
    return basePackage;
  }

  /**
   * The modules in declaration order.
   *
   * @return the module names
   */
  public @NotNull @Unmodifiable Set<String> modules() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(ranks.keySet()));
  }

  /**
   * The rules in their order of precedence.
   *
   * @return the rules
   */
  public @NotNull @Unmodifiable List<Rule> rules() {
    return rules;
  }

  /**
   * The rank of a module.
   *
   * @param module a declared module
   * @return its rank
   * @throws IllegalArgumentException when the module is not declared
   */
  public int rank(@NotNull String module) {
    Integer rank = ranks.get(module);
    if (rank == null) {
      throw new IllegalArgumentException("Module not in " + source + ": " + module);
    }
    return rank;
  }

  /**
   * Tells whether one module may depend on another: on itself, on a module of lower rank, or on a
   * same-rank module named by an {@code allow} row.
   *
   * @param from the depending module
   * @param to the module depended on
   * @return {@code true} when the dependency is allowed
   */
  public boolean mayDependOn(@NotNull String from, @NotNull String to) {
    return from.equals(to) || rank(from) > rank(to) || allowed.contains(from + "->" + to);
  }

  /**
   * Finds the rule that assigns a class: the first one that matches.
   *
   * @param fullName the fully qualified name of a top-level class
   * @return the assigning rule, empty when the class lies outside the base package or no rule
   *     matches
   */
  public @NotNull Optional<Rule> ruleFor(@NotNull String fullName) {
    int dot = fullName.lastIndexOf('.');
    String packageName = dot < 0 ? "" : fullName.substring(0, dot);
    String simpleName = fullName.substring(dot + 1);
    String relative;
    if (packageName.equals(basePackage)) {
      relative = "";
    } else if (packageName.startsWith(basePackage + ".")) {
      relative = packageName.substring(basePackage.length() + 1);
    } else {
      return Optional.empty();
    }
    for (Rule rule : rules) {
      if (rule.matches(simpleName, relative)) {
        return Optional.of(rule);
      }
    }
    return Optional.empty();
  }

  private static String stripImplSuffix(String simpleName) {
    return simpleName.endsWith(IMPL_SUFFIX) && simpleName.length() > IMPL_SUFFIX.length()
        ? simpleName.substring(0, simpleName.length() - IMPL_SUFFIX.length())
        : simpleName;
  }

  private static String firstSegment(String relativePackage) {
    int dot = relativePackage.indexOf('.');
    return dot < 0 ? relativePackage : relativePackage.substring(0, dot);
  }

  private static int parseRank(String source, int lineNo, String value) {
    try {
      int rank = Integer.parseInt(value);
      require(rank >= 0, source, lineNo, "a non-negative rank");
      return rank;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(source + ":" + lineNo + ": expected a rank", e);
    }
  }

  private static Pattern compile(String source, int lineNo, String regex) {
    try {
      return Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(source + ":" + lineNo + ": invalid pattern", e);
    }
  }

  private static void require(boolean condition, String source, int lineNo, String expected) {
    if (!condition) {
      throw new IllegalArgumentException(source + ":" + lineNo + ": expected " + expected);
    }
  }
}
