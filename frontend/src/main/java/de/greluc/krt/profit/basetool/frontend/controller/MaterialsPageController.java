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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MatrixGridDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for the materials pages: {@code /materials}, the {@code /materials/overview} matrix
 * and the {@code /materials/{id}} detail.
 *
 * <p>The matrix page renders a shell; the grid data comes as JSON from {@code
 * /materials/overview/data}, filtered server-side (REQ-UI-014) and assembled across all backend
 * pages. The unfiltered matrix is served from the shared catalogue cache.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/materials")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MaterialsPageController {

  /**
   * Terminal column of the matrix, ordered by star system, planet system, location-type group
   * (city, jump-point station, loading-dock station, other station, outpost, rest) and name.
   *
   * @param name terminal display name
   * @param nickname terminal short name
   * @param starSystemName parent star system; {@code null} or blank sorts last
   * @param planetName effective planet system (direct, via moon or via orbit); {@code null} or
   *     blank sorts last within its star system
   * @param planetCssClass CSS class for the planet tint, from {@link PlanetColorResolver}
   * @param cityName parent city, if any (highest grouping priority)
   * @param spaceStationName parent space station, if any
   * @param outpostName parent outpost, if any
   * @param isJumpPoint whether the parent station is a jump point (raises group priority)
   * @param hasLoadingDock whether the terminal has a loading dock
   * @param isAutoLoad whether the terminal supports automatic cargo loading
   */
  public record TerminalCol(
      String name,
      String nickname,
      String starSystemName,
      String planetName,
      String planetCssClass,
      String cityName,
      String spaceStationName,
      String outpostName,
      Boolean isJumpPoint,
      Boolean hasLoadingDock,
      Boolean isAutoLoad)
      implements Comparable<TerminalCol> {

    private int getGroupPriority() {
      if (cityName != null && !cityName.isBlank()) {
        return 1;
      }
      if (spaceStationName != null && !spaceStationName.isBlank()) {
        if (Boolean.TRUE.equals(isJumpPoint)) {
          return 2;
        }
        if (Boolean.TRUE.equals(hasLoadingDock)) {
          return 3;
        }
        return 4;
      }
      if (outpostName != null && !outpostName.isBlank()) {
        return 5;
      }
      return 6;
    }

    @Override
    public int compareTo(TerminalCol o) {
      String thisSystem = this.starSystemName != null ? this.starSystemName : "";
      String otherSystem = o.starSystemName != null ? o.starSystemName : "";
      int sysCmp = thisSystem.compareToIgnoreCase(otherSystem);
      if (sysCmp != 0) {
        return sysCmp;
      }

      boolean thisHasPlanet = this.planetName != null && !this.planetName.isBlank();
      boolean otherHasPlanet = o.planetName != null && !o.planetName.isBlank();
      if (thisHasPlanet != otherHasPlanet) {
        return thisHasPlanet ? -1 : 1;
      }
      if (thisHasPlanet) {
        int planetCmp = this.planetName.compareToIgnoreCase(o.planetName);
        if (planetCmp != 0) {
          return planetCmp;
        }
      }

      int group1 = this.getGroupPriority();
      int group2 = o.getGroupPriority();
      if (group1 != group2) {
        return Integer.compare(group1, group2);
      }

      String thisName = this.name != null ? this.name : "";
      String otherName = o.name != null ? o.name : "";
      return thisName.compareToIgnoreCase(otherName);
    }
  }

  /** Reads the materials, their prices and the trade matrix. */
  private final CatalogueBackendClient catalogueClient;

  /**
   * Limits concurrent page-walks of uncached filtered matrix slices to three, so bursts of distinct
   * filters cannot exhaust the heap; callers block until a permit is free.
   */
  private final Semaphore filteredMatrixFetchGuard = new Semaphore(3);

  /**
   * Renders the materials overview ({@code /materials}), grouped by category; uncategorised
   * materials appear under "Unsortiert".
   *
   * @param model Thymeleaf model populated with {@code materials} and {@code materialsByKind}
   * @return the {@code materials} view name
   */
  @NotNull
  @GetMapping
  public String listMaterials(Model model) {
    try {
      PageResponse<MaterialPriceOverviewDto> page = catalogueClient.materialPriceOverview();

      List<MaterialPriceOverviewDto> materials = new ArrayList<>();
      if (page != null && page.content() != null) {
        materials = new ArrayList<>(page.content());
      }

      Map<String, List<MaterialPriceOverviewDto>> materialsByKind = new TreeMap<>();
      for (MaterialPriceOverviewDto mat : materials) {
        String kind =
            mat.category() != null
                    && mat.category().name() != null
                    && !mat.category().name().isBlank()
                ? mat.category().name()
                : "Unsortiert";
        materialsByKind.computeIfAbsent(kind, _ -> new ArrayList<>()).add(mat);
      }
      materialsByKind
          .values()
          .forEach(
              list ->
                  list.sort(
                      Comparator.comparing(
                          MaterialPriceOverviewDto::name, String.CASE_INSENSITIVE_ORDER)));

      model.addAttribute("materials", materials);
      model.addAttribute("materialsByKind", materialsByKind);
    } catch (Exception e) {
      log.error("Error loading materials overview", e);
      model.addAttribute("error", "error.materials.load");
      model.addAttribute("materials", new ArrayList<>());
      model.addAttribute("materialsByKind", new TreeMap<>());
    }

    return "materials";
  }

  /**
   * Renders the matrix-overview shell ({@code /materials/overview}) with the full material-name and
   * star-system filter lists; the grid itself comes from {@link #getMatrixData}.
   *
   * @param model Thymeleaf model populated with the {@code materialNames} and {@code starSystems}
   *     filter source lists and the {@code uexAge} freshness hint
   * @return the {@code materials-overview} view name
   */
  @NotNull
  @GetMapping("/overview")
  public String getMatrixOverview(Model model) {
    model.addAttribute("uexAge", uexAge());
    try {
      List<MaterialMatrixItemDto> items = fetchMatrixItems();
      model.addAttribute(
          "starSystems",
          items.stream()
              .map(item -> item.starSystemName() != null ? item.starSystemName() : "")
              .filter(s -> !s.isEmpty())
              .collect(Collectors.toCollection(TreeSet::new)));
      model.addAttribute(
          "materialNames",
          items.stream()
              .map(MaterialMatrixItemDto::materialName)
              .collect(Collectors.toCollection(TreeSet::new)));
    } catch (Exception e) {
      log.error("Error loading materials matrix filters", e);
      model.addAttribute("error", "error.materials.matrix.load");
      model.addAttribute("starSystems", new TreeSet<>());
      model.addAttribute("materialNames", new TreeSet<>());
    }
    return "materials-overview";
  }

  /**
   * Returns the trade matrix as a {@link MatrixGridDto} for the virtual-scroll grid, with the four
   * filters applied server-side (REQ-UI-014). The unfiltered matrix is cached; filtered requests
   * are uncached and throttled by {@link #filteredMatrixFetchGuard}.
   *
   * @param materials material names to keep, or empty/absent for all
   * @param systems star-system names to keep, or empty/absent for all
   * @param loadingDock {@code true} to keep only terminals with a loading dock
   * @param autoLoad {@code true} to keep only terminals with automatic cargo loading
   * @return the reshaped (possibly filtered) grid, or an empty grid if the backend fetch fails
   */
  @GetMapping("/overview/data")
  @ResponseBody
  public MatrixGridDto getMatrixData(
      @RequestParam(required = false) List<String> materials,
      @RequestParam(required = false) List<String> systems,
      @RequestParam(defaultValue = "false") boolean loadingDock,
      @RequestParam(defaultValue = "false") boolean autoLoad) {
    try {
      boolean unfiltered =
          isEmptySelection(materials) && isEmptySelection(systems) && !loadingDock && !autoLoad;
      List<MaterialMatrixItemDto> items =
          unfiltered
              ? fetchMatrixItems()
              : fetchFilteredMatrixItems(materials, systems, loadingDock, autoLoad);
      return buildGrid(items);
    } catch (Exception e) {
      log.error("Error loading materials matrix data", e);
      return new MatrixGridDto(List.of(), List.of(), List.of());
    }
  }

  /**
   * Fetches the complete unfiltered matrix from the shared catalogue cache.
   *
   * @return the matrix rows, never {@code null}
   */
  @NotNull
  private List<MaterialMatrixItemDto> fetchMatrixItems() {
    PageResponse<MaterialMatrixItemDto> page = catalogueClient.materialsMatrix();
    if (page == null || page.content() == null) {
      return new ArrayList<>();
    }
    return new ArrayList<>(page.content());
  }

  /**
   * Fetches a filtered matrix slice across all backend pages, uncached (REQ-UI-014).
   *
   * @param materials material names to keep (non-empty)
   * @param systems star-system names to keep (may be empty)
   * @param loadingDock {@code true} to keep only terminals with a loading dock
   * @param autoLoad {@code true} to keep only terminals with automatic cargo loading
   * @return the matching matrix rows, never {@code null}
   */
  @NotNull
  private List<MaterialMatrixItemDto> fetchFilteredMatrixItems(
      List<String> materials, List<String> systems, boolean loadingDock, boolean autoLoad) {
    try {
      filteredMatrixFetchGuard.acquire();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting to fetch a filtered materials matrix slice");
      return new ArrayList<>();
    }
    try {
      CatalogueBackendClient.MatrixFilter filter =
          new CatalogueBackendClient.MatrixFilter(materials, systems, loadingDock, autoLoad);
      CatalogPages.CompleteCatalog<MaterialMatrixItemDto> walked =
          CatalogPages.fetchAll(page -> catalogueClient.filteredMatrixPage(filter, page));
      if (walked.truncated()) {
        log.warn(
            "Filtered materials matrix hit the page-walk safety cap of {} pages — the grid slice is"
                + " incomplete for this filter selection",
            CatalogPages.MAX_CATALOG_PAGES);
      }
      return new ArrayList<>(walked.items());
    } finally {
      filteredMatrixFetchGuard.release();
    }
  }

  /**
   * Whether a multi-select filter dimension carries no effective selection ({@code null} or empty
   * list), i.e. the dimension is unconstrained.
   *
   * @param values the selected values of one filter dimension
   * @return {@code true} when the dimension applies no filter
   */
  private static boolean isEmptySelection(List<String> values) {
    return values == null || values.isEmpty();
  }

  /**
   * Reshapes the flat matrix rows into a {@link MatrixGridDto}: ordered terminal columns,
   * star-system header spans and per-category material rows with sparse price cells.
   *
   * @param items the flat matrix rows; must not be {@code null}
   * @return the reshaped grid, never {@code null}
   */
  @NotNull
  private MatrixGridDto buildGrid(@NotNull List<MaterialMatrixItemDto> items) {
    Set<TerminalCol> terminals = new TreeSet<>();
    Map<String, Map<String, MatrixGridDto.Cell>> pricesByMaterial = new HashMap<>();
    Map<String, String> kindByMaterial = new HashMap<>();
    Map<String, boolean[]> flagsByMaterial = new HashMap<>();

    for (MaterialMatrixItemDto item : items) {
      String effectiveSystem = item.starSystemName() != null ? item.starSystemName() : "";
      terminals.add(
          new TerminalCol(
              item.terminalName(),
              item.terminalNickname(),
              effectiveSystem,
              item.planetName(),
              PlanetColorResolver.cssClassFor(effectiveSystem, item.planetName()),
              item.cityName(),
              item.spaceStationName(),
              item.outpostName(),
              item.isJumpPoint(),
              item.hasLoadingDock(),
              item.isAutoLoad()));

      String material = item.materialName();
      kindByMaterial.put(
          material,
          item.category() != null
                  && item.category().name() != null
                  && !item.category().name().isBlank()
              ? item.category().name()
              : "Unsortiert");
      boolean[] flags = flagsByMaterial.computeIfAbsent(material, _ -> new boolean[3]);
      if (Boolean.TRUE.equals(item.isIllegal())) {
        flags[0] = true;
      }
      if (Boolean.TRUE.equals(item.isVolatileQt())) {
        flags[1] = true;
      }
      if (Boolean.TRUE.equals(item.isVolatileTime())) {
        flags[2] = true;
      }
      pricesByMaterial
          .computeIfAbsent(material, _ -> new HashMap<>())
          .put(item.terminalName(), new MatrixGridDto.Cell(item.priceBuy(), item.priceSell()));
    }

    List<MatrixGridDto.Column> columns = new ArrayList<>(terminals.size());
    for (TerminalCol term : terminals) {
      columns.add(
          new MatrixGridDto.Column(
              term.name(),
              term.nickname(),
              term.starSystemName(),
              term.planetName(),
              term.planetCssClass(),
              Boolean.TRUE.equals(term.hasLoadingDock()),
              Boolean.TRUE.equals(term.isAutoLoad())));
    }

    List<MatrixGridDto.SystemGroup> systemGroups = new ArrayList<>();
    String currentSystem = null;
    int currentCount = 0;
    for (TerminalCol term : terminals) {
      if (currentSystem == null) {
        currentSystem = term.starSystemName();
        currentCount = 1;
      } else if (currentSystem.equals(term.starSystemName())) {
        currentCount++;
      } else {
        systemGroups.add(new MatrixGridDto.SystemGroup(currentSystem, currentCount));
        currentSystem = term.starSystemName();
        currentCount = 1;
      }
    }
    if (currentSystem != null) {
      systemGroups.add(new MatrixGridDto.SystemGroup(currentSystem, currentCount));
    }

    Map<String, List<MatrixGridDto.Row>> rowsByKind = new TreeMap<>();
    for (Map.Entry<String, Map<String, MatrixGridDto.Cell>> entry : pricesByMaterial.entrySet()) {
      String material = entry.getKey();
      boolean[] flags = flagsByMaterial.getOrDefault(material, new boolean[3]);
      rowsByKind
          .computeIfAbsent(kindByMaterial.get(material), _ -> new ArrayList<>())
          .add(new MatrixGridDto.Row(material, flags[0], flags[1], flags[2], entry.getValue()));
    }
    rowsByKind
        .values()
        .forEach(
            list ->
                list.sort(
                    Comparator.comparing(
                        MatrixGridDto.Row::materialName, String.CASE_INSENSITIVE_ORDER)));

    List<MatrixGridDto.Group> groups = new ArrayList<>(rowsByKind.size());
    for (Map.Entry<String, List<MatrixGridDto.Row>> entry : rowsByKind.entrySet()) {
      groups.add(new MatrixGridDto.Group(entry.getKey(), entry.getValue()));
    }

    return new MatrixGridDto(columns, systemGroups, groups);
  }

  /**
   * Renders the material detail page ({@code /materials/{id}}) with the complete price list across
   * all backend pages (REQ-UI-015), the terminal rows sorted by sale price and the four price
   * figures. On backend failure the model stays empty.
   *
   * @param id material id
   * @param model Thymeleaf model populated with {@code material}, {@code prices}, {@code
   *     terminalRows}, {@code priceSummary} and {@code uexAge}
   * @return the {@code material-detail} view name
   */
  @NotNull
  @GetMapping("/{id}")
  public String getMaterialDetail(@PathVariable @NotNull UUID id, Model model) {
    model.addAttribute("uexAge", uexAge());
    try {
      MaterialDto material = catalogueClient.material(id);
      model.addAttribute("material", material);

      CatalogPages.CompleteCatalog<MaterialPriceDto> prices =
          CatalogPages.fetchAll(page -> catalogueClient.materialPricePage(id, page));
      if (prices.truncated()) {
        log.warn(
            "Material {} price list hit the page-walk safety cap of {} pages — the detail table is"
                + " incomplete",
            id,
            CatalogPages.MAX_CATALOG_PAGES);
      }
      List<MaterialPriceDto> priceList = new ArrayList<>(prices.items());
      model.addAttribute("prices", priceList);
      List<MaterialTerminalPrices.Row> rows =
          MaterialTerminalPrices.rows(priceList, matrixItemsOf(id));
      model.addAttribute("terminalRows", rows);
      model.addAttribute("priceSummary", MaterialTerminalPrices.summarize(rows));
    } catch (Exception e) {
      log.error("Error loading material detail for id {}", id, e);
      model.addAttribute("error", "error.material.details.load");
      model.addAttribute("material", null);
      model.addAttribute("prices", new ArrayList<>());
      model.addAttribute("terminalRows", List.of());
      model.addAttribute("priceSummary", MaterialTerminalPrices.summarize(List.of()));
    }
    return "material-detail";
  }

  /**
   * The cached price-matrix rows of one material, which carry each terminal's location; a failure
   * yields no rows, so the detail table only loses its locations.
   *
   * @param id the material
   * @return the material's matrix rows, never {@code null}
   */
  @NotNull
  private List<MaterialMatrixItemDto> matrixItemsOf(@NotNull UUID id) {
    try {
      return MaterialTerminalPrices.forMaterial(fetchMatrixItems(), id);
    } catch (Exception e) {
      log.warn("Price matrix unavailable for the locations of material {}", id, e);
      return List.of();
    }
  }

  /**
   * How long ago the last UEX sweep ran, read from the cached terminal catalogue.
   *
   * @return the age, or {@code null} when the catalogue is unavailable or carries no sweep time
   */
  @Nullable
  private UexAge uexAge() {
    try {
      PageResponse<TerminalDto> terminals = catalogueClient.terminalCatalogue();
      return UexAge.of(UexAge.latestSync(terminals), Instant.now());
    } catch (Exception e) {
      log.warn("Terminal catalogue unavailable for the UEX freshness hint", e);
      return null;
    }
  }
}
