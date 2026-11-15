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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrigin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The list filters of a hangar tab (REQ-HANGAR-002, REQ-HANGAR-017): the search term, the fitted
 * flag and the origin, each absent when it does not filter. They travel together from the query
 * into the backend reads, the model and the pagination links.
 *
 * @param search the trimmed ship-type/manufacturer term, or {@code null} for none
 * @param fitted the fitted flag to list, or {@code null} for both
 * @param origin the origin to list, or {@code null} for every origin
 */
record HangarListFilter(
    @Nullable String search, @Nullable Boolean fitted, @Nullable ShipOrigin origin) {

  /** No filter: every ship of the tab. */
  static final HangarListFilter NONE = new HangarListFilter(null, null, null);

  /**
   * Reads the filters from the raw query values leniently, so a crafted value filters nothing
   * instead of failing the page.
   *
   * @param search the raw search term; blank means none
   * @param fitted the raw fitted value; anything but {@code true} or {@code false} means both
   * @param origin the raw origin value; an unknown value means every origin
   * @return the filters
   */
  static @NotNull HangarListFilter of(
      @Nullable String search, @Nullable String fitted, @Nullable String origin) {
    return new HangarListFilter(searchOf(search), fittedOf(fitted), ShipOrigin.parse(origin));
  }

  /**
   * Reads a search term alone, for a tab without the fitted and origin filters.
   *
   * @param search the raw search term; blank means none
   * @return the filter
   */
  static @NotNull HangarListFilter searchOnly(@Nullable String search) {
    return new HangarListFilter(searchOf(search), null, null);
  }

  /**
   * Renders the fitted filter as the page's filter control holds it.
   *
   * @return {@code true}, {@code false}, or an empty string for both
   */
  @NotNull
  String fittedParam() {
    return fitted == null ? "" : fitted.toString();
  }

  /**
   * Renders the origin filter as the page's filter control holds it.
   *
   * @return the origin's name, or an empty string for every origin
   */
  @NotNull
  String originParam() {
    return origin == null ? "" : origin.name();
  }

  /**
   * Builds a tab's pagination base URL keeping the active filters, so paging and the size picker
   * never drop them.
   *
   * @param path the tab's own path
   * @return the base URL without paging params
   */
  @NotNull
  String baseUrl(@NotNull String path) {
    if (search == null && fitted == null && origin == null) {
      return path;
    }
    UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
    if (search != null) {
      uri.queryParam("search", search);
    }
    if (fitted != null) {
      uri.queryParam("fitted", fitted);
    }
    if (origin != null) {
      uri.queryParam("origin", origin.name());
    }
    return uri.toUriString();
  }

  /**
   * Trims a search term.
   *
   * @param search the raw term
   * @return the trimmed term, or {@code null} for a blank one
   */
  private static @Nullable String searchOf(@Nullable String search) {
    return search == null || search.isBlank() ? null : search.trim();
  }

  /**
   * Reads the fitted filter.
   *
   * @param fitted the raw query value
   * @return {@code TRUE} or {@code FALSE} for {@code "true"} / {@code "false"}, otherwise {@code
   *     null}
   */
  @Contract("null -> null")
  private static @Nullable Boolean fittedOf(@Nullable String fitted) {
    if ("true".equalsIgnoreCase(fitted)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(fitted)) {
      return Boolean.FALSE;
    }
    return null;
  }
}
