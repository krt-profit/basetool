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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.CityDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialExternalAliasDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialSellingTerminalDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.OutpostDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.PoiDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SpaceStationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the catalogue domain: materials and their prices, matrix, categories and
 * external aliases, locations, the UEX-mirrored universe, ship data, the mission reference data,
 * quality tiers, sync reports and the P4K import, over {@link BackendApiClient} (plan §5.9,
 * ADR-0032). Evicting the catalogue caches after a write stays with the caller.
 */
@Service
@RequiredArgsConstructor
public class CatalogueBackendClient {

  /** The material-alias collection. */
  private static final String MATERIAL_ALIASES = "/api/v1/material-external-aliases";

  /** The admin quality-tier collection. */
  private static final String QUALITY_TIERS = "/api/v1/admin/quality-tiers";

  /** The P4K import-job collection. */
  private static final String JOBS_URI = "/api/v1/admin/import/p4k/jobs";

  /** The page size of the sync-report listing. */
  private static final int SYNC_REPORT_PAGE_SIZE = 100;

  private static final ParameterizedTypeReference<PageResponse<MaterialPriceOverviewDto>>
      MATERIAL_PRICE_OVERVIEW_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialMatrixItemDto>>
      MATERIAL_MATRIX_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialPriceDto>>
      MATERIAL_PRICE_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialSellingTerminalDto>>
      SELLING_TERMINAL_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<ProfitCalculationDto>>
      PROFIT_CALCULATION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<LocationReferenceDto>>
      LOCATION_REFERENCE_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialCategoryDto>>
      MATERIAL_CATEGORY_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialExternalAliasDto>> ALIAS_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<CityDto>> CITY_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<SpaceStationDto>>
      SPACE_STATION_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<OutpostDto>> OUTPOST_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PoiDto>> POI_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<TerminalDto>> TERMINAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<SyncReportDto>> SYNC_REPORT_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<JobTypeDto>> JOB_TYPE_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> SQUADRON_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<FrequencyTypeDto>>
      FREQUENCY_TYPE_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<ManufacturerDto>> MANUFACTURER_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<QualityTierDto>> QUALITY_TIER_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /** The UEX-mirrored entity kinds that carry a loading-dock override. */
  public enum UexEntity {
    /** {@code /api/v1/cities}. */
    CITIES,
    /** {@code /api/v1/space-stations}. */
    SPACE_STATIONS,
    /** {@code /api/v1/outposts}. */
    OUTPOSTS,
    /** {@code /api/v1/pois}. */
    POIS,
    /** {@code /api/v1/terminals}. */
    TERMINALS
  }

  /**
   * The four filters of a materials-matrix slice (REQ-UI-014).
   *
   * @param materials material names to keep, or {@code null}/empty for all
   * @param systems star-system names to keep, or {@code null}/empty for all
   * @param loadingDock {@code true} to keep only terminals with a loading dock
   * @param autoLoad {@code true} to keep only terminals with automatic cargo loading
   */
  public record MatrixFilter(
      @Nullable List<String> materials,
      @Nullable List<String> systems,
      boolean loadingDock,
      boolean autoLoad) {}

