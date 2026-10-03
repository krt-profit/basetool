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

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil.PageCeiling;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Tests the kernel page policy: a {@value PaginationUtil#MAX_PAGE_SIZE} default ceiling and an
 * exact, reviewed list of lists that opt out with {@link PageCeiling#LOAD_ALL} (REQ-API-005).
 */
class PageCeilingTest {

  /** The prefix stripped from a class name to name an opt-out. */
  private static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool.backend.";

  /**
   * The handlers allowed to opt out, as {@code package.Class.method}, each requested above the
   * default by a caller: the frontend's price matrix ({@code size=100000}), price overview and
   * per-material prices ({@code size=10000}), and its terminal and UEX location catalogues ({@code
   * size=10000}).
   */
  private static final Set<String> REVIEWED_OPT_OUTS =
      Set.of(
          "controller.CityController.getAllCities",
          "controller.MaterialController.getMaterialMatrixItems",
          "controller.MaterialController.getMaterialPriceOverview",
          "controller.MaterialController.getMaterialPrices",
          "controller.OutpostController.getAllOutposts",
          "controller.PoiController.getAllPois",
          "controller.SpaceStationController.getAllSpaceStations",
          "controller.TerminalController.getAllTerminals");

  /**
   * Lists every method that reads {@link PageCeiling#LOAD_ALL}, relative to the backend package.
   *
   * @param classes the classes to search
   * @return {@code Class.method} names, sorted
   */
  @NotNull
  private static Set<String> optOuts(@NotNull JavaClasses classes) {
    Set<String> callers = new TreeSet<>();
    classes.forEach(
        javaClass ->
            javaClass.getFieldAccessesFromSelf().stream()
                .filter(access -> access.getTargetOwner().isEquivalentTo(PageCeiling.class))
                .filter(access -> access.getTarget().getName().equals(PageCeiling.LOAD_ALL.name()))
                .filter(access -> !access.getOriginOwner().isEquivalentTo(PageCeiling.class))
                .forEach(
                    access ->
                        callers.add(
                            access.getOriginOwner().getName().substring(BACKEND_PACKAGE.length())
                                + "."
                                + access.getOrigin().getName())));
    return callers;
  }

  @Test
  @DisplayName("an absent or non-positive size falls back to the default page")
  void absentSizeDefaults() {
    assertThat(sizeOf(null, PageCeiling.DEFAULT)).isEqualTo(PaginationUtil.DEFAULT_PAGE_SIZE);
    assertThat(sizeOf(0, PageCeiling.LOAD_ALL)).isEqualTo(PaginationUtil.DEFAULT_PAGE_SIZE);
  }

  @Test
  @DisplayName("the default ceiling clamps a large page to 1,000")
  void theDefaultCeilingClamps() {
    assertThat(sizeOf(1_000, PageCeiling.DEFAULT)).isEqualTo(1_000);
    assertThat(sizeOf(100_000, PageCeiling.DEFAULT)).isEqualTo(1_000);
    assertThat(
            PaginationUtil.createPageRequest(0, 5_000, null, Set.of("name", "id"), "name")
                .getPageSize())
        .isEqualTo(1_000);
    assertThat(PaginationUtil.createUnsortedPageRequest(0, 5_000).getPageSize()).isEqualTo(1_000);
  }

  @Test
  @DisplayName("the opt-out keeps the 100,000 ceiling")
  void theOptOutKeepsTheLargeCeiling() {
    assertThat(sizeOf(100_000, PageCeiling.LOAD_ALL)).isEqualTo(100_000);
    assertThat(sizeOf(500_000, PageCeiling.LOAD_ALL)).isEqualTo(100_000);
  }

  @Test
  @DisplayName("exactly the reviewed handlers opt out of the default ceiling")
  void onlyTheReviewedHandlersOptOut() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.greluc.krt.profit.basetool.backend");

    assertThat(optOuts(classes))
        .as("an opt-out is a reviewed decision: name the caller that needs the larger page")
        .isEqualTo(REVIEWED_OPT_OUTS);
  }

  @Test
  @DisplayName("a planted opt-out is detected")
  void aPlantedOptOutIsDetected() {
    JavaClasses planted = new ClassFileImporter().importClasses(PlantedLoadAllCaller.class);

    assertThat(optOuts(planted)).containsExactly("web.PageCeilingTest$PlantedLoadAllCaller.page");
  }

  /**
   * Builds a page request and reads its size.
   *
   * @param size the requested size
   * @param ceiling the ceiling to apply
   * @return the effective page size
   */
  private static int sizeOf(Integer size, @NotNull PageCeiling ceiling) {
    Pageable pageable =
        PaginationUtil.createPageRequest(0, size, null, Set.of("name", "id"), "name", ceiling);
    return pageable.getPageSize();
  }

  /** A test-only caller that opts out without review. */
  static final class PlantedLoadAllCaller {

    /** Not instantiable. */
    private PlantedLoadAllCaller() {}

    /**
     * Opts out of the default ceiling.
     *
     * @return a page request under the large ceiling
     */
    static Pageable page() {
      return PaginationUtil.createPageRequest(
          0, 5_000, null, Set.of("id"), "id", PageCeiling.LOAD_ALL);
    }
  }
}
