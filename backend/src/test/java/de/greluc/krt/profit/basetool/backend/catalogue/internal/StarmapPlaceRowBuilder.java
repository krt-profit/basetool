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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a UEX location row of the starmap and shop link tests (REQ-P4K-035, REQ-P4K-043): a live,
 * unlinked row of the star system {@code Stanton} unless set otherwise.
 */
final class StarmapPlaceRowBuilder {

  private final StarmapPlaceKind kind;
  private final String name;
  @Nullable private Integer uexId;
  @Nullable private String nickname;
  private String starSystem = "Stanton";
  @Nullable private String planet;
  @Nullable private String moon;
  @Nullable private String city;
  @Nullable private String outpost;
  private boolean live = true;
  @Nullable private String terminalType;

  /**
   * Starts a row.
   *
   * @param kind the table
   * @param name the UEX name
   */
  private StarmapPlaceRowBuilder(@NotNull StarmapPlaceKind kind, @NotNull String name) {
    this.kind = kind;
    this.name = name;
  }

  /**
   * Starts a row of a table.
   *
   * @param kind the table
   * @param name the UEX name
   * @return the builder
   */
  @NotNull
  static StarmapPlaceRowBuilder row(@NotNull StarmapPlaceKind kind, @NotNull String name) {
    return new StarmapPlaceRowBuilder(kind, name);
  }

  /**
   * Starts a terminal of a city.
   *
   * @param name the UEX name
   * @param city the city's name
   * @param type the UEX terminal type, or {@code null}
   * @return the builder
   */
  @NotNull
  static StarmapPlaceRowBuilder terminal(
      @NotNull String name, @NotNull String city, @Nullable String type) {
    StarmapPlaceRowBuilder builder = new StarmapPlaceRowBuilder(StarmapPlaceKind.TERMINAL, name);
    builder.city = city;
    builder.terminalType = type;
    return builder;
  }

  /**
   * Sets the UEX id.
   *
   * @param value the id
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder uexId(int value) {
    uexId = value;
    return this;
  }

  /**
   * Sets the UEX nickname.
   *
   * @param value the nickname
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder nickname(@NotNull String value) {
    nickname = value;
    return this;
  }

  /**
   * Sets the star system.
   *
   * @param value the star system's name
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder starSystem(@NotNull String value) {
    starSystem = value;
    return this;
  }

  /**
   * Sets the planet.
   *
   * @param value the planet's name
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder planet(@NotNull String value) {
    planet = value;
    return this;
  }

  /**
   * Sets the moon.
   *
   * @param value the moon's name
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder moon(@NotNull String value) {
    moon = value;
    return this;
  }

  /**
   * Sets the outpost.
   *
   * @param value the outpost's name
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder outpost(@NotNull String value) {
    outpost = value;
    return this;
  }

  /**
   * Sets UEX's live flag.
   *
   * @param value the flag
   * @return this builder
   */
  @NotNull
  StarmapPlaceRowBuilder live(boolean value) {
    live = value;
    return this;
  }

  /**
   * Builds the unlinked row.
   *
   * @return the row
   */
  @NotNull
  StarmapPlaceRow build() {
    return new StarmapPlaceRow(
        kind,
        UUID.randomUUID(),
        uexId,
        name,
        nickname,
        starSystem,
        planet,
        null,
        moon,
        city,
        null,
        outpost,
        live,
        terminalType,
        null,
        false);
  }
}