  /**
   * Reads the materials price overview behind the {@code /materials} accordion.
   *
   * @return the overview page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialPriceOverviewDto> materialPriceOverview() {
    return backendApiClient.get(
        "/api/v1/materials/prices-overview?size=10000&sort=name,asc", MATERIAL_PRICE_OVERVIEW_PAGE);
  }

  /**
   * Reads the complete unfiltered trade matrix from the shared catalogue cache.
   *
   * @return the matrix, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialMatrixItemDto> materialsMatrix() {
    return backendApiClient.getCached(CachedCatalog.MATERIALS_MATRIX, MATERIAL_MATRIX_PAGE);
  }

  /**
   * Reads one uncached page of a filtered trade-matrix slice, each material and star system passed
   * as its own template variable (REQ-UI-014, REQ-SEC-051).
   *
   * @param filter the slice's filters
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialMatrixItemDto> filteredMatrixPage(
      @NotNull MatrixFilter filter, int page) {
    List<Object> uriVariables = new ArrayList<>();
    String template =
        filteredMatrixTemplate(
                filter.materials(),
                filter.systems(),
                filter.loadingDock(),
                filter.autoLoad(),
                uriVariables)
            + "&page={page}";
    uriVariables.add(page);
    return backendApiClient.get(template, MATERIAL_MATRIX_PAGE, uriVariables.toArray());
  }

  /**
   * Reads one material.
   *
   * @param id the material
   * @return the material, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialDto material(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/materials/{id}", MaterialDto.class, id);
  }

  /**
   * Reads one page of a material's per-terminal prices, by terminal name.
   *
   * @param id the material
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialPriceDto> materialPricePage(@NotNull UUID id, int page) {
    return backendApiClient.get(
        "/api/v1/materials/{id}/prices?size=10000&sort=terminal.name,asc" + "&page={page}",
        MATERIAL_PRICE_PAGE,
        id,
        page);
  }

  /**
   * Reads the complete cached terminal catalogue, mined for star systems and the last UEX sweep.
   *
   * @return the catalogue, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<TerminalDto> terminalCatalogue() {
    return backendApiClient.getCached(CachedCatalog.TERMINALS, TERMINAL_PAGE);
  }

  /**
   * Reads the cached ship-type catalogue sorted by name, for the profit-calculation ship picker.
   *
   * @return the catalogue, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipTypeDto> shipTypesSorted() {
    return backendApiClient.getCached(CachedCatalog.SHIP_TYPES_SORTED, SHIP_TYPE_PAGE);
  }

  /**
   * Lists the terminals that buy a material, with their sale price.
   *
   * @param id the material
   * @return the terminals, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialSellingTerminalDto> materialSellingTerminals(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/materials/{id}/terminals", SELLING_TERMINAL_LIST, id);
  }

  /**
   * Computes the profit rows for a ship, each star-system name passed as its own {@code
   * starSystemNames} template variable (REQ-SEC-051).
   *
   * @param shipId the ship type whose capacity is used
   * @param starSystemNames the star systems to keep, or {@code null} for all
   * @return the rows, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ProfitCalculationDto> profitCalculation(
      @NotNull UUID shipId, @Nullable List<String> starSystemNames) {
    StringBuilder uriTemplate =
        new StringBuilder("/api/v1/materials/profit-calculation?shipId={shipId}");
    List<Object> uriVariables = new ArrayList<>();
    uriVariables.add(shipId);
    if (starSystemNames != null) {
      for (String starSystemName : starSystemNames) {
        uriTemplate.append("&starSystemNames={starSystemName}");
        uriVariables.add(starSystemName);
      }
    }
    return backendApiClient.get(
        uriTemplate.toString(), PROFIT_CALCULATION_LIST, uriVariables.toArray());
  }

  /**
   * Searches the visible material catalogue for the picker, by name (REQ-FE-016).
   *
   * @param q the name fragment, empty for the first page
   * @param jobOrder whether to keep only job-order materials
   * @param raw whether to keep only refinery inputs
   * @param size the number of rows to fetch
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialDto> searchMaterials(
      @NotNull String q, boolean jobOrder, boolean raw, int size) {
    return backendApiClient.get(
        "/api/v1/materials/search?search={q}&jobOrderOnly={jobOrder}&rawOnly={raw}"
            + "&size={size}&sort=name,asc",
        MATERIAL_PAGE,
        q,
        jobOrder,
        raw,
        size);
  }

  /**
   * Searches the non-hidden location catalogue for the picker, by name (REQ-FE-016).
   *
   * @param q the name fragment, empty for the first page
   * @param size the number of rows to fetch
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<LocationReferenceDto> searchLocations(@NotNull String q, int size) {
    return backendApiClient.get(
        "/api/v1/locations/search?search={q}&size={size}&sort=name,asc",
        LOCATION_REFERENCE_PAGE,
        q,
        size);
  }

  /**
   * Reads one page of the admin material catalogue, hidden materials included.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MaterialDto> materialPage(int page) {
    return backendApiClient.get(
        "/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}",
        MATERIAL_PAGE,
        page);
  }

  /**
   * Creates a material manually; the backend marks it as a manual entry.
   *
   * @param request the create payload
   * @return the persisted material, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialDto createMaterial(@NotNull MaterialCreateAjaxRequest request) {
    return backendApiClient.post("/api/v1/materials", request, MaterialDto.class);
  }

  /**
   * Replaces a material, carrying its optimistic-lock version in the body.
   *
   * @param id the material
   * @param body the full material
   */
  public void updateMaterial(@NotNull UUID id, @NotNull MaterialDto body) {
    backendApiClient.put("/api/v1/materials/{id}", body, Void.class, id);
  }

