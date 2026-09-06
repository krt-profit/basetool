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

package de.greluc.krt.profit.basetool.frontend.service;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Unit tests for {@link CachedCatalogListLoader}, pinning its degrade-to-empty contract: a page's
 * content is returned as a fresh mutable list, and a null page / null content / backend failure all
 * collapse to an empty list rather than propagating (so one dead reference catalog never blanks a
 * page).
 */
@ExtendWith(MockitoExtension.class)
class CachedCatalogListLoaderTest {

  private static final ParameterizedTypeReference<PageResponse<String>> PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  @Mock private BackendApiClient backendApiClient;
  @InjectMocks private CachedCatalogListLoader loader;

  @Test
  void returnsFreshMutableCopyOfPageContent() {
    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of("Aurora", "Cutlass"), 0, 10, 2L, 1, List.of()));

    List<String> result =
        loader.loadPageContent(CachedCatalog.SHIP_TYPES, PAGE_TYPE, "ship types");

    assertThat(result).containsExactly("Aurora", "Cutlass");
    // Must be a fresh mutable list the caller can sort/extend in place.
    result.add("Freelancer");
    assertThat(result).hasSize(3);
  }

  @Test
  void nullPage_returnsEmptyList() {
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS), anyTypeRef()))
        .thenReturn(null);

    assertThat(loader.loadPageContent(CachedCatalog.LOCATIONS, PAGE_TYPE, "locations"))
        .isEmpty();
  }

  @Test
  void nullContent_returnsEmptyList() {
    when(backendApiClient.getCached(eq(CachedCatalog.MANUFACTURERS), anyTypeRef()))
        .thenReturn(new PageResponse<>(null, 0, 10, 0L, 0, List.of()));

    assertThat(
            loader.loadPageContent(CachedCatalog.MANUFACTURERS, PAGE_TYPE, "manufacturers"))
        .isEmpty();
  }

  @Test
  void backendFailure_degradesToEmptyList_ratherThanPropagating() {
    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    assertThat(loader.loadPageContent(CachedCatalog.SHIP_TYPES, PAGE_TYPE, "ship types"))
        .isEmpty();
  }
}
