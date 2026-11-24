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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON document the gateway reads from the mirror key (REQ-XCH-003): {@code schemaVersion},
 * {@code revision}, {@code writtenAt}, one boolean per {@link ExchangeSwitch} under its mirror
 * field in declaration order, and {@code clients} by client id. A document without a switch's
 * member reads that switch as off.
 */
final class ExchangeRegistryMirrorDocument {

  /** The current document format. */
  static final int SCHEMA_VERSION = 1;

  /** The member holding the document format. */
  private static final String SCHEMA_VERSION_FIELD = "schemaVersion";

  /** The member holding the clients by client id. */
  private static final String CLIENTS_FIELD = "clients";

  /** The type the clients member is read as. */
  private static final TypeReference<TreeMap<String, ExchangeRegistrySnapshot.Client>>
      CLIENTS_TYPE = new TypeReference<>() {};

  private ExchangeRegistryMirrorDocument() {}

  /**
   * Renders a snapshot as the document.
   *
   * @param mapper the mapper the document is written with
   * @param snapshot the registry content
   * @param revision grows with every write, across restarts
   * @param writtenAt when the backend writes the document
   * @return the document's JSON text
   */
  @NotNull
  static String write(
      @NotNull JsonMapper mapper,
      @NotNull ExchangeRegistrySnapshot snapshot,
      long revision,
      @NotNull Instant writtenAt) {
    ObjectNode document = mapper.createObjectNode();
    document.put(SCHEMA_VERSION_FIELD, SCHEMA_VERSION);
    document.put("revision", revision);
    document.set("writtenAt", mapper.valueToTree(writtenAt));
    for (ExchangeSwitch target : ExchangeSwitch.values()) {
      document.put(target.getMirrorField(), snapshot.isOn(target));
    }
    document.set(CLIENTS_FIELD, mapper.valueToTree(snapshot.clients()));
    return mapper.writeValueAsString(document);
  }

  /**
   * Parses the document back into the registry content it carries, without revision and write time.
   *
   * @param mapper the mapper the document is read with
   * @param raw the document's JSON text
   * @return the content, or empty for a document of another format
   * @throws JacksonException when the text is no readable document
   */
  @NotNull
  static Optional<ExchangeRegistrySnapshot> read(@NotNull JsonMapper mapper, @NotNull String raw) {
    JsonNode document = mapper.readTree(raw);
    JsonNode version = document.path(SCHEMA_VERSION_FIELD);
    if (!version.isInt() || version.intValue() != SCHEMA_VERSION) {
      return Optional.empty();
    }
    Set<ExchangeSwitch> on = EnumSet.noneOf(ExchangeSwitch.class);
    for (ExchangeSwitch target : ExchangeSwitch.values()) {
      JsonNode flag = document.path(target.getMirrorField());
      if (flag.isBoolean() && flag.booleanValue()) {
        on.add(target);
      }
    }
    JsonNode clients = document.path(CLIENTS_FIELD);
    return Optional.of(
        new ExchangeRegistrySnapshot(
            on, clients.isObject() ? mapper.convertValue(clients, CLIENTS_TYPE) : new TreeMap<>()));
  }
}
