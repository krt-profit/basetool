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
import org.jetbrains.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.cbor.JacksonCborHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * Makes CBOR responses encode the same document as JSON (REQ-API-011, ADR-0161).
 *
 * <p>UUIDs are written as strings instead of raw binary. The CBOR converter is write-only: a
 * request body sent as {@code application/cbor} is answered with {@code 415}, since only JSON
 * reading applies the project's input normalization.
 */
@Configuration
public class CborFidelityConfig implements WebMvcConfigurer {

  /**
   * Replaces the auto-detected CBOR converter with a write-only one whose mapper writes UUIDs as
   * strings, leaving the other converters unchanged.
   *
   * @param builder the server-side converter builder Spring hands every configurer.
   */
  @Override
  public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
    builder.withCborConverter(new WriteOnlyCborConverter(faithfulCborMapper()));
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
   * The CBOR converter with reading disabled, so a CBOR request body yields {@code 415 Unsupported
   * Media Type}.
   */
  private static final class WriteOnlyCborConverter extends JacksonCborHttpMessageConverter {

    /**
     * Builds the converter over the faithful mapper.
     *
     * @param mapper the CBOR mapper to write with.
     */
    WriteOnlyCborConverter(CBORMapper mapper) {
      super(mapper);
    }

    /**
     * Always refuses to read.
     *
     * @param type the target type, unused.
     * @param mediaType the request's content type, unused.
     * @return {@code false}, always.
     */
    @Override
    public boolean canRead(ResolvableType type, @Nullable MediaType mediaType) {
      return false;
    }
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
