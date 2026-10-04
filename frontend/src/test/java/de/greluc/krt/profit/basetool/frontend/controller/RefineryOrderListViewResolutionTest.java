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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for how the refinery list maps its {@code view} and legacy {@code status} parameters
 * onto a segment and an exact filter, and how its pagination links keep them.
 */
class RefineryOrderListViewResolutionTest {

  /** A valid view wins, case-insensitively; an unknown one falls back to the status mapping. */
  @Test
  void viewParameterSelectsTheSegment() {
    assertThat(RefineryOrderPageController.resolveView("ready", null)).isEqualTo("READY");
    assertThat(RefineryOrderPageController.resolveView("ALL", List.of("COMPLETED")))
        .isEqualTo("ALL");
    assertThat(RefineryOrderPageController.resolveView("bogus", List.of("COMPLETED")))
        .isEqualTo("COMPLETED");
    assertThat(RefineryOrderPageController.resolveView(null, null)).isEqualTo("RUNNING");
  }

  /** Former status filters map onto their segment; other subsets stay exact. */
  @Test
  void legacyStatusFilterMapsOrStaysExact() {
    assertThat(RefineryOrderPageController.resolveView(null, List.of("OPEN", "IN_PROGRESS")))
        .isEqualTo("RUNNING");
    assertThat(RefineryOrderPageController.exactStatusFilter(List.of("OPEN", "IN_PROGRESS")))
        .isNull();
    assertThat(RefineryOrderPageController.exactStatusFilter(List.of("COMPLETED"))).isNull();
    assertThat(
            RefineryOrderPageController.exactStatusFilter(
                List.of("CANCELED", "COMPLETED", "IN_PROGRESS", "OPEN")))
        .isNull();
    assertThat(RefineryOrderPageController.exactStatusFilter(List.of("canceled")))
        .containsExactly("CANCELED");
    assertThat(RefineryOrderPageController.resolveView(null, List.of("CANCELED"))).isEqualTo("ALL");
    assertThat(RefineryOrderPageController.exactStatusFilter(List.of("OPEN,COMPLETED")))
        .containsExactly("OPEN", "COMPLETED");
    assertThat(RefineryOrderPageController.exactStatusFilter(List.of("nonsense"))).isNull();
  }

  /**
   * Each segment maps onto one backend filter: the open segments by end, {@code READY} with the
   * ready filter, the closed ones by start; the search becomes the {@code q} template variable.
   */
  @Test
  void segmentsMapOntoOneServerSidePage() {
    assertThat(
            RefineryOrderPageController.orderPageUri(
                "/api/v1/refinery-orders/all",
                RefineryOrderPageController.segmentQuery("RUNNING", null),
                2,
                10))
        .isEqualTo(
            "/api/v1/refinery-orders/all?page=2&size=10&sort=endsAt,asc&status=OPEN,IN_PROGRESS");
    assertThat(
            RefineryOrderPageController.orderPageUri(
                "/api/v1/refinery-orders/my-orders",
                RefineryOrderPageController.segmentQuery("READY", "Gold"),
                0,
                50))
        .isEqualTo(
            "/api/v1/refinery-orders/my-orders?page=0&size=50&sort=endsAt,asc"
                + "&status=OPEN,IN_PROGRESS&ready=true&q={q}");
    assertThat(RefineryOrderPageController.segmentQuery("COMPLETED", null))
        .isEqualTo(
            new RefineryOrderPageController.ListQuery(
                List.of("COMPLETED"), false, null, "startedAt,desc"));
    assertThat(RefineryOrderPageController.segmentQuery("ALL", "x").statuses())
        .containsExactly("OPEN", "IN_PROGRESS", "COMPLETED", "CANCELED");
  }

  /** The pagination base keeps the segment or exact filter, the switch and the encoded search. */
  @Test
  void paginationBaseKeepsTheFilter() {
    assertThat(RefineryOrderPageController.buildPaginationBaseUrl("READY", null, true, "Gold Erz"))
        .isEqualTo("/refinery-orders?view=READY&onlyMine=true&q=Gold%20Erz");
    assertThat(
            RefineryOrderPageController.buildPaginationBaseUrl(
                "ALL", List.of("CANCELED"), false, null))
        .isEqualTo("/refinery-orders?status=CANCELED");
  }
}
