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
 * Replaces Thymeleaf's default {@link IStandardJavaScriptSerializer} on every {@link
 * StandardDialect} with one that serializes {@code java.time} types in {@code [[${dto}]]} inline
 * expressions.
 *
 * <p>Keeps Thymeleaf's HTML-safe escape set, including {@code U+2028} / {@code U+2029}, so string
 * values cannot break out of a {@code <script>} block.
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
   * Serializer that delegates primitive values to Thymeleaf's {@link StandardJavaScriptSerializer},
   * keeping their output unchanged, and serializes complex objects with a JSR-310-aware {@link
   * ObjectMapper}.
   */
  static final class JavaTimeAwareJavaScriptSerializer implements IStandardJavaScriptSerializer {

    private final ObjectMapper mapper;
    private final IStandardJavaScriptSerializer delegate;

    JavaTimeAwareJavaScriptSerializer() {
      ObjectMapper m = new ObjectMapper();
      m.registerModule(new JavaTimeModule());
      m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      m.getFactory().setCharacterEscapes(new ThymeleafCompatibleEscapes());
      m.getFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
      this.mapper = m;
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
   * Character-escape table mirroring Thymeleaf's {@code JacksonThymeleafEscapes}: {@code <}, {@code
   * >}, {@code &}, {@code '}, {@code "}, {@code /}, {@code U+2028} and {@code U+2029} are escaped.
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
      return switch (ch) {
        case '<', '>', '&', '\'', '"' -> jsUnicodeEscape(ch);
        case '/' -> new SerializedString("\\/");
        case 0x2028, 0x2029 -> jsUnicodeEscape(ch);
        default -> null;
      };
    }

    @NotNull
    private static SerializedString jsUnicodeEscape(int codePoint) {
      return new SerializedString(String.format("\\u%04X", codePoint));
    }
  }
}
