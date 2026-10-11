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

package de.greluc.krt.profit.basetool.frontend.blueprint.client;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.DefaultBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintRecipeDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemDto;
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

/**
 * Typed backend client of the blueprint domain: the caller's owned blueprints with their recipe,
 * craftability and import, the org-unit availability overview, the synced blueprint catalogue, the
 * auto-granted default set and the admin management of a member's blueprints (REQ-INV-*), over
 * {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class BlueprintBackendClient {

  /** Backend path of the caller's own import preview. */
  private static final String PREVIEW_URI = "/api/v1/personal-blueprints/import/preview";

  /** Backend path of an admin import preview for a member. */
  private static final String ADMIN_PREVIEW_URI =
      "/api/v1/admin/personal-blueprints/{userSub}/import/preview";

  /** Backend admin API of the default-blueprint set. */
  private static final String DEFAULTS = "/api/v1/admin/default-blueprints";

  private static final ParameterizedTypeReference<List<BlueprintProductDto>> PRODUCT_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BlueprintCraftabilityDto>>
      CRAFTABILITY_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PersonalBlueprintDto>>
      PERSONAL_BLUEPRINT_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PersonalInventoryItemDto>>
      PERSONAL_INVENTORY_ITEM_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BlueprintOverviewEntryDto>>
      OVERVIEW_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BlueprintOverviewOwnerDto>> OWNER_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<BlueprintDto>> BLUEPRINT_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<DefaultBlueprintDto>> DEFAULT_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads a one-element page of the caller's personal-inventory items, whose total feeds the
   * „Items" tab count.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalInventoryItemDto> inventoryItemCountPage() {
    return backendApiClient.get("/api/v1/personal-inventory?size=1", PERSONAL_INVENTORY_ITEM_PAGE);
  }

  /**
   * Searches the blueprint products by name, each hit flagged when the caller owns it.
   *
   * @param q the case-insensitive name fragment, empty for the first products
   * @param limit the result cap
   * @return the products, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BlueprintProductDto> searchProducts(@NotNull String q, int limit) {
    return backendApiClient.get(
        "/api/v1/blueprints/products/search?q={q}&limit={limit}", PRODUCT_LIST, q, limit);
  }

  /**
   * Reads the ingredients and per-quality stat modifiers of an owned blueprint.
   *
   * @param id the owned-blueprint entry
   * @return the recipe, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintRecipeDto recipe(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/personal-blueprints/{id}/recipe", PersonalBlueprintRecipeDto.class, id);
  }

  /**
   * Reads the craftability of each of the caller's owned blueprints.
   *
   * @param includeRefinery whether the caller's open refinery yield is folded in
   * @return the per-blueprint craftability, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BlueprintCraftabilityDto> craftability(boolean includeRefinery) {
    return backendApiClient.get(
        "/api/v1/personal-blueprints/craftability?includeRefinery={includeRefinery}",
        CRAFTABILITY_LIST,
        includeRefinery);
  }

  /**
   * Adds products to the caller's owned blueprints.
   *
   * @param request the product keys
   * @return the added and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintBatchResultDto addOwned(
      @NotNull PersonalBlueprintBatchCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/personal-blueprints/batch", request, PersonalBlueprintBatchResultDto.class);
  }

  /**
   * Updates the note and acquisition time of one of the caller's owned blueprints.
   *
   * @param id the owned-blueprint entry
   * @param request the new values and the version they replace
   * @return the stored blueprint, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintDto updateOwned(
      @NotNull UUID id, @NotNull PersonalBlueprintUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/personal-blueprints/{id}", request, PersonalBlueprintDto.class, id);
  }

  /**
   * Removes one of the caller's owned blueprints.
   *
   * @param id the owned-blueprint entry
   */
  public void deleteOwned(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/personal-blueprints/{id}", Void.class, id);
  }

  /**
   * Removes the caller's removable owned blueprints, keeping the auto-granted defaults.
   *
   * @return the removed count, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintBulkDeleteResultDto deleteAllOwned() {
    return backendApiClient.delete(
        "/api/v1/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
  }

  /**
   * Reads one page of the caller's owned blueprints, by product name.
   *
   * @param q the case-insensitive product-name filter, or {@code null}/blank for none
   * @param size the page size
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalBlueprintDto> ownedPage(@Nullable String q, int size, int page) {
    String uri = "/api/v1/personal-blueprints?size={size}&page={page}&sort=productName,asc";
    if (q != null && !q.isBlank()) {
      return backendApiClient.get(uri + "&q={q}", PERSONAL_BLUEPRINT_PAGE, size, page, q);
    }
    return backendApiClient.get(uri, PERSONAL_BLUEPRINT_PAGE, size, page);
  }

  /**
   * Uploads a blueprint export for the caller's import preview; the backend persists nothing.
   *
   * @param filename the file name the part carries
   * @param bytes the export
   * @return the preview, or {@code null} when the backend sent no body
   */
  @Nullable
  public BlueprintImportPreviewDto importPreview(@NotNull String filename, byte @NotNull [] bytes) {
    MultipartBodyBuilder builder = exportPart(filename, bytes);
    return backendApiClient.execute(
        HttpMethod.POST,
        PREVIEW_URI,
        webClient ->
            webClient
                .post()
                .uri(PREVIEW_URI)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())),
        spec -> spec.bodyToMono(BlueprintImportPreviewDto.class));
  }

  /**
   * Applies the caller's reviewed import resolutions.
   *
   * @param request the resolutions
   * @return the summary, or {@code null} when the backend sent no body
   */
  @Nullable
  public BlueprintImportResultDto importApply(@NotNull BlueprintImportApplyRequest request) {
    return backendApiClient.post(
        "/api/v1/personal-blueprints/import/apply", request, BlueprintImportResultDto.class);
  }

  /**
   * Reads one page of the org-unit blueprint availability overview.
   *
   * @param page the zero-based page index
   * @param size the page size
   * @param search the product-name fragment, or {@code null} for none
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BlueprintOverviewEntryDto> overviewPage(
      int page, int size, @Nullable String search) {
    String uri = "/api/v1/personal-blueprints/overview?page={page}&size={size}";
    return search != null
        ? backendApiClient.get(uri + "&search={search}", OVERVIEW_PAGE, page, size, search)
        : backendApiClient.get(uri, OVERVIEW_PAGE, page, size);
  }

  /**
   * Reads the in-scope members owning one product.
   *
   * @param productKey the normalized product key
   * @return the owners, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BlueprintOverviewOwnerDto> overviewOwners(@NotNull String productKey) {
    return backendApiClient.get(
        "/api/v1/personal-blueprints/overview/owners?productKey={productKey}",
        OWNER_LIST,
        productKey);
  }

  /**
   * Reads one page of the synced blueprint catalogue, by output name.
   *
   * @param size the page size
   * @param page the zero-based page index
   * @param search the output-name or key filter, or {@code null} for none
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<BlueprintDto> blueprintPage(int size, int page, @Nullable String search) {
    return search != null
        ? backendApiClient.get(
            "/api/v1/blueprints?size={size}&page={page}&sort=outputName,asc&search={search}",
            BLUEPRINT_PAGE,
            size,
            page,
            search)
        : backendApiClient.get(
            "/api/v1/blueprints?size={size}&page={page}&sort=outputName,asc",
            BLUEPRINT_PAGE,
            size,
            page);
  }

  /**
   * Reads the auto-granted default-blueprint set.
   *
   * @return the set, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<DefaultBlueprintDto> defaults() {
    return backendApiClient.get(DEFAULTS, DEFAULT_LIST);
  }

  /**
   * Adds one product to the default-blueprint set.
   *
   * @param request the product key
   * @return the stored entry, or {@code null} when the backend sent no body
   */
  @Nullable
  public DefaultBlueprintDto addDefault(@NotNull DefaultBlueprintCreateRequest request) {
    return backendApiClient.post(DEFAULTS, request, DefaultBlueprintDto.class);
  }

  /**
   * Removes one product from the default-blueprint set.
   *
   * @param id the default-blueprint entry
   */
  public void removeDefault(@NotNull UUID id) {
    backendApiClient.delete(DEFAULTS + "/{id}", Void.class, id);
  }

  /**
   * Reads a member for the admin picker's seed option.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @return the member, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto user(@NotNull UUID userSub) {
    return backendApiClient.get("/api/v1/users/{userSub}", UserDto.class, userSub);
  }

  /**
   * Reads a member's owned blueprints for the admin page, by product name.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param size the page size
   * @param q the case-insensitive product-name filter, or {@code null}/blank for none
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalBlueprintDto> memberOwnedPage(
      @NotNull UUID userSub, int size, @Nullable String q) {
    if (q != null && !q.isBlank()) {
      return backendApiClient.get(
          "/api/v1/admin/personal-blueprints/{userSub}?size={size}&sort=productName,asc&q={q}",
          PERSONAL_BLUEPRINT_PAGE,
          userSub,
          size,
          q);
    }
    return backendApiClient.get(
        "/api/v1/admin/personal-blueprints/{userSub}?size={size}&sort=productName,asc",
        PERSONAL_BLUEPRINT_PAGE,
        userSub,
        size);
  }

  /**
   * Adds products to a member's owned blueprints.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param request the product keys
   * @return the added and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintBatchResultDto addMemberOwned(
      @NotNull UUID userSub, @NotNull PersonalBlueprintBatchCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/admin/personal-blueprints/{userSub}/batch",
        request,
        PersonalBlueprintBatchResultDto.class,
        userSub);
  }

  /**
   * Updates the note and acquisition time of any member's owned blueprint.
   *
   * @param id the owned-blueprint entry
   * @param request the new values and the version they replace
   * @return the stored blueprint, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintDto updateMemberOwned(
      @NotNull UUID id, @NotNull PersonalBlueprintUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/admin/personal-blueprints/items/{id}", request, PersonalBlueprintDto.class, id);
  }

  /**
   * Removes any member's owned blueprint.
   *
   * @param id the owned-blueprint entry
   */
  public void deleteMemberOwned(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/admin/personal-blueprints/items/{id}", Void.class, id);
  }

  /**
   * Uploads a blueprint export for a member's import preview; the backend persists nothing.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param filename the file name the part carries
   * @param bytes the export
   * @return the preview, or {@code null} when the backend sent no body
   */
  @Nullable
  public BlueprintImportPreviewDto memberImportPreview(
      @NotNull UUID userSub, @NotNull String filename, byte @NotNull [] bytes) {
    MultipartBodyBuilder builder = exportPart(filename, bytes);
    return backendApiClient.execute(
        HttpMethod.POST,
        ADMIN_PREVIEW_URI,
        webClient ->
            webClient
                .post()
                .uri(ADMIN_PREVIEW_URI, userSub)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())),
        spec -> spec.bodyToMono(BlueprintImportPreviewDto.class));
  }

  /**
   * Applies reviewed import resolutions for a member.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param request the resolutions
   * @return the summary, or {@code null} when the backend sent no body
   */
  @Nullable
  public BlueprintImportResultDto memberImportApply(
      @NotNull UUID userSub, @NotNull BlueprintImportApplyRequest request) {
    return backendApiClient.post(
        "/api/v1/admin/personal-blueprints/{userSub}/import/apply",
        request,
        BlueprintImportResultDto.class,
        userSub);
  }

  /**
   * Removes the removable owned blueprints of every member, keeping the auto-granted defaults.
   *
   * @return the removed count, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalBlueprintBulkDeleteResultDto deleteAllMembersOwned() {
    return backendApiClient.delete(
        "/api/v1/admin/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
  }

  /**
   * Builds the multipart body of a blueprint export upload: one octet-stream {@code file} part.
   *
   * @param filename the file name the part carries
   * @param bytes the export
   * @return the builder holding the part
   */
  @NotNull
  private static MultipartBodyBuilder exportPart(@NotNull String filename, byte @NotNull [] bytes) {
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
    return builder;
  }
}
