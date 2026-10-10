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

package de.greluc.krt.profit.basetool.backend.filter;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The one classification of every API family as {@code no-store} or revalidatable (REQ-SEC-031),
 * shared by {@link ApiCacheControlFilter} and the ETag filter.
 *
 * <p>The most specific matching family decides, so a sub-family can differ from its parent. An
 * {@code /api} path no family classifies is treated as {@code no-store}, and a test over the real
 * handler mappings fails on it.
 */
public final class NoStoreApiScopes {

  /** How the GET responses of one API family may be cached. */
  public enum Caching {

    /** {@code private, no-store}: personal or member-specific data that no cache may keep. */
    NO_STORE,

    /** {@code no-cache, must-revalidate}: shared or reference data, storable but revalidated. */
    REVALIDATE
  }

  /** The families whose GET bodies must not be stored anywhere. */
  private static final List<String> NO_STORE_FAMILIES =
      List.of(
          "/api/v1/audit/**",
          "/api/v1/bank/**",
          "/api/v1/blueprints/admin/**",
          "/api/v1/catalog/admin/**",
          "/api/v1/connected-apps/**",
          "/api/v1/exchange/**",
          "/api/v1/finance-entries/**",
          "/api/v1/hangar/**",
          "/api/v1/inventory/**",
          "/api/v1/leitung/**",
          "/api/v1/live-sync/**",
          "/api/v1/material-exchange/**",
          "/api/v1/material-requests/**",
          "/api/v1/me/**",
          "/api/v1/missions/*/finance-entries/**",
          "/api/v1/notifications/**",
          "/api/v1/operations/**",
          "/api/v1/orders/**",
          "/api/v1/org-chart/**",
          "/api/v1/org-units/bank/**",
          "/api/v1/personal-blueprints/**",
          "/api/v1/personal-inventory/**",
          "/api/v1/promotion/**",
          "/api/v1/refinery-orders/**",
          "/api/v1/roles/**",
          "/api/v1/special-commands/*/members/**",
          "/api/v1/squadrons/*/members/**",
          "/api/v1/terms/**",
          "/api/v1/users/**");

  /** The families whose GET bodies are shared or reference data and only need revalidation. */
  private static final List<String> REVALIDATE_FAMILIES =
      List.of(
          "/api/v1/announcement/**",
          "/api/v1/app/**",
          "/api/v1/blueprints/**",
          "/api/v1/cities/**",
          "/api/v1/exchange/catalog/**",
          "/api/v1/frequency-types/**",
          "/api/v1/job-types/**",
          "/api/v1/kommando-groups/**",
          "/api/v1/locations/**",
          "/api/v1/manufacturers/**",
          "/api/v1/material-categories/**",
          "/api/v1/material-external-aliases/**",
          "/api/v1/materials/**",
          "/api/v1/missions/**",
          "/api/v1/orders/item-catalog/**",
          "/api/v1/org-hierarchy/**",
          "/api/v1/org-units/**",
          "/api/v1/outposts/**",
          "/api/v1/pois/**",
          "/api/v1/quality-tiers/**",
          "/api/v1/refining-methods/**",
          "/api/v1/settings/**",
          "/api/v1/ship-types/**",
          "/api/v1/space-stations/**",
          "/api/v1/special-commands/**",
          "/api/v1/squadrons/**",
          "/api/v1/star-systems/**",
          "/api/v1/terminals/**",
          "/api/v1/terms/document",
          "/api/v1/uex/**");

  /** The surface the classification is about. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  /** Every family with its class, most specific first, so the first match decides. */
  private static final List<Map.Entry<PathPattern, Caching>> FAMILIES =
      Stream.concat(
              NO_STORE_FAMILIES.stream().map(p -> Map.entry(parse(p), Caching.NO_STORE)),
              REVALIDATE_FAMILIES.stream().map(p -> Map.entry(parse(p), Caching.REVALIDATE)))
          .sorted(Map.Entry.comparingByKey())
          .toList();

  /** Not instantiable; the classification and its lookup are all this type carries. */
  private NoStoreApiScopes() {}

  /**
   * Parses one family pattern with the shared parser.
   *
   * @param pattern the family pattern
   * @return the parsed pattern
   */
  @NotNull
  private static PathPattern parse(@NotNull String pattern) {
    return PathPatternParser.defaultInstance.parse(pattern);
  }

  /**
   * Answers whether a request URI must answer {@code no-store}, matched against the decoded path
   * (REQ-SEC-029).
   *
   * @param uri the raw request URI; {@code null} answers {@code false}
   * @return {@code true} for an {@code /api} path not classified as revalidatable
   */
  public static boolean matches(@Nullable String uri) {
    return uri != null && matches(PathContainer.parsePath(uri));
  }

  /**
   * Answers whether an already-parsed request path must answer {@code no-store}.
   *
   * @param path the parsed request path
   * @return {@code true} for an {@code /api} path not classified as revalidatable
   */
  public static boolean matches(@NotNull PathContainer path) {
    return API_SCOPE.matches(path) && classify(path) != Caching.REVALIDATE;
  }

  /**
   * Classifies a path by the most specific family that matches it.
   *
   * @param path the parsed request path
   * @return the family's class, or {@code null} when no family matches
   */
  @Nullable
  public static Caching classify(@NotNull PathContainer path) {
    Map.Entry<PathPattern, Caching> family = mostSpecificFamily(path);
    return family == null ? null : family.getValue();
  }

  /**
   * Names the most specific family that matches a path.
   *
   * @param path the parsed request path
   * @return the family's pattern, or {@code null} when no family matches
   */
  @Nullable
  public static String familyOf(@NotNull PathContainer path) {
    Map.Entry<PathPattern, Caching> family = mostSpecificFamily(path);
    return family == null ? null : family.getKey().getPatternString();
  }

  /**
   * Finds the most specific family matching a path.
   *
   * @param path the parsed request path
   * @return the family with its class, or {@code null} when none matches
   */
  @Nullable
  private static Map.Entry<PathPattern, Caching> mostSpecificFamily(@NotNull PathContainer path) {
    for (Map.Entry<PathPattern, Caching> family : FAMILIES) {
      if (family.getKey().matches(path)) {
        return family;
      }
    }
    return null;
  }

  /**
   * The {@code no-store} family patterns, in declaration order.
   *
   * @return the patterns
   */
  @NotNull
  @Unmodifiable
  public static List<String> noStoreFamilies() {
    return NO_STORE_FAMILIES;
  }

  /**
   * The revalidatable family patterns, in declaration order.
   *
   * @return the patterns
   */
  @NotNull
  @Unmodifiable
  public static List<String> revalidateFamilies() {
    return REVALIDATE_FAMILIES;
  }

  /**
   * The number of {@code no-store} families, so a test can assert the list was not emptied.
   *
   * @return how many {@code no-store} patterns are frozen here
   */
  public static int size() {
    return NO_STORE_FAMILIES.size();
  }
}
