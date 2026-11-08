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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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

  /** The curated kind mapping RSI store slugs to ships, read beside the five base kinds. */
  private static final String SHIP_STORE_SLUGS = "shipStoreSlugs";

  /** The stats a blueprint modifier names. */
  private static final String GAMEPLAY_PROPERTIES = "gameplayProperties";

  /** The dismantle processes a blueprint's dismantle link names. */
  private static final String DISMANTLE_PROCESSES = "dismantleProcesses";

  /** The crafting globals holding the dismantle exclusions. */
  private static final String CRAFTING_GLOBAL = "craftingGlobal";

  /** The fields besides the kinds that {@link #read} holds in memory. */
  private static final Set<String> HELD_EXTRAS =
      Set.of(
          SHIP_STORE_SLUGS,
          P4kCatalogue.PAINTS,
          GAMEPLAY_PROPERTIES,
          DISMANTLE_PROCESSES,
          CRAFTING_GLOBAL);

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
      throw notCatalogue(e);
    }
  }

  /**
   * Reads the head, the five base kinds, the starmap objects, the shops, the ship store slugs, the
   * paints, the resource groups, the blueprint categories and the crafting records the blueprints
   * refer to, skipping everything else, the loadouts included. The held fields are counted against
   * {@code heldLimitBytes} after every record, so the memory they take stays bounded by that limit
   * plus one record.
   *
   * @param in the decompressed catalogue
   * @param heldLimitBytes the most bytes of catalogue JSON the held fields may span together
   * @return the catalogue
   * @throws P4kCatalogueFormatException when the content is not catalogue JSON
   * @throws P4kContentTooLargeException when the content passes the decompressed limit or the held
   *     fields pass {@code heldLimitBytes}
   */
  @NotNull
  public P4kCatalogue read(@NotNull InputStream in, long heldLimitBytes) {
    try (JsonParser parser = openObject(in)) {
      HeadBuilder head = new HeadBuilder();
      HeldBudget budget = new HeldBudget(heldLimitBytes);
      Map<P4kKind, List<? extends P4kRecord>> kinds = new EnumMap<>(P4kKind.class);
      List<P4kShipStoreSlug> storeSlugs = List.of();
      List<P4kPaintDto> paints = List.of();
      List<P4kGameplayPropertyDto> properties = List.of();
      List<P4kDismantleProcessDto> processes = List.of();
      P4kCraftingGlobalDto craftingGlobal = null;
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        if (head.offer(parser)) {
          continue;
        }
        String field = parser.currentName();
        P4kKind kind = kindOf(field);
        parser.nextToken();
        if (kind != null) {
          kinds.put(kind, readArray(parser, recordType(kind), budget));
        } else if (SHIP_STORE_SLUGS.equals(field)) {
          storeSlugs = readArray(parser, P4kShipStoreSlug.class, budget);
        } else if (P4kCatalogue.PAINTS.equals(field)) {
          paints = readArray(parser, P4kPaintDto.class, budget);
        } else if (GAMEPLAY_PROPERTIES.equals(field)) {
          properties = readArray(parser, P4kGameplayPropertyDto.class, budget);
        } else if (DISMANTLE_PROCESSES.equals(field)) {
          processes = readArray(parser, P4kDismantleProcessDto.class, budget);
        } else if (CRAFTING_GLOBAL.equals(field)) {
          budget.open(parser);
          craftingGlobal = bind(parser, P4kCraftingGlobalDto.class);
          budget.close(parser);
        } else {
          parser.skipChildren();
        }
      }
      return new P4kCatalogue(
          head.build(),
          listOf(kinds, P4kKind.MANUFACTURER, P4kManufacturerDto.class),
          listOf(kinds, P4kKind.ITEM, P4kItemDto.class),
          listOf(kinds, P4kKind.SHIP, P4kShipDto.class),
          listOf(kinds, P4kKind.COMMODITY, P4kCommodityDto.class),
          listOf(kinds, P4kKind.BLUEPRINT, P4kBlueprintDto.class),
          listOf(kinds, P4kKind.STARMAP_OBJECT, P4kStarmapObjectDto.class),
          listOf(kinds, P4kKind.SHOP, P4kShopDto.class),
          storeSlugs,
          paints,
          listOf(kinds, P4kKind.RESOURCE_GROUP, P4kResourceGroupDto.class),
          listOf(kinds, P4kKind.BLUEPRINT_CATEGORY, P4kBlueprintCategoryDto.class),
          P4kCraftingReferences.of(properties, processes, craftingGlobal));
    } catch (JacksonException e) {
      throw notCatalogue(e);
    }
  }

  /**
   * Reads the content to its end without binding a record, counting the bytes the fields {@link
   * #read} holds span.
   *
   * @param in the decompressed catalogue
   * @param heldLimitBytes the most bytes of catalogue JSON the held fields may span together
   * @throws IOException when the stream cannot be read
   * @throws P4kCatalogueFormatException when the content is not catalogue JSON
   * @throws P4kContentTooLargeException when the content passes the decompressed limit or the held
   *     fields pass {@code heldLimitBytes}
   */
  public void drainCountingHeld(@NotNull InputStream in, long heldLimitBytes) throws IOException {
    try (JsonParser parser = openObject(in)) {
      HeldBudget budget = new HeldBudget(heldLimitBytes);
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        boolean held = isHeld(parser.currentName());
        parser.nextToken();
        if (held) {
          budget.open(parser);
        }
        parser.skipChildren();
        if (held) {
          budget.close(parser);
        }
      }
      // Bytes after the root object still count against the decompressed limit.
      in.transferTo(OutputStream.nullOutputStream());
    } catch (JacksonException e) {
      throw notCatalogue(e);
    }
  }

  /**
   * Streams the {@code loadouts} kind: binds one loadout at a time, hands it on and drops it, so
   * the kind never sits in memory as a whole; every other field is skipped.
   *
   * @param in the decompressed catalogue
   * @param action receives each loadout in catalogue order
   * @throws P4kCatalogueFormatException when the content is not catalogue JSON
   * @throws P4kContentTooLargeException when the content passes the decompressed limit
   */
  public void readLoadouts(@NotNull InputStream in, @NotNull Consumer<P4kLoadoutDto> action) {
    try (JsonParser parser = openObject(in)) {
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        String field = parser.currentName();
        parser.nextToken();
        if (!P4kCatalogue.LOADOUTS.equals(field)) {
          parser.skipChildren();
        } else if (parser.currentToken() == JsonToken.START_ARRAY) {
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            P4kLoadoutDto loadout = bind(parser, P4kLoadoutDto.class);
            if (loadout != null) {
              action.accept(loadout);
            }
          }
          return;
        }
      }
    } catch (JacksonException e) {
      throw notCatalogue(e);
    }
  }

  /**
   * Translates a Jackson failure. Jackson wraps an exception the stream throws while it binds a
   * record, so the decompressed limit reached mid-record arrives as a cause, not on its own.
   *
   * @param e the failure
   * @return the limit's {@link P4kContentTooLargeException} when it caused the failure, else a
   *     {@link P4kCatalogueFormatException}
   */
  @NotNull
  private static RuntimeException notCatalogue(@NotNull JacksonException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof P4kContentTooLargeException tooLarge) {
        return tooLarge;
      }
    }
    return new P4kCatalogueFormatException("The upload is not catalogue JSON.", e);
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
   * Binds the held array the parser stands on, one element at a time, charging each element to the
   * budget.
   *
   * @param parser the parser on the array's first token
   * @param type the element type
   * @param budget the budget of the held fields
   * @param <T> the element type
   * @return the elements; empty for {@code null}
   * @throws P4kContentTooLargeException when the held fields pass the budget
   */
  @NotNull
  private <T> List<T> readArray(
      @NotNull JsonParser parser, @NotNull Class<T> type, @NotNull HeldBudget budget) {
    List<T> out = new ArrayList<>();
    budget.open(parser);
    if (parser.currentToken() != JsonToken.START_ARRAY) {
      parser.skipChildren();
    } else {
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        T element = bind(parser, type);
        budget.charge(parser);
        if (element != null) {
          out.add(element);
        }
      }
    }
    budget.close(parser);
    return out;
  }

  /**
   * Whether {@link #read} holds a top-level field.
   *
   * @param field the field name
   * @return {@code true} for a kind or one of the extra fields the import holds
   */
  private static boolean isHeld(@Nullable String field) {
    return kindOf(field) != null || HELD_EXTRAS.contains(field);
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
      case STARMAP_OBJECT -> P4kStarmapObjectDto.class;
      case SHOP -> P4kShopDto.class;
      case RESOURCE_GROUP -> P4kResourceGroupDto.class;
      case BLUEPRINT_CATEGORY -> P4kBlueprintCategoryDto.class;
    };
  }

  /**
   * Counts the bytes of catalogue JSON the held fields span, from the parser's byte offsets, and
   * refuses once they pass the limit.
   */
  private static final class HeldBudget {

    /** The most bytes the held fields may span together. */
    private final long limitBytes;

    /** The bytes of the held fields already closed. */
    private long closedBytes;

    /** The byte offset where the open held field starts. */
    private long fieldStart;

    /**
     * Creates an empty budget.
     *
     * @param limitBytes the most bytes the held fields may span together
     */
    private HeldBudget(long limitBytes) {
      this.limitBytes = limitBytes;
    }

    /**
     * Opens a held field at the token the parser stands on, its value's first token.
     *
     * @param parser the parser
     */
    private void open(@NotNull JsonParser parser) {
      fieldStart = parser.currentTokenLocation().getByteOffset();
    }

    /**
     * Checks the closed fields plus the open one up to the parser's position against the limit.
     *
     * @param parser the parser
     * @return the bytes counted so far
     * @throws P4kContentTooLargeException when they pass the limit
     */
    private long charge(@NotNull JsonParser parser) {
      long used = closedBytes + parser.currentLocation().getByteOffset() - fieldStart;
      if (used > limitBytes) {
        throw new P4kContentTooLargeException(
            "The part of the catalogue held in memory", limitBytes);
      }
      return used;
    }

    /**
     * Closes the open field at the parser's position, the end of its value.
     *
     * @param parser the parser
     * @throws P4kContentTooLargeException when the held fields pass the limit
     */
    private void close(@NotNull JsonParser parser) {
      closedBytes = charge(parser);
    }
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
