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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams a version-2 catalogue (REQ-P4K-002, REQ-P4K-003): it binds one record at a time, skips
 * every kind and field it does not read without binding it, and never holds the file in memory.
 */
@Component
@RequiredArgsConstructor
public class P4kCatalogueReader {

  /** The head field holding the shape version. */
  private static final String SCHEMA_VERSION = "schemaVersion";

  /** The head field holding the source. */
  private static final String SOURCE = "source";

  /** The head field holding the completeness per kind. */
  private static final String COMPLETENESS = "completeness";

  private final ObjectMapper objectMapper;

  /**
   * Reads the head only and stops at the first field after it; {@code schemaVersion} and {@code
   * source} come first in the file.
   *
   * @param in the decompressed catalogue
   * @return the head; a field the file lacks is {@code null} or empty
   * @throws P4kCatalogueFormatException when the content is not a JSON object
   */
  @NotNull
  public P4kCatalogueHead readHead(@NotNull InputStream in) {
    try (JsonParser parser = openObject(in)) {
      HeadBuilder head = new HeadBuilder();
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        if (!head.offer(parser)) {
          break;
        }
      }
      return head.build();
    } catch (JacksonException e) {
      throw new P4kCatalogueFormatException("The upload is not catalogue JSON.", e);
    }
  }

  /**
   * Reads the head and the five base kinds, skipping everything else.
   *
   * @param in the decompressed catalogue
   * @return the catalogue
   * @throws P4kCatalogueFormatException when the content is not catalogue JSON
   */
  @NotNull
  public P4kCatalogue read(@NotNull InputStream in) {
    try (JsonParser parser = openObject(in)) {
      HeadBuilder head = new HeadBuilder();
      Map<P4kKind, List<? extends P4kRecord>> kinds = new EnumMap<>(P4kKind.class);
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        if (head.offer(parser)) {
          continue;
        }
        P4kKind kind = kindOf(parser.currentName());
        parser.nextToken();
        if (kind == null) {
          parser.skipChildren();
        } else {
          kinds.put(kind, readArray(parser, recordType(kind)));
        }
      }
      return new P4kCatalogue(
          head.build(),
          listOf(kinds, P4kKind.MANUFACTURER, P4kManufacturerDto.class),
          listOf(kinds, P4kKind.ITEM, P4kItemDto.class),
          listOf(kinds, P4kKind.SHIP, P4kShipDto.class),
          listOf(kinds, P4kKind.COMMODITY, P4kCommodityDto.class),
          listOf(kinds, P4kKind.BLUEPRINT, P4kBlueprintDto.class));
    } catch (JacksonException e) {
      throw new P4kCatalogueFormatException("The upload is not catalogue JSON.", e);
    }
  }

  /**
   * Opens a parser positioned on the root object.
   *
   * @param in the decompressed catalogue
   * @return the parser after {@code START_OBJECT}
   * @throws P4kCatalogueFormatException when the root is not an object
   */
  @NotNull
  private JsonParser openObject(@NotNull InputStream in) {
    JsonParser parser = objectMapper.createParser(in);
    if (parser.nextToken() != JsonToken.START_OBJECT) {
      parser.close();
      throw new P4kCatalogueFormatException(
          "The upload is not a JSON object.", new IllegalArgumentException("root"));
    }
    return parser;
  }

  /**
   * Binds the array the parser stands on, one element at a time.
   *
   * @param parser the parser on the array's first token
   * @param type the element type
   * @param <T> the element type
   * @return the elements; empty for {@code null}
   */
  @NotNull
  private <T> List<T> readArray(@NotNull JsonParser parser, @NotNull Class<T> type) {
    List<T> out = new ArrayList<>();
    if (parser.currentToken() != JsonToken.START_ARRAY) {
      parser.skipChildren();
      return out;
    }
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      T element = bind(parser, type);
      if (element != null) {
        out.add(element);
      }
    }
    return out;
  }

  /**
   * Binds the value the parser stands on, leaving the parser on its last token.
   *
   * @param parser the parser on the value's first token
   * @param type the value type
   * @param <T> the value type
   * @return the value, or {@code null} for a JSON {@code null}
   */
  @Nullable
  private <T> T bind(@NotNull JsonParser parser, @NotNull Class<T> type) {
    return objectMapper
        .readerFor(type)
        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .readValue(parser);
  }

  /**
   * Returns the kind a top-level field holds.
   *
   * @param field the field name
   * @return the kind, or {@code null} for a kind the import does not read
   */
  @Nullable
  private static P4kKind kindOf(@Nullable String field) {
    for (P4kKind kind : P4kKind.values()) {
      if (kind.getCatalogueField().equals(field)) {
        return kind;
      }
    }
    return null;
  }

  /**
   * Returns one kind's records with their element type.
   *
   * @param kinds the records read per kind
   * @param kind the kind
   * @param type the record type of the kind
   * @param <T> the record type
   * @return the records, empty when the file lacks the kind
   */
  @NotNull
  private static <T extends P4kRecord> List<T> listOf(
      @NotNull Map<P4kKind, List<? extends P4kRecord>> kinds,
      @NotNull P4kKind kind,
      @NotNull Class<T> type) {
    List<? extends P4kRecord> records = kinds.getOrDefault(kind, List.of());
    return records.stream().map(type::cast).toList();
  }

  /**
   * Returns the record type a kind binds to.
   *
   * @param kind the kind
   * @return the record type
   */
  @NotNull
  private static Class<? extends P4kRecord> recordType(@NotNull P4kKind kind) {
    return switch (kind) {
      case MANUFACTURER -> P4kManufacturerDto.class;
      case ITEM -> P4kItemDto.class;
      case SHIP -> P4kShipDto.class;
      case COMMODITY -> P4kCommodityDto.class;
      case BLUEPRINT -> P4kBlueprintDto.class;
    };
  }

  /** Collects the head fields as the parser meets them. */
  private final class HeadBuilder {

    /** The shape version, once read. */
    @Nullable private Integer schemaVersion;

    /** The source, once read. */
    @Nullable private P4kSource source;

    /** The completeness per kind, once read. */
    @NotNull private Map<String, P4kKindCompleteness> completeness = Map.of();

    /**
     * Reads the field the parser stands on when it is a head field.
     *
     * @param parser the parser on a {@code PROPERTY_NAME}
     * @return {@code true} when the field was a head field and has been read
     */
    private boolean offer(@NotNull JsonParser parser) {
      String field = parser.currentName();
      if (SCHEMA_VERSION.equals(field)) {
        JsonToken token = parser.nextToken();
        schemaVersion = token == JsonToken.VALUE_NUMBER_INT ? parser.getIntValue() : null;
        parser.skipChildren();
        return true;
      }
      if (SOURCE.equals(field)) {
        parser.nextToken();
        source = bind(parser, P4kSource.class);
        return true;
      }
      if (COMPLETENESS.equals(field)) {
        parser.nextToken();
        Map<String, P4kKindCompleteness> read =
            objectMapper
                .readerFor(new TypeReference<Map<String, P4kKindCompleteness>>() {})
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(parser);
        completeness = read == null ? Map.of() : read;
        return true;
      }
      return false;
    }

    /**
     * Returns the head read so far.
     *
     * @return the head
     */
    @NotNull
    private P4kCatalogueHead build() {
      return new P4kCatalogueHead(schemaVersion, source, completeness);
    }
  }
}
