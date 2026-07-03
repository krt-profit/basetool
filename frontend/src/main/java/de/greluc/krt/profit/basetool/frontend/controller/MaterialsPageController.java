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

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MatrixGridDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring MVC controller for the materials browsing pages ({@code /materials}, {@code
 * /materials/overview} matrix, {@code /materials/{id}} detail).
 *
 * <p>The overview and detail pages stay simple — list + by-category groups, detail + price list.
 * The matrix is the heaviest read path in the frontend. It is split in two: {@code GET
 * /materials/overview} renders only a lightweight shell (filters + an empty grid container), and
 * {@code GET /materials/overview/data} returns the whole matrix as one lean {@link MatrixGridDto}
 * JSON document. The browser's virtual-scroll grid ({@code /js/materials-matrix.js}) then
 * materializes only the currently visible rows into the DOM and does all filtering client-side, so
 * a multi-thousand-cell universe no longer freezes the page by forcing the browser to build the
 * entire dense table at once. The underlying 100 000-row backend fetch is cached (see {@link
 * #getMatrixData}); the server-side reshaping into columns/rows lives in {@link #buildGrid}.
 */
@Controller
@RequestMapping("/materials")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MaterialsPageController {

  /**
   * Terminal column for the matrix. Sorts first by star system, then by effective planet system (so
   * terminals on the same planet/moon/orbit stay visually contiguous), then by location-type group
   * (city &lt; jump-point space station &lt; loading-dock space station &lt; other station &lt;
   * outpost &lt; everything else), and finally alphabetically by name. The order is meaningful for
   * the template — it controls the visual grouping of column headers and ensures planet-tint
   * stripes form unbroken bands.
   *
   * @param name terminal display name
   * @param nickname terminal short name
   * @param starSystemName parent star system; {@code null} or blank pushes the column to the back
   * @param planetName effective planet system this terminal belongs to (direct, via parent moon, or
   *     via like-named orbit); {@code null} or blank pushes the column to the end of its star
   *     system
   * @param planetCssClass CSS class derived from {@code planetName} via {@link
   *     PlanetColorResolver}; controls the planet-color tint applied to the column header and a
   *     thin top-border stripe on each body cell
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

      // Planet-less terminals (jump points / Lagrange) sink to the end of their star system so
      // the planet-tinted block stays contiguous. Within the planet-less tail the existing
      // group/name ordering still applies.
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

  private final BackendApiClient backendApiClient;

  /** Response type for the materials price-overview page fetch backing the accordion. */
  private static final ParameterizedTypeReference<PageResponse<MaterialPriceOverviewDto>>
      MATERIAL_PRICE_OVERVIEW_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for the full trade-matrix projection fetch feeding the virtual-scroll grid. */
  private static final ParameterizedTypeReference<PageResponse<MaterialMatrixItemDto>>
      MATERIAL_MATRIX_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for a material's per-terminal price list on the detail page. */
  private static final ParameterizedTypeReference<PageResponse<MaterialPriceDto>>
      MATERIAL_PRICE_PAGE_TYPE = new ParameterizedTypeReference<>() {};

  /**
   * Renders the materials overview ({@code /materials}). Fetches the price-overview projection for
   * all materials in one large page and groups them by category for the template's accordion
   * layout. Materials without a category land under "Unsortiert" so they remain visible.
   *
   * @param model Thymeleaf model populated with {@code materials} and {@code materialsByKind}
   * @return the {@code materials} view name
   */
  @GetMapping
  public String listMaterials(Model model) {
    try {
      PageResponse<MaterialPriceOverviewDto> page =
          backendApiClient.get(
              "/api/v1/materials/prices-overview?size=10000&sort=name,asc",
              MATERIAL_PRICE_OVERVIEW_PAGE_TYPE);

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
        materialsByKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(mat);
      }
      // Sort items within each kind alphabetically by name (already sorted from API, but just to be
      // sure)
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
   * Renders the matrix-overview shell ({@code GET /materials/overview}).
   *
   * <p>This endpoint deliberately renders no table body. It fetches the cached matrix only to
   * derive the distinct material-name and star-system lists that populate the two multi-select
   * filters, then returns the page shell. The grid itself is fetched separately as JSON from {@link
   * #getMatrixData} and drawn — and filtered — entirely client-side by the virtual-scroll script,
   * which is what keeps a large universe from freezing the browser by never building the whole
   * dense table in the DOM at once.
   *
   * @param model Thymeleaf model populated with the {@code materialNames} and {@code starSystems}
   *     filter source lists
   * @return the {@code materials-overview} view name
   */
  @GetMapping("/overview")
  public String getMatrixOverview(Model model) {
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
   * Returns the entire trade matrix as one lean {@link MatrixGridDto} JSON document ({@code GET
   * /materials/overview/data}), consumed by the client-side virtual-scroll grid.
   *
   * <p>The heavy 100 000-row backend fetch is served from {@link BackendApiClient#getCached}
   * (10-minute TTL) — the matrix is global price/terminal reference data, not user-scoped, so a
   * shared cache is safe and the fetch/deserialize runs at most once per TTL. The per-request work
   * is the {@link #buildGrid} reshaping into columns and category-grouped rows. The response is
   * always the full, unfiltered grid; the four filter dimensions are applied in the browser. The
   * trade-off is that overview prices can lag a UEX sync by up to the TTL; the per-material detail
   * page stays uncached for authoritative prices.
   *
   * @return the reshaped grid, or an empty grid if the backend fetch fails (the client then shows
   *     its no-results state instead of an error page)
   */
  @GetMapping("/overview/data")
  @ResponseBody
  public MatrixGridDto getMatrixData() {
    try {
      return buildGrid(fetchMatrixItems());
    } catch (Exception e) {
      log.error("Error loading materials matrix data", e);
      return new MatrixGridDto(List.of(), List.of(), List.of());
    }
  }

  /**
   * Fetches the full matrix projection from the (cached) backend endpoint, normalising a {@code
   * null} page or {@code null} content to an empty list so callers never see {@code null}.
   *
   * @return the matrix rows, never {@code null}
   */
  @NotNull
  private List<MaterialMatrixItemDto> fetchMatrixItems() {
    PageResponse<MaterialMatrixItemDto> page =
        backendApiClient.getCached(
            "/api/v1/materials/matrix?size=100000", MATERIAL_MATRIX_PAGE_TYPE);
    if (page == null || page.content() == null) {
      return new ArrayList<>();
    }
    return new ArrayList<>(page.content());
  }

  /**
   * Reshapes the flat material/terminal/price stream into the render-ready {@link MatrixGridDto}:
   * the deterministically ordered terminal columns (sorted via {@link TerminalCol#compareTo}), the
   * spanning star-system header counts, and the per-category material rows, each carrying a sparse
   * terminal-name → price-cell map. No filtering happens here — the browser filters the full grid.
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
      boolean[] flags = flagsByMaterial.computeIfAbsent(material, k -> new boolean[3]);
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
          .computeIfAbsent(material, k -> new HashMap<>())
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
          .computeIfAbsent(kindByMaterial.get(material), k -> new ArrayList<>())
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
   * Renders the per-material detail page ({@code /materials/{id}}) with the material's core record
   * and its full price list across terminals. Backend failure leaves the model attributes empty so
   * the template renders a "not available" placeholder rather than failing.
   *
   * @param id material id
   * @param model Thymeleaf model populated with {@code material} and {@code prices}
   * @return the {@code material-detail} view name
   */
  @GetMapping("/{id}")
  public String getMaterialDetail(@PathVariable @NotNull UUID id, Model model) {
    try {
      MaterialDto material = backendApiClient.get("/api/v1/materials/" + id, MaterialDto.class);
      model.addAttribute("material", material);

      PageResponse<MaterialPriceDto> pricesPage =
          backendApiClient.get(
              "/api/v1/materials/" + id + "/prices?size=1000&sort=terminal.name,asc",
              MATERIAL_PRICE_PAGE_TYPE);

      List<MaterialPriceDto> prices = new ArrayList<>();
      if (pricesPage != null && pricesPage.content() != null) {
        prices = new ArrayList<>(pricesPage.content());
      }
      model.addAttribute("prices", prices);

    } catch (Exception e) {
      log.error("Error loading material detail for id {}", id, e);
      model.addAttribute("error", "error.material.details.load");
      model.addAttribute("material", null);
      model.addAttribute("prices", new ArrayList<>());
    }
    return "material-detail";
  }
}
