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

package de.greluc.krt.profit.basetool.backend.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Translates pagination query parameters into a {@link Pageable}: sorting only on whitelisted
 * fields, {@code id} appended as tiebreaker, and {@code size} clamped to {@value #MAX_PAGE_SIZE}.
 *
 * <p>The ceiling is high because some views load everything in one request; long-running queries
 * are bounded by the statement timeout instead (REQ-DATA-009).
 */
public final class PaginationUtil {

  /**
   * Upper bound on the {@code size} query parameter. High by design so the "load all in one
   * request" surfaces (material trade matrix, admin material / member / UEX lists, org-unit
   * pickers) are not truncated; the query-execution timeout (REQ-DATA-009) is what bounds a heavy
   * fetch's hold on a database connection. See the class Javadoc (SEC-03).
   */
  public static final int MAX_PAGE_SIZE = 100_000;

  /** Default {@code size} when the caller supplies none or a non-positive value. */
  public static final int DEFAULT_PAGE_SIZE = 50;

  private PaginationUtil() {}

  /**
   * Builds a {@link Pageable} from raw query parameters.
   *
   * <p>{@code page} defaults to 0 and is clamped to at least 0; {@code size} defaults to 50 and is
   * clamped to {@code [1, 100000]}. {@code sort} is a semicolon-separated list of {@code
   * field,asc|desc} tokens; {@code id} is appended as tiebreaker when whitelisted.
   *
   * @param pageParam zero-based page index, may be {@code null}
   * @param sizeParam page size, may be {@code null}
   * @param sortParam raw {@code sort} query parameter, may be {@code null} or blank
   * @param allowedSortFields whitelist of sortable field names (must include {@code
   *     defaultSortField})
   * @param defaultSortField fallback field used when {@code sortParam} is null/blank
   * @return a {@link Pageable} ready to hand to a repository
   * @throws IllegalArgumentException when {@code sortParam} contains a field not in {@code
   *     allowedSortFields}
   */
  public static Pageable createPageRequest(
      Integer pageParam,
      Integer sizeParam,
      String sortParam,
      Set<String> allowedSortFields,
      String defaultSortField) {
    int page = pageParam == null || pageParam < 0 ? 0 : pageParam;
    int size =
        sizeParam == null || sizeParam <= 0
            ? DEFAULT_PAGE_SIZE
            : Math.min(sizeParam, MAX_PAGE_SIZE);

    Sort sort = resolveSort(sortParam, allowedSortFields, defaultSortField);
    if (!containsProperty(sort, "id") && allowedSortFields.contains("id")) {
      sort = sort.and(Sort.by("id"));
    }
    return PageRequest.of(page, size, sort);
  }

  /**
   * Builds an unsorted {@link Pageable} with the same page and size clamping, for queries that
   * define their own {@code ORDER BY}.
   *
   * @param pageParam zero-based page index, may be {@code null}
   * @param sizeParam page size, may be {@code null}
   * @return an unsorted {@link Pageable} ready to hand to a custom-ordered repository query
   */
  public static Pageable createUnsortedPageRequest(Integer pageParam, Integer sizeParam) {
    int page = pageParam == null || pageParam < 0 ? 0 : pageParam;
    int size =
        sizeParam == null || sizeParam <= 0
            ? DEFAULT_PAGE_SIZE
            : Math.min(sizeParam, MAX_PAGE_SIZE);
    return PageRequest.of(page, size);
  }

  private static boolean containsProperty(Sort sort, String property) {
    for (Sort.Order order : sort) {
      if (order.getProperty().equalsIgnoreCase(property)) {
        return true;
      }
    }
    return false;
  }

  private static Sort resolveSort(String sortParam, Set<String> allowed, String defaultField) {
    if (sortParam == null || sortParam.isBlank()) {
      return Sort.by(defaultField).ascending();
    }
    List<Sort.Order> orders = new ArrayList<>();
    String[] parts = sortParam.split("[;]");
    for (String part : parts) {
      String[] tokens = part.split(",");
      String field = tokens[0].trim();
      if (!allowed.contains(field)) {
        throw new IllegalArgumentException("Unsupported sort field: " + field);
      }
      Sort.Direction dir = Sort.Direction.ASC;
      if (tokens.length > 1) {
        String d = tokens[1].trim();
        if (d.equalsIgnoreCase("desc")) {
          dir = Sort.Direction.DESC;
        }
      }
      orders.add(new Sort.Order(dir, field));
    }
    if (orders.isEmpty()) {
      return Sort.by(defaultField).ascending();
    }
    return Sort.by(orders);
  }

  /**
   * Renders a {@link Sort} back into the {@code field,direction} form echoed in {@code
   * PageResponse.sort}.
   *
   * @param sort sort object as built by {@link #createPageRequest}
   * @return list of {@code field,asc|desc} tokens in declaration order
   */
  public static List<String> toSortStrings(@NotNull Sort sort) {
    return sort.stream()
        .map(o -> o.getProperty() + "," + o.getDirection().name().toLowerCase())
        .toList();
  }
}
