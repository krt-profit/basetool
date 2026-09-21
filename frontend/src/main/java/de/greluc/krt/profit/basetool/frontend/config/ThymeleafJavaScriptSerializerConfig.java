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

package de.greluc.krt.profit.basetool.frontend.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.Writer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.dialect.IDialect;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.standard.StandardDialect;
import org.thymeleaf.standard.serializer.IStandardJavaScriptSerializer;
import org.thymeleaf.standard.serializer.StandardJavaScriptSerializer;

/**
 * Replaces Thymeleaf's default {@link IStandardJavaScriptSerializer} with one that knows about
 * {@code java.time} types so {@code [[${dto}]]} inline expressions on objects carrying {@link
 * java.time.Instant}, {@link java.time.OffsetDateTime} or {@link java.time.LocalDateTime} render
 * without blowing up at template-output time.
 *
 * <p><b>Why this exists.</b> Thymeleaf 3.1.x ships {@code
 * org.thymeleaf.standard.serializer.StandardJavaScriptSerializer} with an inner {@code
 * JacksonStandardJavaScriptSerializer} that instantiates a bare {@code new ObjectMapper()} — no
 * {@code JavaTimeModule} registered. Spring Boot 4 has moved its own primary {@code ObjectMapper}
 * to Jackson 3 ({@code tools.jackson.core}), so the Jackson 2 instance Thymeleaf uses for JS
 * inlining never picks up the JSR-310 module transitively either. The promotion admin pages render
 * {@code Map<UUID, List<PromotionCategoryDto>>} payloads whose DTOs carry {@code createdAt} /
 * {@code updatedAt} as {@link java.time.Instant}, so the omission surfaced as an {@code
 * InvalidDefinitionException} during template rendering and a 500 to the user. This configuration
 * plugs a custom serializer onto every {@link StandardDialect} attached to the Spring template
 * engine so the failure stops happening once and for all instead of being worked around on each
 * template.
 *
 * <p><b>Character escapes.</b> The replacement keeps Thymeleaf's HTML-safe escape set so {@code <},
 * {@code >}, {@code &}, {@code '}, {@code "}, {@code /} and the JavaScript-breaking Unicode line
 * separators {@code U+2028} / {@code U+2029} continue to be escaped — without those a string value
 * containing a stray {@code </script>} or a Twitter-style line separator could break out of the
 * surrounding {@code <script>} block and turn a benign DTO field into stored XSS.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ThymeleafJavaScriptSerializerConfig {

  private final SpringTemplateEngine templateEngine;

  /**
   * Plugs the JSR-310-aware serializer onto every {@link StandardDialect} attached to the engine.
   * Runs after Spring Boot's auto-configuration has wired the engine's default dialect, so the call
   * overrides the default {@code JacksonStandardJavaScriptSerializer} that would otherwise blow up
   * on {@code java.time.*} values.
   */
  @PostConstruct
  public void registerJavaTimeAwareSerializer() {
    IStandardJavaScriptSerializer serializer = new JavaTimeAwareJavaScriptSerializer();
    int replaced = 0;
    for (IDialect dialect : templateEngine.getDialects()) {
      if (dialect instanceof StandardDialect standard) {
        standard.setJavaScriptSerializer(serializer);
        replaced++;
      }
    }
    log.info("Configured JSR-310-aware JavaScript serializer on {} Thymeleaf dialect(s)", replaced);
  }

  /**
   * Hybrid serializer that delegates to Thymeleaf's stock {@link StandardJavaScriptSerializer} for
   * primitive values (String / Number / Boolean / null) so the byte-for-byte JS output stays
   * unchanged for the vast majority of inline expressions (typed-out i18n keys like {@code
   * /*[[#{...}]]*\/}), and only falls back to a JSR-310-aware Jackson {@link ObjectMapper} for
   * complex objects where the {@link java.time.Instant} / {@link java.time.OffsetDateTime} support
   * is actually needed. Without the delegation path the replacement mapper produced a subtly
   * different escape output for everyday String values that caused Thymeleaf's JS-inline
   * comment-stripper to bail out mid-render (see the regression captured by the {@code
   * OperationPageControllerMvcTest} suite). Package-private so the dedicated unit test can drive it
   * directly without spinning up a Spring context.
   */
  static final class JavaTimeAwareJavaScriptSerializer implements IStandardJavaScriptSerializer {

    private final ObjectMapper mapper;
    private final IStandardJavaScriptSerializer delegate;

    JavaTimeAwareJavaScriptSerializer() {
      ObjectMapper m = new ObjectMapper();
      // Render java.time.* as ISO-8601 strings — matches CLAUDE.md ("All times in UTC") and the
      // TimezoneSerializationTest contract enforced elsewhere in the codebase.
      m.registerModule(new JavaTimeModule());
      m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      m.getFactory().setCharacterEscapes(new ThymeleafCompatibleEscapes());
      // Jackson's writeValue(Writer, ...) closes the writer by default when AUTO_CLOSE_TARGET
      // is on. Thymeleaf shares ONE writer across the entire template render, so closing it
      // after an inline expression silently truncates everything that follows in the same
      // <script> block (including the event-handler registration at the bottom of the
      // promotion-admin-rank-requirements template). Disabling the feature keeps the writer
      // open so Thymeleaf can continue emitting the rest of the page after the JSON island.
      m.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
      this.mapper = m;
      // Reuse Thymeleaf's stock Jackson-backed serializer for the primitive path so that
      // pure-String
      // values (the most common case in templates) get byte-for-byte identical output to what
      // ships before this configuration was introduced.
      this.delegate = new StandardJavaScriptSerializer(true);
    }

    @Override
    public void serializeValue(Object object, Writer writer) {
      if (isPrimitiveLike(object)) {
        delegate.serializeValue(object, writer);
        return;
      }
      try {
        mapper.writeValue(writer, object);
      } catch (IOException e) {
        throw new TemplateProcessingException(
            "An exception was raised while trying to serialize value to JavaScript using Jackson",
            e);
      }
    }

    /**
     * The "simple types" path. These values cannot transitively reference a {@link
     * java.time.Instant}, so delegating to Thymeleaf's stock serializer preserves the exact JS
     * output (escape style, quoting, fallback-removal compatibility) that the engine has produced
     * for years.
     */
    private static boolean isPrimitiveLike(Object o) {
      return o == null
          || o instanceof CharSequence
          || o instanceof Number
          || o instanceof Boolean
          || o instanceof Character
          || o instanceof Enum<?>;
    }
  }

  /**
   * Character-escape table that mirrors Thymeleaf's stock {@code JacksonThymeleafEscapes} so the
   * replacement serializer keeps the same HTML-safety properties: HTML-relevant ASCII characters
   * ({@code <}, {@code >}, {@code &}, {@code '}, {@code "}, {@code /}) and the two
   * JavaScript-breaking Unicode line separators ({@code U+2028}, {@code U+2029}) are escaped as
   * {@code \\uXXXX} / {@code \\/} so values containing a stray {@code </script>} or Twitter-style
   * line separator cannot break out of the surrounding {@code <script>} context.
   */
  private static final class ThymeleafCompatibleEscapes extends CharacterEscapes {

    private static final int[] ASCII_ESCAPES;

    static {
      int[] escapes = standardAsciiEscapesForJSON();
      escapes['<'] = ESCAPE_CUSTOM;
      escapes['>'] = ESCAPE_CUSTOM;
      escapes['&'] = ESCAPE_CUSTOM;
      escapes['\''] = ESCAPE_CUSTOM;
      escapes['"'] = ESCAPE_CUSTOM;
      escapes['/'] = ESCAPE_CUSTOM;
      ASCII_ESCAPES = escapes;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return ASCII_ESCAPES;
    }

    @Nullable
    @Override
    public SerializableString getEscapeSequence(int ch) {
      // The escape outputs (e.g. the 6-char string "'") are intentionally Unicode escape
      // sequences in JSON, NOT shorter Java escapes like \". They have to land on the wire as
      // literal backslash-u-0027 so the surrounding <script> block stays well-formed even when
      // the original value is an apostrophe. We construct them via jsUnicodeEscape(int) instead
      // of literal "'"/""" string literals so Checkstyle's IllegalTokenText rule
      // (which suggests \' / \" as shorter alternatives) does not flag the source — the
      // shorter Java escapes would change the *Java* literal but produce a wrong JSON output.
      return switch (ch) {
        case '<', '>', '&', '\'', '"' -> jsUnicodeEscape(ch);
        case '/' -> new SerializedString("\\/");
        case 0x2028, 0x2029 -> jsUnicodeEscape(ch);
        default -> null;
      };
    }

    @NotNull
    private static SerializedString jsUnicodeEscape(int codePoint) {
      // Uppercase hex to match Thymeleaf's stock JacksonThymeleafEscapes output byte-for-byte;
      // mixing cases would surface as a diff in any snapshot test that compares rendered HTML
      // against a recorded fixture.
      return new SerializedString(String.format("\\u%04X", codePoint));
    }
  }
}
