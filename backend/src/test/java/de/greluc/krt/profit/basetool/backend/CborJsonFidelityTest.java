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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.cbor.JacksonCborHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * Verifies that the registered CBOR converter encodes the same document as the JSON converter
 * (REQ-API-011), notably UUIDs as strings rather than native binary.
 *
 * <p>Encodes one value carrying every type whose wire form could diverge through the two converters
 * Spring actually registered, without going through an endpoint.
 */
@SpringBootTest
@ActiveProfiles("test")
class CborJsonFidelityTest {

  @Autowired private RequestMappingHandlerAdapter handlerAdapter;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * One value carrying every type whose CBOR form could differ from its JSON form.
   *
   * @param id a UUID, which CBOR would otherwise write as binary
   * @param ids a list of UUIDs, the shape most responses use
   * @param money a {@code BigDecimal}, which CBOR can encode as a native decimal fraction
   * @param count a long, to catch a width or tagging difference
   * @param when an {@code Instant}, which may render as a number or a string
   * @param kind an enum, whose name must survive as a name
   * @param flag a boolean control value
   * @param text a string control value
   */
  private record WireSample(
      UUID id,
      List<UUID> ids,
      BigDecimal money,
      long count,
      Instant when,
      Kind kind,
      boolean flag,
      String text) {}

  /** A two-constant enum, enough to prove a name is written as a name. */
  private enum Kind {
    /** First constant. */
    CREW,
    /** Second constant. */
    MISSION
  }

  @Test
  @DisplayName("the CBOR converter encodes the same document as the JSON converter")
  void cborEncodesTheSameDocumentAsJson() throws Exception {
    WireSample sample =
        new WireSample(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            List.of(UUID.fromString("ad04d0d2-ba6d-4590-bf63-eab68b3554bd")),
            new BigDecimal("1234.5600"),
            9_007_199_254_740_993L,
            Instant.parse("2026-09-10T08:24:00Z"),
            Kind.MISSION,
            true,
            "Umbuchen");

    JsonNode viaJson =
        JsonMapper.builder()
            .build()
            .readTree(
                encode(JacksonJsonHttpMessageConverter.class, MediaType.APPLICATION_JSON, sample));
    JsonNode viaCbor =
        CBORMapper.builder()
            .build()
            .readTree(
                encode(JacksonCborHttpMessageConverter.class, MediaType.APPLICATION_CBOR, sample));

    assertThat(normalise(viaCbor))
        .as(
            "CBOR and JSON must decode to the same document. A UUID arriving as a binary node is"
                + " what put base64 ids into the DOM and failed five E2E write flows; anything else"
                + " that diverges here would do the same to whatever reads it")
        .isEqualTo(normalise(viaJson));
  }

  @Test
  @DisplayName("a decimal keeps its scale in CBOR and loses it in JSON, at equal value")
  void decimalsDifferInScaleButNotInValue() throws Exception {
    BigDecimal money = new BigDecimal("1234.5600");

    JsonNode fromJson =
        JsonMapper.builder()
            .build()
            .readTree(
                encode(JacksonJsonHttpMessageConverter.class, MediaType.APPLICATION_JSON, money));
    JsonNode fromCbor =
        CBORMapper.builder()
            .build()
            .readTree(
                encode(JacksonCborHttpMessageConverter.class, MediaType.APPLICATION_CBOR, money));

    assertThat(fromCbor.decimalValue()).isEqualByComparingTo(fromJson.decimalValue());
    assertThat(fromCbor.decimalValue().scale())
        .as("CBOR preserves the scale the object carried")
        .isEqualTo(4);
    assertThat(fromJson.decimalValue().scale())
        .as("the JSON reader widens to a double first, so the scale is whatever printing produced")
        .isEqualTo(2);
  }

  /**
   * Rewrites every numeric node to a canonical decimal so numbers compare by value, since the two
   * readers may produce different node types for the same decimal.
   *
   * @param node the tree to normalise; modified in place
   * @return the same tree with numbers canonicalised
   */
  private static JsonNode normalise(JsonNode node) {
    if (node.isObject()) {
      tools.jackson.databind.node.ObjectNode object = (tools.jackson.databind.node.ObjectNode) node;
      for (String name : object.propertyNames().stream().toList()) {
        object.set(name, normalise(object.get(name)));
      }
    } else if (node.isArray()) {
      tools.jackson.databind.node.ArrayNode array = (tools.jackson.databind.node.ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, normalise(array.get(i)));
      }
    } else if (node.isNumber() && !node.isIntegralNumber()) {
      return tools.jackson.databind.node.DecimalNode.valueOf(
          node.decimalValue().stripTrailingZeros());
    }
    return node;
  }

  @Test
  @DisplayName("a UUID is a string on the wire, in both encodings")
  void aUuidIsAStringInBothEncodings() throws Exception {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

    JsonNode node =
        CBORMapper.builder()
            .build()
            .readTree(
                encode(JacksonCborHttpMessageConverter.class, MediaType.APPLICATION_CBOR, id));

    assertThat(node.isString()).as("a UUID must not be a binary node in CBOR").isTrue();
    assertThat(node.stringValue()).isEqualTo("00000000-0000-0000-0000-000000000001");
  }

  @Test
  @DisplayName("the registered CBOR converter refuses to read, so only responses negotiate")
  void theCborConverterIsWriteOnly() {
    HttpMessageConverter<?> cbor = converterOf(JacksonCborHttpMessageConverter.class);

    assertThat(cbor.canWrite(WireSample.class, MediaType.APPLICATION_CBOR))
        .as("responses must still negotiate CBOR")
        .isTrue();
    assertThat(cbor.canRead(WireSample.class, MediaType.APPLICATION_CBOR))
        .as("a CBOR request body must be refused, which Spring turns into 415")
        .isFalse();
  }

  /**
   * Finds the converter Spring actually registered.
   *
   * @param type the converter class to find
   * @return the registered instance
   */
  private HttpMessageConverter<?> converterOf(Class<?> type) {
    for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
      if (type.isInstance(converter)) {
        return converter;
      }
    }
    throw new AssertionError(
        "no " + type.getSimpleName() + " registered — the second encoding is not wired at all");
  }

  /**
   * Encodes a value with the converter Spring actually registered, so the mapper under test is the
   * production one.
   *
   * @param type the converter class to use
   * @param mediaType the media type to write
   * @param value the value to encode
   * @return the encoded bytes
   * @throws Exception if the converter refuses the value
   */
  @SuppressWarnings("unchecked")
  private byte[] encode(Class<?> type, MediaType mediaType, Object value) throws Exception {
    for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
      if (type.isInstance(converter)) {
        MockHttpOutputMessage out = new MockHttpOutputMessage();
        ((HttpMessageConverter<Object>) converter).write(value, mediaType, out);
        return out.getBodyAsBytes();
      }
    }
    throw new AssertionError(
        "no " + type.getSimpleName() + " registered — the second encoding is not wired at all");
  }
}
