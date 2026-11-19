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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders {@code [[${...}]]} JavaScript inlining through the application's template engine and pins
 * what an inline script receives (REQ-FE-022): strings survive the round trip, the literal carries
 * no character that could end the {@code <script>} element or a template literal, {@code java.time}
 * values arrive as ISO-8601 strings, and objects, maps and lists keep their shape.
 */
@SpringBootTest
class JavaScriptInliningRenderTest {

  private static final String HOSTILE_TEXT =
      "</script><script>alert('x')</script><!-- & \"q\" \\ /end";

  private static final String NON_ASCII_TEXT = "Größe ä € \uD83D\uDE00";

  private static final String LINE_SEPARATORS = "a\u2028b\u2029c";

  private static final String TEMPLATE_LITERAL = "`${x}` and $y";

  private static final String CONTROL_CHARS = "tab\tnl\ncr\r nul\u0000 del\u007F";

  private static final Pattern LITERAL_LINE =
      Pattern.compile("^\\s*v\\.(\\w+) = (.*);$", Pattern.MULTILINE);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private ITemplateEngine templateEngine;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private String html;

  private Map<String, String> literals;

  /**
   * A tier whose {@link #toString()} differs from its name.
   *
   * <p>Serialized as part of a record and on its own.
   */
  enum Tier {
    /** The first tier. */
    FIRST,
    /** The second tier. */
    SECOND;

