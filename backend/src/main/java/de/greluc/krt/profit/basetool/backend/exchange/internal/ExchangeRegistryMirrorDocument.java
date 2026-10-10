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
import java.util.SortedMap;
import org.jetbrains.annotations.NotNull;

/**
 * The JSON document the gateway reads from the mirror key (REQ-XCH-003).
 *
 * @param schemaVersion the document format, {@value #SCHEMA_VERSION} for this layout
 * @param revision grows with every write, across restarts
 * @param writtenAt when the backend wrote the document
 * @param enabled whether the exchange serves any request
 * @param clients every registry client by its Keycloak client id
 */
public record ExchangeRegistryMirrorDocument(
    int schemaVersion,
    long revision,
    @NotNull Instant writtenAt,
    boolean enabled,
    @NotNull SortedMap<String, ExchangeRegistrySnapshot.Client> clients) {

  /** The current document format. */
  public static final int SCHEMA_VERSION = 1;

  /**
   * Returns the registry content without revision and timestamp.
   *
   * @return the snapshot this document carries
   */
  @NotNull
  public ExchangeRegistrySnapshot snapshot() {
    return new ExchangeRegistrySnapshot(enabled, clients);
  }
}
