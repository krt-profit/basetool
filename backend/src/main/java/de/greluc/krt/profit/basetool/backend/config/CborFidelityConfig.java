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

import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.cbor.JacksonCborHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * Makes CBOR encode the same document JSON does (REQ-API-011, ADR-0161 §8.5).
 *
 * <p><strong>Without this class the second encoding is a second contract</strong>, and the
 * difference is invisible until it reaches a screen. Jackson's {@code UUIDSerializer} asks the
 * generator {@code canWriteBinaryNatively()} and writes the sixteen raw bytes when the answer is
 * yes. JSON answers no and emits {@code "00000000-0000-0000-0000-000000000001"}; CBOR answers yes
 * and emits binary, which anything that then treats the value as text renders as {@code
 * AAAAAAAAAAAAAAAAAAAAAQ==}. That is base64 of those bytes, and it is what appeared in a {@code
 * <select>} value, in picker option ids and in table row keys — failing five end-to-end write flows
 * on all three browsers while every unit test stayed green.
 *
 * <p>209 properties of the frozen external contract are {@code type: string, format: uuid}
 * (REQ-API-009). A representation that silently stops being a string on one encoding is exactly the
 * in-place shape change that requirement exists to forbid, so the fix belongs at the encoder rather
 * than at each reader.
 *
 * <p><b>Only the UUID is overridden.</b> The other divergence the fidelity test found — {@code
 * BigDecimal} arriving as a decimal node in CBOR where JSON's reader had already widened it to a
 * double — is left alone deliberately: it is CBOR being <em>more</em> faithful, not less, and every
 * consumer binds it to a declared type rather than reading the tree. It is recorded in {@code
 * CborJsonFidelityTest} rather than papered over, so a future reader meets it as a known difference
 * instead of a surprise.
 */
@Configuration
public class CborFidelityConfig implements WebMvcConfigurer {

  /**
   * Replaces the auto-detected CBOR converter with one whose mapper writes UUIDs as strings.
   *
   * <p>Registered through the builder rather than by post-processing a converter list, because the
   * builder is where Spring 7 expects a format's converter to be chosen and it keeps the
   * auto-detected JSON, Smile and XML converters exactly as they were.
   *
   * @param builder the server-side converter builder Spring hands every configurer.
   */
  @Override
  public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
    builder.withCborConverter(new JacksonCborHttpMessageConverter(faithfulCborMapper()));
  }

  /**
   * Builds the CBOR mapper, differing from the default in exactly one serializer.
   *
   * @return a mapper that writes {@link UUID} the way JSON does.
   */
  private static CBORMapper faithfulCborMapper() {
    SimpleModule module = new SimpleModule("cbor-uuid-as-string");
    module.addSerializer(UUID.class, new UuidAsStringSerializer());
    return CBORMapper.builder().addModule(module).build();
  }

  /**
   * Writes a {@link UUID} as its canonical textual form, whatever the generator could do natively.
   *
   * <p>Deliberately not {@code writeBinary}: the point is to be indistinguishable from the JSON
   * encoding, not to be efficient. A UUID costs 36 bytes as text against 16 as binary, and paying
   * those 20 bytes is what keeps one contract instead of two.
   */
  private static final class UuidAsStringSerializer extends StdSerializer<UUID> {

    /** Binds the serializer to the type it handles. */
    UuidAsStringSerializer() {
      super(UUID.class);
    }

    /**
     * Writes {@code value} as a string.
     *
     * @param value the identifier to write; never {@code null} here, Jackson handles nulls itself.
     * @param generator the CBOR generator.
     * @param context the serialization context, unused.
     */
    @Override
    public void serialize(UUID value, JsonGenerator generator, SerializationContext context) {
      generator.writeString(value.toString());
    }
  }
}
