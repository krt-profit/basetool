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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CatalogPages}, the shared admin-catalogue page walk (REQ-ADMIN-001/002,
 * ADR-0102). Pins the walk-until-last-page concatenation, the single-request common case, the
 * {@code MAX_CATALOG_PAGES} runaway backstop with its {@code truncated} flag, the authoritative
 * {@code totalElements} passthrough and the exception-transparency contract.
 */
class CatalogPagesTest {

  /** Builds one page of the given content with explicit page math. */
  private static PageResponse<String> page(
      List<String> content, int pageIndex, long totalElements, int totalPages) {
    return new PageResponse<>(content, pageIndex, content.size(), totalElements, totalPages, null);
  }

  @Test
  void fetchAll_concatenatesAllPagesInOrder() {
    List<List<String>> pages = List.of(List.of("a", "b"), List.of("c", "d"), List.of("e"));
    AtomicInteger calls = new AtomicInteger();

    CompleteCatalog<String> catalog =
        CatalogPages.fetchAll(
            p -> {
              calls.incrementAndGet();
              return page(pages.get(p), p, 5, 3);
            });

    assertEquals(List.of("a", "b", "c", "d", "e"), catalog.items());
    assertEquals(5L, catalog.totalElements());
    assertFalse(catalog.truncated(), "a completed walk must not be flagged truncated");
    assertEquals(3, calls.get(), "exactly one request per backend page");
  }

  @Test
  void fetchAll_singlePage_issuesExactlyOneRequest() {
    AtomicInteger calls = new AtomicInteger();

    CompleteCatalog<String> catalog =
        CatalogPages.fetchAll(
            p -> {
              calls.incrementAndGet();
              return page(List.of("only"), p, 1, 1);
            });

    assertEquals(List.of("only"), catalog.items());
    assertEquals(1, calls.get(), "the common case must stay a single round-trip");
    assertFalse(catalog.truncated());
  }

  @Test
  void fetchAll_capHit_flagsTruncated_andKeepsBackendTotal() {
    int reportedPages = CatalogPages.MAX_CATALOG_PAGES + 5;
    AtomicInteger calls = new AtomicInteger();

    CompleteCatalog<String> catalog =
        CatalogPages.fetchAll(
            p -> {
              calls.incrementAndGet();
              return page(List.of("row-" + p), p, reportedPages, reportedPages);
            });

    assertTrue(catalog.truncated(), "stopping at the cap must be loud, never silent");
    assertEquals(CatalogPages.MAX_CATALOG_PAGES, calls.get(), "the walk must stop at the cap");
    assertEquals(CatalogPages.MAX_CATALOG_PAGES, catalog.items().size());
    assertEquals(
        reportedPages,
        catalog.totalElements(),
        "the reported total must stay the backend's full count, not the gathered size");
  }

  @Test
  void fetchAll_nullResponse_yieldsEmptyCatalog() {
    CompleteCatalog<String> catalog = CatalogPages.fetchAll(p -> null);

    assertTrue(catalog.items().isEmpty());
    assertEquals(0L, catalog.totalElements());
    assertFalse(catalog.truncated());
  }

  @Test
  void fetchAll_emptyPage_endsWalk() {
    AtomicInteger calls = new AtomicInteger();

    CompleteCatalog<String> catalog =
        CatalogPages.fetchAll(
            p -> {
              calls.incrementAndGet();
              return page(List.of(), p, 3, 3);
            });

    assertTrue(catalog.items().isEmpty());
    assertEquals(1, calls.get(), "an empty page must terminate the walk");
    assertFalse(catalog.truncated());
  }

  @Test
  void fetchAll_totalNeverBelowGatheredSize() {
    CompleteCatalog<String> catalog = CatalogPages.fetchAll(p -> page(List.of("a", "b"), p, 0, 1));

    assertEquals(2L, catalog.totalElements());
  }

  @Test
  void fetchAll_propagatesFetcherException() {
    RuntimeException boom = new RuntimeException("backend down");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                CatalogPages.fetchAll(
                    p -> {
                      throw boom;
                    }));
    assertEquals(boom, thrown, "the walk must not swallow or wrap fetch failures");
  }

  /** The degraded-value factory yields no items, a zero total and no truncation flag. */
  @Test
  void empty_returnsNoItemsNotTruncated() {
    CompleteCatalog<Object> catalog = CompleteCatalog.empty();
    assertTrue(catalog.items().isEmpty());
    assertEquals(0L, catalog.totalElements());
    assertFalse(catalog.truncated());
  }
}
