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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.jetbrains.annotations.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The trimmed LIVE catalogue of version 2 the P4K tests share, and ways to vary and compress it.
 *
 * <p>{@code p4k/catalogue-v2-live-trimmed.json} is a subset of a real LIVE export of p4kx 0.4.2
 * (change 12913470): its head unchanged, a few records of every kind the import reads, game texts
 * shortened, and one record of a few skipped kinds. It validates against {@code
 * p4k/basetool-catalog.schema.json}. The gzip and zstd forms are made here, so they always match
 * the JSON.
 */
public final class P4kTestCatalogues {

  /** The fixture's classpath location. */
  public static final String FIXTURE = "/p4k/catalogue-v2-live-trimmed.json";

  /** The fixture's change number. */
  public static final String CHANGE_NUMBER = "12913470";

  /** A {@code player} Shield of grade 3. */
  public static final UUID SHIELD = UUID.fromString("339bd641-8dfb-4bc1-b273-5ffc5f702fbb");

  /** Its class name. */
  public static final String SHIELD_CLASS = "SHLD_GODI_S01_AllStop_SCItem";

  /** A {@code player} cooler. */
  public static final UUID COOLER = UUID.fromString("04285ce3-561f-4377-b0db-c641e3fad1de");

  /** A {@code special} item. */
  public static final UUID SPECIAL_ITEM = UUID.fromString("00003571-11df-4b9d-a30d-1392d2a4bde7");

  /** A {@code player} ship. */
  public static final UUID PLAYER_SHIP = UUID.fromString("0079c5d5-1678-4f8c-85ba-18ca8f642af6");

  /** An {@code ai} ship. */
  public static final UUID AI_SHIP = UUID.fromString("0045b223-9bea-4ef5-a873-d1316830fe43");

  /** Its class name. */
  public static final String AI_SHIP_CLASS = "ANVL_Hornet_F7C_Mk2_AI_Super";

  /** A {@code mission} ship. */
  public static final UUID MISSION_SHIP = UUID.fromString("05189197-09f7-4869-8db2-5fe21886cda3");

  /** A {@code special} ship. */
  public static final UUID SPECIAL_SHIP = UUID.fromString("05a4f566-36a4-495c-99a4-dbedc5105cb6");

  /** A commodity. */
  public static final UUID ASLARITE = UUID.fromString("fde0cd65-8827-4b23-804d-cc8845dfa7ac");

  /** The window log p4kx writes the LIVE catalogue with ({@code --long=27}). */
  public static final int LIVE_WINDOW_LOG = 27;

  /** Shared mapper for building variants. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Non-instantiable holder of static helpers. */
  private P4kTestCatalogues() {}

  /**
   * Reads the fixture as it is committed.
   *
   * @return the plain JSON bytes
   */
  public static byte @NotNull [] fixtureBytes() {
    try (InputStream in = P4kTestCatalogues.class.getResourceAsStream(FIXTURE)) {
      if (in == null) {
        throw new IllegalStateException("Missing " + FIXTURE);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the fixture as a mutable tree.
   *
   * @return the root object
   */
  @NotNull
  public static ObjectNode tree() {
    return (ObjectNode) MAPPER.readTree(fixtureBytes());
  }

  /**
   * Serialises a tree.
   *
   * @param root the root
   * @return the plain JSON bytes
   */
  public static byte @NotNull [] bytes(@NotNull JsonNode root) {
    return MAPPER.writeValueAsBytes(root);
  }

  /**
   * Returns the build object of a tree's source.
   *
   * @param root the root
   * @return {@code source.build}
   */
  @NotNull
  public static ObjectNode build(@NotNull ObjectNode root) {
    return (ObjectNode) root.get("source").get("build");
  }

  /**
   * Returns the fixture with another change number.
   *
   * @param changeNumber the change number text
   * @return the plain JSON bytes
   */
  public static byte @NotNull [] withChangeNumber(@NotNull String changeNumber) {
    ObjectNode root = tree();
    build(root).put("changeNumber", changeNumber);
    return bytes(root);
  }

  /**
   * Finds the record of a GUID in a kind of a tree.
   *
   * @param root the root
   * @param kind the kind's field, e.g. {@code ships}
   * @param guid the GUID
   * @return the record
   */
  @NotNull
  public static ObjectNode record(
      @NotNull ObjectNode root, @NotNull String kind, @NotNull UUID guid) {
    for (JsonNode node : (ArrayNode) root.get(kind)) {
      if (guid.toString().equals(node.get("guid").asString())) {
        return (ObjectNode) node;
      }
    }
    throw new IllegalArgumentException("No " + kind + " record " + guid);
  }

  /**
   * Compresses with gzip.
   *
   * @param plain the plain bytes
   * @return a gzip member
   */
  public static byte @NotNull [] gzip(byte @NotNull [] plain) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (OutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(plain);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * Compresses with zstd and long-distance matching over a 128 MiB window, as p4kx writes it.
   *
   * @param plain the plain bytes
   * @return a zstd frame
   */
  public static byte @NotNull [] zstd(byte @NotNull [] plain) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (OutputStream zstd = longWindowZstd(out, LIVE_WINDOW_LOG)) {
      zstd.write(plain);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * Opens a zstd encoder that writes one frame of unknown size with long-distance matching, so the
   * frame header declares the full window.
   *
   * @param sink the sink
   * @param windowLog the window log
   * @return the encoder
   * @throws IOException if the encoder rejects the parameters
   */
  public static @NotNull OutputStream longWindowZstd(@NotNull OutputStream sink, int windowLog)
      throws IOException {
    ZstdOutputStream zstd = new ZstdOutputStream(sink, 3);
    zstd.setLong(windowLog);
    return zstd;
  }
}
