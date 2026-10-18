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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Every filter of a Lager page, bound once from the query string with {@code @ModelAttribute} and
 * handed on in place of loose parameters (REQ-INV-030, REQ-INV-034, REQ-INV-040, REQ-INV-053,
 * REQ-INV-063).
 *
 * <p>The record owns the parsing, the emptiness test, the query parameters sent to the backend and
 * the query parameters of the page URL; both use the one set of parameter names below. A view reads
 * only the dimensions that belong to it: the material view the material, quality and mission ones,
 * the items view the game-item and attribute ones.
 *
 * @param view {@code "items"} for the game-item view, otherwise {@code null} for the material view
 * @param materialIds the materials to narrow to (material view)
 * @param minQuality the quality floor (material view)
 * @param jobOrderIds the earmarking job orders to narrow to (both views)
 * @param missionIds the earmarking missions to narrow to (material view)
 * @param gameItemIds the game items to narrow to (items view)
 * @param locationIds the storage locations to narrow to (both views)
 * @param personalOnly whether only the caller's personal entries are listed
 * @param nonPersonalOnly whether only the caller's shared entries are listed
 * @param stolenOnly whether only stock marked „gestohlen" is listed
 * @param nonStolenOnly whether stock marked „gestohlen" is hidden
 * @param kinds the item category names to narrow to (items view)
 * @param componentTypes the ship component type names to narrow to (items view)
 * @param sizes the component sizes to narrow to (items view)
 * @param grades the component grades to narrow to (items view)
 * @param itemClasses the component class names to narrow to (items view)
 */
