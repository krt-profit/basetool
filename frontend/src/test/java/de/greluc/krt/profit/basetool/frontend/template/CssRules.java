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

package de.greluc.krt.profit.basetool.frontend.template;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Splits a stylesheet into its innermost rules for the CSS contract guards; a rule inside an
 * {@code @media} or {@code @layer} block counts like a top-level one.
 */
final class CssRules {

  /** An innermost rule: its selector list and its declarations. */
  private static final Pattern RULE = Pattern.compile("([^{};]+)\\{([^{}]*)}");

  /** A run of whitespace, collapsed to one space before selectors and values are compared. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private CssRules() {}

  /**
   * One innermost rule of a stylesheet.
   *
   * @param selector the selector list, with each whitespace run collapsed to one space
   * @param body the declaration block between the braces
   */
  record Rule(@NotNull String selector, @NotNull String body) {

    /**
     * Splits the selector list into its comma-separated selectors.
     *
     * @return the selectors in source order, with single spaces
     */
    @NotNull
    @Unmodifiable
    List<String> entries() {
      return List.of(selector.split("\\s*,\\s*"));
    }

    /**
     * Tells whether the selector list contains a selector exactly.
     *
     * @param candidate the selector, with single spaces
     * @return whether one comma-separated entry equals it
     */
    boolean names(@NotNull String candidate) {
      return entries().contains(candidate);
    }
  }

  /**
   * Reads a stylesheet below the main resources root, without its comments, and splits it into its
   * innermost rules.
   *
   * @param path the stylesheet path relative to the resources root, such as {@code
   *     static/css/kernel/styles.css}
   * @return the rules in source order
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  static @NotNull @Unmodifiable List<Rule> read(@NotNull String path)
      throws IOException, URISyntaxException {
    return read(CssSources.mainResources().resolve(path));
  }

  /**
   * Reads a stylesheet, without its comments, and splits it into its innermost rules.
   *
   * @param sheet the stylesheet file
   * @return the rules in source order
   * @throws IOException if the stylesheet cannot be read
   */
  static @NotNull @Unmodifiable List<Rule> read(@NotNull Path sheet) throws IOException {
    return parse(CssSources.stripComments(Files.readString(sheet, StandardCharsets.UTF_8)));
  }

  /**
   * Splits a comment-free stylesheet into its innermost rules.
   *
   * @param css the stylesheet source without comments
   * @return the rules in source order
   */
  static @NotNull @Unmodifiable List<Rule> parse(@NotNull String css) {
    List<Rule> rules = new ArrayList<>();
    Matcher rule = RULE.matcher(css);
    while (rule.find()) {
      rules.add(new Rule(collapse(rule.group(1)), rule.group(2)));
    }
    return List.copyOf(rules);
  }

  /**
   * Lists the declaration blocks of the rules whose selector list names a selector exactly.
   *
   * @param rules the rules to search
   * @param selector the selector, with single spaces
   * @return the declaration blocks in source order
   */
  static @NotNull @Unmodifiable List<String> declarations(
      @NotNull List<Rule> rules, @NotNull String selector) {
    return rules.stream().filter(rule -> rule.names(selector)).map(Rule::body).toList();
  }

  /**
   * Tells whether a declaration block sets a property to a value, comparing the value with each
   * whitespace run collapsed to one space.
   *
   * @param body the declaration block
   * @param property the property name
   * @param value the expected value, with single spaces
   * @return whether the block's first declaration of the property has exactly that value
   */
  static boolean declares(@NotNull String body, @NotNull String property, @NotNull String value) {
    Matcher declaration = declaration(property).matcher(body);
    return declaration.find() && value.equals(collapse(declaration.group(1)));
  }

  /**
   * Tells whether a declaration block sets a property, whatever its value.
   *
   * @param body the declaration block
   * @param property the property name
   * @return whether the block declares the property
   */
  static boolean sets(@NotNull String body, @NotNull String property) {
    return declaration(property).matcher(body).find();
  }

  /**
   * Builds the pattern of one declaration of a property; its group 1 is the raw value.
   *
   * @param property the property name
   * @return the pattern, which does not match a longer property name that ends in this one
   */
  private static @NotNull Pattern declaration(@NotNull String property) {
    return Pattern.compile("(?<![-\\w])" + Pattern.quote(property) + "\\s*:\\s*([^;]+);");
  }

  /**
   * Trims a source fragment and collapses each whitespace run in it to one space.
   *
   * @param text the fragment
   * @return the normalised fragment
   */
  private static @NotNull String collapse(@NotNull String text) {
    return WHITESPACE.matcher(text.strip()).replaceAll(" ");
  }
}
