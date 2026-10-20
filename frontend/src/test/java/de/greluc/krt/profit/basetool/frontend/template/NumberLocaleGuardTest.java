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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.support.ClasspathAssets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps every number in the page language (REQ-UI-037, REQ-UI-038): no native number input, every
 * input that takes a number is a live-formatted number field, and no template or script prints a
 * number with a fixed separator.
 */
class NumberLocaleGuardTest {

  /** An input element, its attributes possibly spread over several lines. */
  private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.DOTALL);

  /** A native number input, in markup or built by a script. */
  private static final Pattern NUMBER_TYPE =
      Pattern.compile(
          "type\\s*=\\s*[\"']number[\"']|\\.type\\s*=\\s*[\"']number[\"']|\\[type=[\"']?number");

  /** An attribute that marks an input as taking a number. */
  private static final Pattern NUMERIC_HINT =
      Pattern.compile(
          "\\s(?:th:)?(?:min|max|step)=|inputmode=[\"'](?:numeric|decimal)[\"']|data-scu-decimal");

  /** Input types whose `min`, `max` or `step` describe something other than a number. */
  private static final Pattern NON_NUMBER_TYPE =
      Pattern.compile(
          "type=[\"'](?:hidden|checkbox|radio|range|date|time|datetime-local|month|week|file|color)[\"']");

  /** A Thymeleaf number format with a fixed thousands or decimal separator. */
  private static final Pattern FIXED_SEPARATOR =
      Pattern.compile("#numbers\\.format\\w+\\([^\\n]*'(?:POINT|COMMA|WHITESPACE)'");

  /** A locale hard-coded in a script. */
  private static final Pattern FIXED_LOCALE = Pattern.compile("[\"']de-DE[\"']|[\"']en-US[\"']");

  @Test
  void noTemplateOrScriptUsesANativeNumberInput() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : files(ClasspathAssets.templates(), ".html")) {
      collect(file, NUMBER_TYPE, offenders);
    }
    for (Path file : files(ClasspathAssets.scripts(), ".js")) {
      collect(file, NUMBER_TYPE, offenders);
    }
    assertThat(offenders)
        .as("a number input is <input type=\"text\" data-number-input> (REQ-UI-037)")
        .isEmpty();
  }

  @Test
  void everyTemplateInputThatTakesANumberIsANumberField() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : files(ClasspathAssets.templates(), ".html")) {
      String html = Files.readString(file, StandardCharsets.UTF_8);
      Matcher input = INPUT.matcher(html);
      while (input.find()) {
        String tag = input.group();
        if (NUMERIC_HINT.matcher(tag).find()
            && !NON_NUMBER_TYPE.matcher(tag).find()
            && !tag.contains("data-number-input")) {
          offenders.add(file.getFileName() + ":" + line(html, input.start()));
        }
      }
    }
    assertThat(offenders)
        .as("an input with min, max, step or a numeric inputmode carries data-number-input")
        .isEmpty();
  }

  @Test
  void noTemplateFormatsANumberWithAFixedSeparator() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : files(ClasspathAssets.templates(), ".html")) {
      collect(file, FIXED_SEPARATOR, offenders);
    }
    assertThat(offenders)
        .as("#numbers.format* takes 'DEFAULT' so the page language decides (REQ-UI-038)")
        .isEmpty();
  }

  @Test
  void noScriptHardCodesALocaleForNumbers() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (Path file : files(ClasspathAssets.scripts(), ".js")) {
      collect(file, FIXED_LOCALE, offenders);
    }
    assertThat(offenders)
        .as("a script formats with document.documentElement.lang (REQ-UI-038)")
        .isEmpty();
  }

  @Test
  void theGuardsScanTheRealTemplatesAndScripts() throws IOException {
    assertThat(files(ClasspathAssets.templates(), ".html")).hasSizeGreaterThan(100);
    assertThat(files(ClasspathAssets.scripts(), ".js")).hasSizeGreaterThan(100);
    int numberFields = 0;
    for (Path file : files(ClasspathAssets.templates(), ".html")) {
      Matcher input = INPUT.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (input.find()) {
        if (input.group().contains("data-number-input")) {
          numberFields++;
        }
      }
    }
    assertThat(numberFields).as("number fields found in the templates").isGreaterThan(100);
  }

  @Test
  void theGuardsRecogniseWhatTheyForbid() {
    assertThat(NUMBER_TYPE.matcher("<input type=\"number\" min=\"1\">").find()).isTrue();
    assertThat(NUMBER_TYPE.matcher("input.type = 'number';").find()).isTrue();
    assertThat(NUMBER_TYPE.matcher("querySelector('input[type=\"number\"]')").find()).isTrue();
    assertThat(NUMERIC_HINT.matcher("<input type=\"text\" step=\"0.001\">").find()).isTrue();
    assertThat(NUMERIC_HINT.matcher("<input type=\"text\" maxlength=\"20\">").find()).isFalse();
    assertThat(NON_NUMBER_TYPE.matcher("<input type=\"date\" min=\"2026-01-01\">").find()).isTrue();
    assertThat(
            FIXED_SEPARATOR
                .matcher("${#numbers.formatInteger(@moneyFormat.round(x), 1, 'POINT')}")
                .find())
        .isTrue();
    assertThat(FIXED_SEPARATOR.matcher("${#numbers.formatInteger(x, 1, 'DEFAULT')}").find())
        .isFalse();
    assertThat(FIXED_LOCALE.matcher("n.toLocaleString('de-DE')").find()).isTrue();
  }

  /**
   * Adds every line of a file that matches a pattern.
   *
   * @param file the file to scan
   * @param pattern the forbidden pattern
   * @param offenders the list to add {@code file:line} entries to
   * @throws IOException if the file cannot be read
   */
  private static void collect(Path file, Pattern pattern, List<String> offenders)
      throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int i = 0; i < lines.size(); i++) {
      if (pattern.matcher(lines.get(i)).find()) {
        offenders.add(file.getFileName() + ":" + (i + 1));
      }
    }
  }

  /**
   * Counts the line an offset of a text lies on.
   *
   * @param text the text
   * @param offset the character offset
   * @return the one-based line number
   */
  private static int line(String text, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (text.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * Lists the files below a root with a given suffix, sorted.
   *
   * @param root the directory to walk
   * @param suffix the file name suffix
   * @return the files
   * @throws IOException if the tree cannot be walked
   */
  private static List<Path> files(Path root, String suffix) throws IOException {
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .filter(p -> !p.toString().replace('\\', '/').contains("/vendor/"))
          .sorted()
          .toList();
    }
  }
}