  /**
   * Lists every material category.
   *
   * @return the categories, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialCategoryDto> materialCategories() {
    return backendApiClient.get("/api/v1/material-categories", MATERIAL_CATEGORY_LIST);
  }

  /**
   * Reads one material category.
   *
   * @param id the category
   * @return the category, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialCategoryDto materialCategory(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/material-categories/{id}", MaterialCategoryDto.class, id);
  }

  /**
   * Creates a material category.
   *
   * @param category the category, its name set
   * @return the created category, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialCategoryDto createMaterialCategory(@NotNull MaterialCategoryDto category) {
    return backendApiClient.post(
        "/api/v1/material-categories", category, MaterialCategoryDto.class);
  }

  /**
   * Deletes a material category; the backend refuses with {@code 409} while a material uses it.
   *
   * @param id the category
   */
  public void deleteMaterialCategory(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/material-categories/{id}", Void.class, id);
  }

  /**
   * Lists every material external alias.
   *
   * @return the aliases, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialExternalAliasDto> materialAliases() {
    return backendApiClient.get(MATERIAL_ALIASES, ALIAS_LIST);
  }

  /**
   * Reads one material external alias.
   *
   * @param id the alias
   * @return the alias, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExternalAliasDto materialAlias(@NotNull UUID id) {
    return backendApiClient.get(MATERIAL_ALIASES + "/{id}", MaterialExternalAliasDto.class, id);
  }

  /**
   * Creates a material external alias.
   *
   * @param body the alias to create
   * @return the created alias, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExternalAliasDto createMaterialAlias(
      @NotNull MaterialExternalAliasWriteRequest body) {
    return backendApiClient.post(MATERIAL_ALIASES, body, MaterialExternalAliasDto.class);
  }

  /**
   * Updates a material external alias, carrying its optimistic-lock version in the body.
   *
   * @param id the alias
   * @param body the new values
   * @return the updated alias, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialExternalAliasDto updateMaterialAlias(
      @NotNull UUID id, @NotNull MaterialExternalAliasWriteRequest body) {
    return backendApiClient.put(
        MATERIAL_ALIASES + "/{id}", body, MaterialExternalAliasDto.class, id);
  }

  /**
   * Deletes a material external alias.
   *
   * @param id the alias
   */
  public void deleteMaterialAlias(@NotNull UUID id) {
    backendApiClient.delete(MATERIAL_ALIASES + "/{id}", Void.class, id);
  }

  /**
   * Reads one page of the admin location catalogue, hidden locations included.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<LocationDto> locationPage(int page) {
    return backendApiClient.get(
        "/api/v1/locations?size=1000&sort=name,asc&includeHidden=true&page={page}",
        LOCATION_PAGE,
        page);
  }

  /**
   * Reads one location.
   *
   * @param id the location
   * @return the location, or {@code null} when the backend sent no body
   */
  @Nullable
  public LocationDto location(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/locations/{id}", LocationDto.class, id);
  }

  /**
   * Replaces a location, carrying its optimistic-lock version in the body.
   *
   * @param id the location
   * @param body the full location
   */
  public void updateLocation(@NotNull UUID id, @NotNull LocationDto body) {
    backendApiClient.put("/api/v1/locations/{id}", body, Void.class, id);
  }