    @Override
    public String toString() {
      return "label-" + name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A page-bootstrap DTO shape with a {@code null} field, a timestamp, a nested list and an enum.
   *
   * @param name the display name
   * @param missing an absent count
   * @param at the timestamp
   * @param tags the tags
   * @param tier the tier
   */
  record Line(String name, Integer missing, Instant at, List<String> tags, Tier tier) {}

  /**
   * The shape of a refinery yield bonus as the refinery pages inline it.
   *
   * @param percent the current bonus
   * @param weekAveragePercent the 7-day average
   * @param monthAveragePercent the 30-day average
   */
  record Bonus(Integer percent, Integer weekAveragePercent, Integer monthAveragePercent) {}

  /**
   * Builds the harness variables: every value shape the templates inline, plus hostile strings.
   *
   * @return the variables by name, in harness order
   */
  @NotNull
  static Map<String, Object> variables() {
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("hostileText", HOSTILE_TEXT);
    vars.put("nonAsciiText", NON_ASCII_TEXT);
    vars.put("lineSeparators", LINE_SEPARATORS);
    vars.put("templateLiteral", TEMPLATE_LITERAL);
    vars.put("controlChars", CONTROL_CHARS);
    vars.put("integerValue", 42);
    vars.put("decimalValue", new BigDecimal("12.50"));
    vars.put("doubleValue", 1.5);
    vars.put("flag", true);
    vars.put("nothing", null);
    vars.put("character", 'x');
    vars.put("enumValue", Tier.SECOND);
    vars.put("uuid", UUID.fromString("0b6f3c9e-1d2a-4c5b-8e7f-9a0b1c2d3e4f"));
    vars.put("instant", Instant.parse("2026-05-15T13:45:30Z"));
    vars.put("localDate", LocalDate.of(2026, 5, 15));
    vars.put("localDateTime", LocalDateTime.of(2026, 5, 15, 13, 45, 30));
    vars.put(
        "offsetDateTime", OffsetDateTime.of(2026, 5, 15, 13, 45, 30, 0, ZoneOffset.ofHours(2)));
    vars.put("duration", Duration.ofMinutes(90));
    vars.put(
        "record",
        new Line(
            "Größe <b>&</b> " + LINE_SEPARATORS,
            null,
            Instant.parse("2026-05-15T13:45:30Z"),
            List.of("a", TEMPLATE_LITERAL),
            Tier.FIRST));
    Map<String, Bonus> recordMap = new LinkedHashMap<>();
    recordMap.put("Quantanium", new Bonus(12, 10, null));
    recordMap.put("Laranite", new Bonus(-3, null, -1));
    vars.put("recordMap", recordMap);
    Map<String, Object> qualities = new LinkedHashMap<>();
    qualities.put("m-1", "T2");
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("id", "i-1");
    line.put("manufactured", 0);
    line.put("gameItemName", "Größe </script>");
    line.put("amount", 3);
    line.put("parentId", null);
    line.put("qualities", qualities);
    vars.put("lineList", List.of(line));
    return vars;
  }

  /** Renders the harness once per test, German. */
  @BeforeEach
  void render() {
    Context context = new Context(Locale.GERMAN);
    context.setVariables(variables());
    html = templateEngine.process("javascript-inlining-harness", context);
    literals = new LinkedHashMap<>();
    Matcher m = LITERAL_LINE.matcher(html);
    while (m.find()) {
      literals.put(m.group(1), m.group(2));
    }
  }

  /** Every string, hostile or not, reaches the script as the exact value the model held. */
  @Test
  void everyStringSurvivesTheRoundTrip() {
    assertThat(string("hostileText")).isEqualTo(HOSTILE_TEXT);
    assertThat(string("nonAsciiText")).isEqualTo(NON_ASCII_TEXT);
    assertThat(string("lineSeparators")).isEqualTo(LINE_SEPARATORS);
    assertThat(string("templateLiteral")).isEqualTo(TEMPLATE_LITERAL);
    assertThat(string("controlChars")).isEqualTo(CONTROL_CHARS);
    assertThat(string("message")).isNotBlank();
    assertThat(string("character")).isEqualTo("x");
    assertThat(string("uuid")).isEqualTo("0b6f3c9e-1d2a-4c5b-8e7f-9a0b1c2d3e4f");
  }

  /**
   * No literal carries {@code <}, {@code >}, {@code &}, a raw {@code U+2028} / {@code U+2029}, a
   * backtick or a dollar sign before a brace, and none carries a non-ASCII character at all, so no
   * value can close the {@code <script>} element, open an HTML comment or end a template literal.
   */
  @Test
  void noLiteralCarriesACharacterThatCouldBreakOut() {
    assertThat(literals).hasSize(variables().size() + 2);
    literals.forEach((name, literal) ->
        assertThat(literal)
            .as("literal of %s", name)
            .doesNotContain("<", ">", "&", "`", "${", "\u2028", "\u2029")
            .matches("[\\x20-\\x7E]*"));
    assertThat(literals.get("hostileText")).contains("\\/");
  }

  /** The harness's script element ends once, at its own closing tag, and the page goes on. */
  @Test
  void theScriptElementIsNotBrokenOut() {
    assertThat(html.split("</script", -1)).hasSize(2);
    assertThat(html).doesNotContain("<!--");
    assertThat(html.indexOf("v.end = 'END';"))
        .isPositive()
        .isLessThan(html.indexOf("</script>"))
        .isLessThan(html.indexOf("<p id=\"after\">AFTER</p>"));
  }

  /** Numbers, booleans and {@code null} keep their JavaScript form; the fallback is dropped. */
  @Test
  void numbersBooleansAndNullKeepTheirJavaScriptForm() {
    assertThat(literals)
        .containsEntry("integerValue", "42")
        .containsEntry("decimalValue", "12.50")
        .containsEntry("doubleValue", "1.5")
        .containsEntry("flag", "true")
        .containsEntry("nothing", "null");
  }

  /** {@code java.time} values arrive as ISO-8601 strings, never as numeric timestamps. */
  @Test
  void javaTimeValuesArriveAsIsoStrings() {
    assertThat(literals)
        .containsEntry("instant", "\"2026-05-15T13:45:30Z\"")
        .containsEntry("localDate", "\"2026-05-15\"")
        .containsEntry("localDateTime", "\"2026-05-15T13:45:30\"")
        .containsEntry("offsetDateTime", "\"2026-05-15T13:45:30+02:00\"")
        .containsEntry("duration", "\"PT1H30M\"");
  }

  /**
   * An enum arrives as its {@link Object#toString()}, on its own and inside an object; for an enum
   * that does not override it, that is its name.
   */
  @Test
  void anEnumArrivesAsItsToString() {
    assertThat(string("enumValue")).isEqualTo("label-second");
    assertThat(tree("record").get("tier").asString()).isEqualTo("label-first");
  }

  /** Records, maps of records and lists of maps keep every property, {@code null}s included. */
  @Test
  void objectsMapsAndListsKeepTheirShape() {
    assertThat(tree("record"))
        .isEqualTo(
            tree(
                """
                {"name":"Größe <b>&</b> a\\u2028b\\u2029c","missing":null,
                 "at":"2026-05-15T13:45:30Z","tags":["a","`${x}` and $y"],"tier":"label-first"}
                """));
    assertThat(tree("recordMap"))
        .isEqualTo(
            tree(
                """
                {"Quantanium":{"percent":12,"weekAveragePercent":10,"monthAveragePercent":null},
                 "Laranite":{"percent":-3,"weekAveragePercent":null,"monthAveragePercent":-1}}
                """));
    assertThat(tree("lineList"))
        .isEqualTo(
            tree(
                """
                [{"id":"i-1","manufactured":0,"gameItemName":"Größe </script>","amount":3,
                  "parentId":null,"qualities":{"m-1":"T2"}}]
                """));
  }

  /** Map entries keep the insertion order the page scripts iterate in. */
  @Test
  void mapEntriesKeepTheirInsertionOrder() {
    assertThat(literals.get("recordMap")).startsWith("{\"Quantanium\":");
    assertThat(literals.get("lineList"))
        .startsWith("[{\"id\":\"i-1\",\"manufactured\":0,\"gameItemName\":");
  }

  /** Rendering into a caller's writer leaves it open, so the response can still be written. */
  @Test
  void theCallersWriterStaysOpen() {
    Context context = new Context(Locale.GERMAN);
    context.setVariables(variables());
    CloseTrackingWriter writer = new CloseTrackingWriter();
    templateEngine.process("javascript-inlining-harness", context, writer);
    assertThat(writer.closed).isFalse();
    assertThat(writer.toString()).contains("<p id=\"after\">AFTER</p>");
  }

  /**
   * Reads one literal as a JavaScript string value.
   *
   * @param name the harness variable
   * @return the decoded string
   */
  private String string(String name) {
    JsonNode node = tree(name);
    assertThat(node.isString()).as("literal of %s is a string", name).isTrue();
    return node.asString();
  }

  /**
   * Parses one literal, or a JSON text, into a tree.
   *
   * @param nameOrJson a harness variable name, or a JSON text when it is none
   * @return the parsed tree
   */
  private JsonNode tree(String nameOrJson) {
    String literal = literals.get(nameOrJson);
    if (literal == null) {
      return JSON.readTree(nameOrJson);
    }
    return JSON.readTree(literal);
  }

  /** A writer that records whether anyone closed it. */
  private static final class CloseTrackingWriter extends StringWriter {

    private boolean closed;

    @Override
    public void close() {
      closed = true;
    }
  }
}