public record LagerQuery(
    @Nullable String view,
    @NotNull @Unmodifiable List<UUID> materialIds,
    @Nullable Integer minQuality,
    @NotNull @Unmodifiable List<UUID> jobOrderIds,
    @NotNull @Unmodifiable List<UUID> missionIds,
    @NotNull @Unmodifiable List<UUID> gameItemIds,
    @NotNull @Unmodifiable List<UUID> locationIds,
    Boolean personalOnly,
    Boolean nonPersonalOnly,
    Boolean stolenOnly,
    Boolean nonStolenOnly,
    @NotNull @Unmodifiable List<String> kinds,
    @NotNull @Unmodifiable List<String> componentTypes,
    @NotNull @Unmodifiable List<Integer> sizes,
    @NotNull @Unmodifiable List<String> grades,
    @NotNull @Unmodifiable List<String> itemClasses) {

  /** The value of {@code view} that selects the game-item view. */
  public static final String VIEW_ITEMS = "items";

  private static final String P_VIEW = "view";
  private static final String P_CATALOG = "catalog";
  private static final String P_MATERIAL_IDS = "materialIds";
  private static final String P_GAME_ITEM_IDS = "gameItemIds";
  private static final String P_LOCATION_IDS = "locationIds";
  private static final String P_MIN_QUALITY = "minQuality";
  private static final String P_JOB_ORDER_IDS = "jobOrderIds";
  private static final String P_MISSION_IDS = "missionIds";
  private static final String P_PERSONAL_ONLY = "personalOnly";
  private static final String P_NON_PERSONAL_ONLY = "nonPersonalOnly";
  private static final String P_STOLEN_ONLY = "stolenOnly";
  private static final String P_NON_STOLEN_ONLY = "nonStolenOnly";
  private static final String P_KINDS = "kinds";
  private static final String P_COMPONENT_TYPES = "componentTypes";
  private static final String P_SIZES = "sizes";
  private static final String P_GRADES = "grades";
  private static final String P_ITEM_CLASSES = "itemClasses";

  private static final LagerQuery EMPTY_ITEMS =
      new LagerQuery(
          VIEW_ITEMS,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          false,
          false,
          false,
          null,
          null,
          null,
          null,
          null);

  private static final LagerQuery EMPTY =
      new LagerQuery(
          null, null, null, null, null, null, null, false, false, false, false, null, null, null,
          null, null);

  /**
   * Normalises the bound values: {@code null} id lists become empty, {@code null} entries are
   * dropped, strings are trimmed, blank ones dropped, and every list is distinct. A {@code view}
   * other than {@code "items"} is the material view.
   *
   * @param view the requested view, may be {@code null}
   * @param materialIds the materials, may be {@code null}
   * @param minQuality the quality floor, may be {@code null}
   * @param jobOrderIds the job orders, may be {@code null}
   * @param missionIds the missions, may be {@code null}
   * @param gameItemIds the game items, may be {@code null}
   * @param locationIds the storage locations, may be {@code null}
   * @param personalOnly the personal-entries flag
   * @param nonPersonalOnly the shared-entries flag
   * @param stolenOnly the stolen-only flag
   * @param nonStolenOnly the not-stolen-only flag
   * @param kinds the item category names, may be {@code null}
   * @param componentTypes the ship component type names, may be {@code null}
   * @param sizes the component sizes, may be {@code null}
   * @param grades the component grades, may be {@code null}
   * @param itemClasses the component class names, may be {@code null}
   */
  public LagerQuery(
      @Nullable String view,
      @Nullable List<UUID> materialIds,
      @Nullable Integer minQuality,
      @Nullable List<UUID> jobOrderIds,
      @Nullable List<UUID> missionIds,
      @Nullable List<UUID> gameItemIds,
      @Nullable List<UUID> locationIds,
      Boolean personalOnly,
      Boolean nonPersonalOnly,
      Boolean stolenOnly,
      Boolean nonStolenOnly,
      @Nullable List<String> kinds,
      @Nullable List<String> componentTypes,
      @Nullable List<Integer> sizes,
      @Nullable List<String> grades,
      @Nullable List<String> itemClasses) {
    this.view = view != null && VIEW_ITEMS.equalsIgnoreCase(view.trim()) ? VIEW_ITEMS : null;
    this.materialIds = distinctIds(materialIds);
    this.minQuality = minQuality;
    this.jobOrderIds = distinctIds(jobOrderIds);
    this.missionIds = distinctIds(missionIds);
    this.gameItemIds = distinctIds(gameItemIds);
    this.locationIds = distinctIds(locationIds);
    this.personalOnly = Boolean.TRUE.equals(personalOnly);
    this.nonPersonalOnly = Boolean.TRUE.equals(nonPersonalOnly);
    this.stolenOnly = Boolean.TRUE.equals(stolenOnly);
    this.nonStolenOnly = Boolean.TRUE.equals(nonStolenOnly);
    ItemAttributeFilter attributes =
        new ItemAttributeFilter(kinds, componentTypes, sizes, grades, itemClasses);
    this.kinds = attributes.kinds();
    this.componentTypes = attributes.componentTypes();
    this.sizes = attributes.sizes();
    this.grades = attributes.grades();
    this.itemClasses = attributes.itemClasses();
  }

  /**
   * The query that narrows nothing, material view.
   *
   * @return the empty query
   */
  public static @NotNull LagerQuery empty() {
    return EMPTY;
  }

  /**
   * The query that narrows nothing, items view.
   *
   * @return the empty items-view query
   */
  public static @NotNull LagerQuery emptyItems() {
    return EMPTY_ITEMS;
  }

  /**
   * Parses a query string as a map of repeated parameters; unknown parameters, blank values and
   * values that are not a UUID or number where one is expected are ignored.
   *
   * @param params the request parameters, by name
   * @return the parsed query
   */
  public static @NotNull LagerQuery fromParams(@NotNull Map<String, List<String>> params) {
    return new LagerQuery(
        first(params, P_VIEW),
        uuids(params, P_MATERIAL_IDS),
        integer(first(params, P_MIN_QUALITY)),
        uuids(params, P_JOB_ORDER_IDS),
        uuids(params, P_MISSION_IDS),
        uuids(params, P_GAME_ITEM_IDS),
        uuids(params, P_LOCATION_IDS),
        flag(params, P_PERSONAL_ONLY),
        flag(params, P_NON_PERSONAL_ONLY),
        flag(params, P_STOLEN_ONLY),
        flag(params, P_NON_STOLEN_ONLY),
        strings(params, P_KINDS),
        strings(params, P_COMPONENT_TYPES),
        integers(params, P_SIZES),
        strings(params, P_GRADES),
        strings(params, P_ITEM_CLASSES));
  }

  /**
   * The names of every filter query parameter, in the order the page renders them.
   *
   * @return the parameter names shared by the URL and the backend
   */
  public static @NotNull List<String> filterParameterNames() {
    return List.of(
        P_MATERIAL_IDS,
        P_GAME_ITEM_IDS,
        P_LOCATION_IDS,
        P_MIN_QUALITY,
        P_JOB_ORDER_IDS,
        P_MISSION_IDS,
        P_PERSONAL_ONLY,
        P_NON_PERSONAL_ONLY,
        P_STOLEN_ONLY,
        P_NON_STOLEN_ONLY,
        P_KINDS,
        P_COMPONENT_TYPES,
        P_SIZES,
        P_GRADES,
        P_ITEM_CLASSES);
  }

  /**
   * The query parameters the page scripts build requests from, as {@code slot=name} pairs: the
   * script's own slot name and the parameter name it appends the slot under.
   *
   * @return the pairs, the view switch first
   */
  public static @NotNull List<String> parameterSlots() {
    return List.of(
        "view=" + P_VIEW,
        "materials=" + P_MATERIAL_IDS,
        "gameItems=" + P_GAME_ITEM_IDS,
        "locations=" + P_LOCATION_IDS,
        "minQuality=" + P_MIN_QUALITY,
        "jobOrders=" + P_JOB_ORDER_IDS,
        "missions=" + P_MISSION_IDS,
        "personal=" + P_PERSONAL_ONLY,
        "nonPersonal=" + P_NON_PERSONAL_ONLY,
        "stolen=" + P_STOLEN_ONLY,
        "nonStolen=" + P_NON_STOLEN_ONLY);
  }

  /**
   * The item-attribute filters as {@code parameter:family} pairs: the query parameter and the
   * multi-select family of the page elements that edit it.
   *
   * @return the pairs, one per attribute filter
   */
  public static @NotNull List<String> attributeFilters() {
    return List.of(
        P_KINDS + ":kind",
        P_COMPONENT_TYPES + ":componentType",
        P_SIZES + ":size",
        P_GRADES + ":grade",
        P_ITEM_CLASSES + ":itemClass");
  }

  /**
   * Whether the game-item view is requested.
   *
   * @return {@code true} for the items view
   */
  public boolean itemsView() {
    return VIEW_ITEMS.equals(view);
  }

  /**
   * The item-attribute filters as one value.
   *
   * @return the category, component-type, size, grade and class filters
   */
  public @NotNull ItemAttributeFilter attributes() {
    return new ItemAttributeFilter(kinds, componentTypes, sizes, grades, itemClasses);
  }

  /**
   * Whether the stolen or not-stolen narrowing is set.
   *
   * @return {@code true} when either flag is set
   */
  public boolean stolenActive() {
    return stolenOnly || nonStolenOnly;
  }

  /**
   * Whether any dimension of the requested view narrows the result.
   *
   * @return {@code true} when an id list is non-empty, a quality floor or flag is set or an
   *     attribute narrows
   */
  public boolean anyActive() {
    boolean common =
        !jobOrderIds.isEmpty()
            || !locationIds.isEmpty()
            || personalOnly
            || nonPersonalOnly
            || stolenActive();
    if (itemsView()) {
      return common || !gameItemIds.isEmpty() || !attributes().isEmpty();
    }
    return common || !materialIds.isEmpty() || minQuality != null || !missionIds.isEmpty();
  }

  /**
   * The same query without the personal and shared flags, for the squadron-wide listing where they
   * do not apply.
   *
   * @return the query with {@code personalOnly} and {@code nonPersonalOnly} cleared
   */
  public @NotNull LagerQuery withoutPersonalScope() {
    return new LagerQuery(
        view,
        materialIds,
        minQuality,
        jobOrderIds,
        missionIds,
        gameItemIds,
        locationIds,
        false,
        false,
        stolenOnly,
        nonStolenOnly,
        kinds,
        componentTypes,
        sizes,
        grades,
        itemClasses);
  }

  /**
   * The query parameters of a backend read: {@code catalog=ITEM} for the items view, then the
   * filters of the requested view, each only when it narrows.
   *
   * @return the parameters, in a fixed order
   */
  public @NotNull MultiValueMap<String, String> backendParams() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    if (itemsView()) {
      params.add(P_CATALOG, "ITEM");
    }
    addFilterParams(params);
    return params;
  }

  /**
   * The query parameters of the page URL: {@code view=items} for the items view, then the filters
   * of the requested view, each only when it narrows. {@link #fromParams} reads them back.
   *
   * @return the parameters, in a fixed order
   */
  public @NotNull MultiValueMap<String, String> urlParams() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    if (itemsView()) {
      params.add(P_VIEW, VIEW_ITEMS);
    }
    addFilterParams(params);
    return params;
  }

  private void addFilterParams(@NotNull MultiValueMap<String, String> params) {
    boolean items = itemsView();
    if (!items) {
      addAll(params, P_MATERIAL_IDS, materialIds);
    } else {
      addAll(params, P_GAME_ITEM_IDS, gameItemIds);
    }
    addAll(params, P_LOCATION_IDS, locationIds);
    if (!items && minQuality != null) {
      params.add(P_MIN_QUALITY, minQuality.toString());
    }
    addAll(params, P_JOB_ORDER_IDS, jobOrderIds);
    if (!items) {
      addAll(params, P_MISSION_IDS, missionIds);
    }
    addFlag(params, P_PERSONAL_ONLY, personalOnly);
    addFlag(params, P_NON_PERSONAL_ONLY, nonPersonalOnly);
    addFlag(params, P_STOLEN_ONLY, stolenOnly);
    addFlag(params, P_NON_STOLEN_ONLY, nonStolenOnly);
    if (items) {
      addAll(params, P_KINDS, kinds);
      addAll(params, P_COMPONENT_TYPES, componentTypes);
      addAll(params, P_SIZES, sizes);
      addAll(params, P_GRADES, grades);
      addAll(params, P_ITEM_CLASSES, itemClasses);
    }
  }

  private static void addAll(
      @NotNull MultiValueMap<String, String> params,
      @NotNull String name,
      @NotNull List<?> values) {
    values.forEach(value -> params.add(name, value.toString()));
  }

  private static void addFlag(
      @NotNull MultiValueMap<String, String> params, @NotNull String name, boolean set) {
    if (set) {
      params.add(name, "true");
    }
  }

  private static @NotNull @Unmodifiable List<UUID> distinctIds(@Nullable List<UUID> ids) {
    return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
  }

  private static @Nullable String first(
      @NotNull Map<String, List<String>> params, @NotNull String name) {
    List<String> values = params.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static @NotNull List<String> strings(
      @NotNull Map<String, List<String>> params, @NotNull String name) {
    List<String> values = params.get(name);
    return values == null ? List.of() : values;
  }

  private static boolean flag(@NotNull Map<String, List<String>> params, @NotNull String name) {
    return "true".equalsIgnoreCase(first(params, name));
  }

  private static @Nullable Integer integer(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException _) {
      return null;
    }
  }

  private static @NotNull List<Integer> integers(
      @NotNull Map<String, List<String>> params, @NotNull String name) {
    List<Integer> result = new ArrayList<>();
    for (String value : strings(params, name)) {
      Integer parsed = integer(value);
      if (parsed != null) {
        result.add(parsed);
      }
    }
    return result;
  }

  private static @NotNull List<UUID> uuids(
      @NotNull Map<String, List<String>> params, @NotNull String name) {
    List<UUID> result = new ArrayList<>();
    for (String value : strings(params, name)) {
      try {
        result.add(UUID.fromString(value.trim()));
      } catch (IllegalArgumentException _) {
        continue;
      }
    }
    return result;
  }
}