  /**
   * Reads one page of the UEX-mirrored cities.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<CityDto> cityPage(int page) {
    return backendApiClient.get(
        "/api/v1/cities?size=10000&sort=name,asc&page={page}", CITY_PAGE, page);
  }

  /**
   * Reads one page of the UEX-mirrored space stations.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SpaceStationDto> spaceStationPage(int page) {
    return backendApiClient.get(
        "/api/v1/space-stations?size=10000&sort=name,asc&page={page}", SPACE_STATION_PAGE, page);
  }

  /**
   * Reads one page of the UEX-mirrored outposts.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<OutpostDto> outpostPage(int page) {
    return backendApiClient.get(
        "/api/v1/outposts?size=10000&sort=name,asc&page={page}", OUTPOST_PAGE, page);
  }

  /**
   * Reads one page of the UEX-mirrored points of interest.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PoiDto> poiPage(int page) {
    return backendApiClient.get(
        "/api/v1/pois?size=10000&sort=name,asc&page={page}", POI_PAGE, page);
  }

  /**
   * Reads one page of the UEX-mirrored terminals.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<TerminalDto> terminalPage(int page) {
    return backendApiClient.get(
        "/api/v1/terminals?size=10000&sort=name,asc&page={page}", TERMINAL_PAGE, page);
  }

  /**
   * Reads one terminal.
   *
   * @param id the terminal
   * @return the terminal, or {@code null} when the backend sent no body
   */
  @Nullable
  public TerminalDto terminal(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/terminals/{id}", TerminalDto.class, id);
  }

  /**
   * Replaces a terminal.
   *
   * @param id the terminal
   * @param body the full terminal
   */
  public void updateTerminal(@NotNull UUID id, @NotNull TerminalDto body) {
    backendApiClient.put("/api/v1/terminals/{id}", body, Void.class, id);
  }

  /**
   * Pins the loading-dock flag of a UEX-mirrored entity to a value.
   *
   * @param entity the entity kind
   * @param id the entity
   * @param value the pinned flag
   */
  public void pinLoadingDock(@NotNull UexEntity entity, @NotNull UUID id, boolean value) {
    backendApiClient.patch(
        value
            ? entityPath(entity) + "/loading-dock?value=true"
            : entityPath(entity) + "/loading-dock?value=false",
        null,
        Void.class,
        id);
  }

  /**
   * Drops the loading-dock pin of a UEX-mirrored entity, so the UEX value applies again.
   *
   * @param entity the entity kind
   * @param id the entity
   */
  public void clearLoadingDockPin(@NotNull UexEntity entity, @NotNull UUID id) {
    backendApiClient.delete(entityPath(entity) + "/loading-dock-override", Void.class, id);
  }

  /**
   * Pins the auto-load flag of a terminal to a value.
   *
   * @param id the terminal
   * @param value the pinned flag
   */
  public void pinTerminalAutoLoad(@NotNull UUID id, boolean value) {
    backendApiClient.patch(
        value
            ? "/api/v1/terminals/{id}/auto-load?value=true"
            : "/api/v1/terminals/{id}/auto-load?value=false",
        null,
        Void.class,
        id);
  }

  /**
   * Drops the auto-load pin of a terminal, so the UEX value applies again.
   *
   * @param id the terminal
   */
  public void clearTerminalAutoLoadPin(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/terminals/{id}/auto-load-override", Void.class, id);
  }

  /**
   * Uploads a P4K catalogue as a multipart {@code file} part and enqueues its preview job.
   *
   * @param bytes the catalogue JSON
   * @param filename the part's file name
   * @return the enqueued job, or {@code null} when the backend sent no body
   */
  @Nullable
  public P4kImportJobDto enqueueP4kImport(byte @NotNull [] bytes, @NotNull String filename) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder
        .part(
            "file",
            new ByteArrayResource(bytes) {
              @Override
              public String getFilename() {
                return filename;
              }
            })
        .contentType(MediaType.APPLICATION_OCTET_STREAM);
    return backendApiClient.execute(
        HttpMethod.POST,
        JOBS_URI,
        webClient ->
            webClient
                .post()
                .uri(JOBS_URI)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())),
        spec -> spec.bodyToMono(P4kImportJobDto.class));
  }

  /**
   * Lists the recent P4K import jobs, newest first.
   *
   * @return the jobs, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<P4kImportJobDto> p4kImportJobs() {
    return backendApiClient.execute(
        HttpMethod.GET,
        JOBS_URI,
        webClient -> webClient.get().uri(JOBS_URI),
        spec -> spec.bodyToFlux(P4kImportJobDto.class).collectList());
  }

  /**
   * Reads one P4K import job.
   *
   * @param id the job
   * @return the job, or {@code null} when the backend sent no body
   */
  @Nullable
  public P4kImportJobDto p4kImportJob(@NotNull UUID id) {
    return backendApiClient.execute(
        HttpMethod.GET,
        JOBS_URI + "/{id}",
        webClient ->
            webClient.get().uri(uriBuilder -> uriBuilder.path(JOBS_URI + "/{id}").build(id)),
        spec -> spec.bodyToMono(P4kImportJobDto.class));
  }

  /**
   * Enqueues the apply job of a finished P4K preview.
   *
   * @param id the preview job
   * @param seedNew whether to insert game rows that match no existing row
   * @return the enqueued apply job, or {@code null} when the backend sent no body
   */
  @Nullable
  public P4kImportJobDto applyP4kImportJob(@NotNull UUID id, boolean seedNew) {
    return backendApiClient.execute(
        HttpMethod.POST,
        JOBS_URI + "/{id}/apply",
        webClient ->
            webClient
                .post()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path(JOBS_URI + "/{id}/apply")
                            .queryParam("seedNew", seedNew)
                            .build(id)),
        spec -> spec.bodyToMono(P4kImportJobDto.class));
  }

  /**
   * Reads one page of sync-report events.
   *
   * @param page zero-based page index, never negative
   * @param source {@code SCWIKI} or {@code UEX}, or {@code null} for both catalogues
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SyncReportDto> syncReportPage(int page, @Nullable String source) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/sync-reports")
            .queryParam("page", page)
            .queryParam("size", SYNC_REPORT_PAGE_SIZE);
    if (source != null) {
      uriBuilder.queryParam("source", source);
    }
    String uri = uriBuilder.toUriString();
    return backendApiClient.get(uri, SYNC_REPORT_PAGE);
  }

  /**
   * Deletes the sync-report events older than a number of days.
   *
   * @param source {@code SCWIKI} or {@code UEX}, or {@code null} for both catalogues
   * @param days the minimum age in days of a deleted event
   * @return the deleted count, or {@code null} when the backend sent no body
   */
  @Nullable
  public SyncReportPurgeResultDto purgeSyncReports(@Nullable String source, int days) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/sync-reports").queryParam("olderThanDays", days);
    if (source != null) {
      uri.queryParam("source", source);
    }
    return backendApiClient.delete(uri.toUriString(), SyncReportPurgeResultDto.class);
  }

  /**
   * Reads one page of the job-type catalogue by name.
   *
   * @param includeInactive whether to include soft-deleted job types
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<JobTypeDto> jobTypePage(boolean includeInactive, int page) {
    return backendApiClient.get(
        "/api/v1/job-types?size=1000&sort=name,asc"
            + "&includeInactive={includeInactive}&page={page}",
        JOB_TYPE_PAGE,
        includeInactive,
        page);
  }

  /**
   * Creates a job type.
   *
   * @param body the job type
   */
  public void createJobType(@NotNull JobTypeDto body) {
    backendApiClient.post("/api/v1/job-types", body, Void.class);
  }

  /**
   * Updates a job type, carrying its optimistic-lock version in the body.
   *
   * @param id the job type
   * @param body the new values
   */
  public void updateJobType(@NotNull UUID id, @NotNull JobTypeDto body) {
    backendApiClient.put("/api/v1/job-types/{id}", body, Void.class, id);
  }

  /**
   * Soft-deletes a job type; the backend refuses with {@code 409} while a mission uses it.
   *
   * @param id the job type
   */
  public void deleteJobType(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/job-types/{id}", Void.class, id);
  }

  /**
   * Re-activates a soft-deleted job type.
   *
   * @param id the job type
   */
  public void activateJobType(@NotNull UUID id) {
    backendApiClient.post("/api/v1/job-types/{id}/activate", null, Void.class, id);
  }

  /**
   * Reads one page of the squadron catalogue by name.
   *
   * @param includeInactive whether to include soft-deleted squadrons
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SquadronDto> squadronPage(boolean includeInactive, int page) {
    return backendApiClient.get(
        "/api/v1/squadrons?size=1000&sort=name,asc"
            + "&includeInactive={includeInactive}&page={page}",
        SQUADRON_PAGE,
        includeInactive,
        page);
  }

  /**
   * Creates a squadron.
   *
   * @param body the squadron
   */
  public void createSquadron(@NotNull SquadronDto body) {
    backendApiClient.post("/api/v1/squadrons", body, Void.class);
  }

  /**
   * Updates a squadron, carrying its optimistic-lock version in the body.
   *
   * @param id the squadron
   * @param body the new values
   */
  public void updateSquadron(@NotNull UUID id, @NotNull SquadronDto body) {
    backendApiClient.put("/api/v1/squadrons/{id}", body, Void.class, id);
  }

  /**
   * Soft-deletes a squadron; the backend refuses with {@code 409} while it is in use.
   *
   * @param id the squadron
   */
  public void deleteSquadron(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/squadrons/{id}", Void.class, id);
  }

  /**
   * Re-activates a soft-deleted squadron.
   *
   * @param id the squadron
   */
  public void activateSquadron(@NotNull UUID id) {
    backendApiClient.post("/api/v1/squadrons/{id}/activate", null, Void.class, id);
  }

  /**
   * Reads one page of the frequency-type catalogue in its admin order.
   *
   * @param includeInactive whether to include soft-deleted frequency types
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<FrequencyTypeDto> frequencyTypePage(boolean includeInactive, int page) {
    return backendApiClient.get(
        "/api/v1/frequency-types?size=1000&sort=sortIndex,asc"
            + (includeInactive ? "" : "&active=true")
            + "&page={page}",
        FREQUENCY_TYPE_PAGE,
        page);
  }

  /**
   * Creates a frequency type; the backend appends it to the order.
   *
   * @param body the frequency type
   */
  public void createFrequencyType(@NotNull FrequencyTypeDto body) {
    backendApiClient.post("/api/v1/frequency-types", body, Void.class);
  }

  /**
   * Updates a frequency type, carrying its optimistic-lock version in the body.
   *
   * @param id the frequency type
   * @param body the new values
   */
  public void updateFrequencyType(@NotNull UUID id, @NotNull FrequencyTypeDto body) {
    backendApiClient.put("/api/v1/frequency-types/{id}", body, Void.class, id);
  }

  /**
   * Soft-deletes a frequency type; the backend refuses with {@code 409} while a mission uses it.
   *
   * @param id the frequency type
   */
  public void deleteFrequencyType(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/frequency-types/{id}", Void.class, id);
  }

  /**
   * Re-activates a soft-deleted frequency type.
   *
   * @param id the frequency type
   */
  public void activateFrequencyType(@NotNull UUID id) {
    backendApiClient.post("/api/v1/frequency-types/{id}/activate", null, Void.class, id);
  }

  /**
   * Stores a new order of the frequency types.
   *
   * @param ids the frequency types in their new order
   */
  public void reorderFrequencyTypes(@NotNull List<UUID> ids) {
    backendApiClient.post("/api/v1/frequency-types/reorder", ids, Void.class);
  }

  /**
   * Reads one page of the manufacturer catalogue, hidden manufacturers included.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ManufacturerDto> manufacturerPage(int page) {
    return backendApiClient.get(
        "/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true" + "&page={page}",
        MANUFACTURER_PAGE,
        page);
  }

  /**
   * Reads one page of the ship-type catalogue, hidden ship types included.
   *
   * @param page zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<ShipTypeDto> shipTypePage(int page) {
    return backendApiClient.get(
        "/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true&page={page}",
        SHIP_TYPE_PAGE,
        page);
  }

  /**
   * Sets a manufacturer's hidden flag.
   *
   * @param id the manufacturer
   * @param hidden the new flag
   */
  public void setManufacturerHidden(@NotNull UUID id, boolean hidden) {
    backendApiClient.put(
        "/api/v1/manufacturers/{id}/visibility?hidden={hidden}", null, Void.class, id, hidden);
  }

  /**
   * Sets a ship type's hidden flag.
   *
   * @param id the ship type
   * @param hidden the new flag
   */
  public void setShipTypeHidden(@NotNull UUID id, boolean hidden) {
    backendApiClient.put(
        "/api/v1/ship-types/{id}/visibility?hidden={hidden}", null, Void.class, id, hidden);
  }

  /** Clears the {@code fitted} flag of every ship in the hangar. */
  public void resetAllFitted() {
    backendApiClient.post("/api/v1/hangar/ships/reset-fitted", null, Void.class);
  }

  /**
   * Lists every quality tier, inactive tiers included (REQ-ORDERS-036).
   *
   * @return the tiers, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<QualityTierDto> qualityTiers() {
    return backendApiClient.get(QUALITY_TIERS, QUALITY_TIER_LIST);
  }

  /**
   * Creates a quality tier.
   *
   * @param body the tier
   * @return the created tier, or {@code null} when the backend sent no body
   */
  @Nullable
  public QualityTierDto createQualityTier(@NotNull QualityTierWriteDto body) {
    return backendApiClient.post(QUALITY_TIERS, body, QualityTierDto.class);
  }

  /**
   * Updates a quality tier, carrying its optimistic-lock version in the body.
   *
   * @param id the tier
   * @param body the new values
   * @return the updated tier, or {@code null} when the backend sent no body
   */
  @Nullable
  public QualityTierDto updateQualityTier(@NotNull UUID id, @NotNull QualityTierWriteDto body) {
    return backendApiClient.put(QUALITY_TIERS + "/{id}", body, QualityTierDto.class, id);
  }

  /**
   * Deletes an unused quality tier.
   *
   * @param id the tier
   */
  public void deleteQualityTier(@NotNull UUID id) {
    backendApiClient.delete(QUALITY_TIERS + "/{id}", Void.class, id);
  }

  /**
   * Names the backend collection of a UEX-mirrored entity kind, up to its {@code {id}} variable.
   *
   * @param entity the entity kind
   * @return the item path template
   */
  @NotNull
  private static String entityPath(@NotNull UexEntity entity) {
    return switch (entity) {
      case CITIES -> "/api/v1/cities/{id}";
      case SPACE_STATIONS -> "/api/v1/space-stations/{id}";
      case OUTPOSTS -> "/api/v1/outposts/{id}";
      case POIS -> "/api/v1/pois/{id}";
      case TERMINALS -> "/api/v1/terminals/{id}";
    };
  }

  /**
   * Builds the matrix URI template of a filtered slice, with one placeholder per material or star
   * system, appending the values to {@code uriVariables} in placeholder order.
   *
   * @param materials material names to keep, or {@code null}/empty for all
   * @param systems star-system names to keep, or {@code null}/empty for all
   * @param loadingDock {@code true} to keep only terminals with a loading dock
   * @param autoLoad {@code true} to keep only terminals with automatic cargo loading
   * @param uriVariables the sink the placeholder values are appended to
   * @return the URI template carrying the filters
   */
  @NotNull
  private static String filteredMatrixTemplate(
      @Nullable List<String> materials,
      @Nullable List<String> systems,
      boolean loadingDock,
      boolean autoLoad,
      @NotNull List<Object> uriVariables) {
    StringBuilder uri = new StringBuilder(CachedCatalog.MATERIALS_MATRIX.getUri());
    if (materials != null && !materials.isEmpty()) {
      for (String material : materials) {
        uri.append("&materialNames={materialName}");
        uriVariables.add(material);
      }
    }
    if (systems != null && !systems.isEmpty()) {
      for (String system : systems) {
        uri.append("&starSystems={starSystem}");
        uriVariables.add(system);
      }
    }
    if (loadingDock) {
      uri.append("&hasLoadingDock=true");
    }
    if (autoLoad) {
      uri.append("&isAutoLoad=true");
    }
    return uri.toString();
  }
}
