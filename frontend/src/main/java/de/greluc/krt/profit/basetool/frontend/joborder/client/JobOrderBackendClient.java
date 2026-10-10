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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.AssigneeNoteRequest;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ClaimDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateClaimDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.LinkedStockAttributionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialDemandOverviewDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the job-order domain: the order list and detail, item and material
 * orders, claims, assignees, handovers and their reports, production, the collection pages and the
 * cross-order material demand, plus the catalogues and settings those pages read, over {@link
 * BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class JobOrderBackendClient {

  /** The backend resource of one job order. */
  private static final String ORDER = "/api/v1/orders/{id}";

  /** The caller's own profile. */
  private static final String CURRENT_USER = "/api/v1/users/me";

  private static final ParameterizedTypeReference<PageResponse<JobOrderDto>> PAGE_OF_JOB_ORDER =
      new ParameterizedTypeReference<PageResponse<JobOrderDto>>() {};

  private static final ParameterizedTypeReference<List<InventoryItemDto>> LIST_OF_INVENTORY_ITEM =
      new ParameterizedTypeReference<List<InventoryItemDto>>() {};

  private static final ParameterizedTypeReference<List<JobOrderItemStockGroupDto>>
      LIST_OF_ITEM_STOCK_GROUP = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<BlueprintReferenceDto>>
      LIST_OF_BLUEPRINT_REFERENCE =
          new ParameterizedTypeReference<List<BlueprintReferenceDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<GameItemReferenceDto>>
      PAGE_OF_GAME_ITEM_REFERENCE =
          new ParameterizedTypeReference<PageResponse<GameItemReferenceDto>>() {};

  private static final ParameterizedTypeReference<List<MaterialDto>> LIST_OF_MATERIAL =
      new ParameterizedTypeReference<List<MaterialDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<SquadronDto>> PAGE_OF_SQUADRON =
      new ParameterizedTypeReference<PageResponse<SquadronDto>>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION =
          new ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>() {};

  private static final ParameterizedTypeReference<List<LocationReferenceDto>>
      LIST_OF_LOCATION_REFERENCE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialCollectionEntryDto>>
      LIST_OF_MATERIAL_COLLECTION_ENTRY = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<LinkedStockAttributionDto>>
      LIST_OF_STOCK_ATTRIBUTION = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of the order list, sorted by priority.
   *
   * @param ownScope {@code true} to read the caller's own requested orders, which ignore the
   *     squadron filter
   * @param toProcess {@code true} to read the queue of the caller's own units, which ignores the
   *     squadron filter
   * @param page the zero-based page index
   * @param size the page size
   * @param statuses the status filter, never empty
   * @param squadronIds the squadron filter, empty for all squadrons
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<JobOrderDto> orders(
      boolean ownScope,
      boolean toProcess,
      int page,
      int size,
      @NotNull List<String> statuses,
      @NotNull List<UUID> squadronIds) {
    StringBuilder uri =
        new StringBuilder(ownScope ? "/api/v1/orders/requested" : "/api/v1/orders")
            .append("?page={page}&size={size}&sort=priority,asc&status=");
    List<Object> uriVariables = new ArrayList<>();
    uriVariables.add(page);
    uriVariables.add(size);
    for (int i = 0; i < statuses.size(); i++) {
      if (i > 0) {
        uri.append(",");
      }
      uri.append("{status}");
      uriVariables.add(statuses.get(i));
    }
    if (toProcess) {
      uri.append("&toProcess=true");
    } else if (!ownScope) {
      for (UUID sid : squadronIds) {
        uri.append("&squadronId={squadronId}");
        uriVariables.add(sid);
      }
    }
    return backendApiClient.get(uri.toString(), PAGE_OF_JOB_ORDER, uriVariables.toArray());
  }

  /**
   * Reads the cached yellow ageing threshold of a job order.
   *
   * @return the setting, or {@code null} when the backend sent no body
   */
  @Nullable
  public SystemSettingDto ageYellowSetting() {
    return backendApiClient.getCached(
        CachedCatalog.SETTING_JOB_ORDER_AGE_YELLOW, SystemSettingDto.class);
  }

  /**
   * Reads the cached red ageing threshold of a job order.
   *
   * @return the setting, or {@code null} when the backend sent no body
   */
  @Nullable
  public SystemSettingDto ageRedSetting() {
    return backendApiClient.getCached(
        CachedCatalog.SETTING_JOB_ORDER_AGE_RED, SystemSettingDto.class);
  }

  /**
   * Reads one job order, redacted by the backend for a requester-only caller.
   *
   * @param id the order
   * @return the order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto order(@NotNull UUID id) {
    return backendApiClient.get(ORDER, JobOrderDto.class, id);
  }

  /**
   * Reads the blueprint coverage of an item order's lines.
   *
   * @param id the item order
   * @return the coverage, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderItemBlueprintOwnersDto itemBlueprintOwners(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/item-blueprint-owners", JobOrderItemBlueprintOwnersDto.class, id);
  }

  /**
   * Reads the game-item stock earmarked to an item order, grouped per game item (REQ-ORDERS-028).
   *
   * @param id the item order
   * @return the groups, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<JobOrderItemStockGroupDto> itemStock(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/orders/{id}/item-stock", LIST_OF_ITEM_STOCK_GROUP, id);
  }

  /**
   * Reads the inventory items still linked to an order material that no longer exists.
   *
   * @param id the order
   * @return the items, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<InventoryItemDto> orphanedInventory(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/inventory/orphaned", LIST_OF_INVENTORY_ITEM, id);
  }

  /**
   * Reads the blueprints that produce an orderable item.
   *
   * @param gameItemId the orderable item
   * @return the blueprints, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<BlueprintReferenceDto> itemBlueprints(@NotNull UUID gameItemId) {
    return backendApiClient.get(
        "/api/v1/orders/item-catalog/{gameItemId}/blueprints",
        LIST_OF_BLUEPRINT_REFERENCE,
        gameItemId);
  }

  /**
   * Derives the materials a blueprint needs for a whole-unit amount.
   *
   * @param blueprintId the blueprint
   * @param amount the amount to scale by
   * @return the derivation, or {@code null} when the backend sent no body
   */
  @Nullable
  public ItemDerivationDto itemDerivation(@NotNull UUID blueprintId, int amount) {
    return backendApiClient.get(
        "/api/v1/orders/item-catalog/blueprints/{blueprintId}/derivation?amount={amount}",
        ItemDerivationDto.class,
        blueprintId,
        amount);
  }

  /**
   * Searches the orderable items by name, sorted by name.
   *
   * @param search the case-insensitive search term, empty for the first page
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<GameItemReferenceDto> searchOrderableItems(@NotNull String search, int size) {
    return backendApiClient.get(
        "/api/v1/orders/item-catalog?search={q}&size={size}&sort=name,asc",
        PAGE_OF_GAME_ITEM_REFERENCE,
        search,
        size);
  }

  /**
   * Reads the cached single-row probe of the orderable-item catalogue.
   *
   * @return the probe page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<GameItemReferenceDto> orderableItemProbe() {
    return backendApiClient.getCached(CachedCatalog.ITEM_CATALOG, PAGE_OF_GAME_ITEM_REFERENCE);
  }

  /**
   * Reads the inventory items that may be linked to one material of an order.
   *
   * @param id the order
   * @param matId the material
   * @return the items, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<InventoryItemDto> inventoryForMaterial(@NotNull UUID id, @NotNull UUID matId) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/materials/{matId}/inventory", LIST_OF_INVENTORY_ITEM, id, matId);
  }

  /**
   * Reads the per-row quality-bucket attribution of one order material (REQ-ORDERS-037).
   *
   * @param id the order
   * @param matId the material
   * @return the attribution, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LinkedStockAttributionDto> stockAttribution(@NotNull UUID id, @NotNull UUID matId) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/materials/{matId}/attribution", LIST_OF_STOCK_ATTRIBUTION, id, matId);
  }

  /**
   * Reads the cached material catalogue of the order forms.
   *
   * @return the materials, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialDto> jobOrderMaterials() {
    return backendApiClient.getCached(CachedCatalog.MATERIALS_JOB_ORDER, LIST_OF_MATERIAL);
  }

  /**
   * Reads the cached, page-walked squadron catalogue sorted by name.
   *
   * @return the squadrons as one page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SquadronDto> squadrons() {
    return backendApiClient.getCached(CachedCatalog.SQUADRONS, PAGE_OF_SQUADRON);
  }

  /**
   * Reads the cached active Staffeln and Spezialkommandos with their profit-eligibility flag.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> activeOrgUnits() {
    return backendApiClient.getCached(
        CachedCatalog.ORG_UNITS_ACTIVE, LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION);
  }

  /**
   * Reads the cached active org units of all four kinds with their profit-eligibility flag.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> activeOrgUnitsAllKinds() {
    return backendApiClient.getCached(
        CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS, LIST_OF_ORG_UNIT_MEMBERSHIP_OPTION);
  }

  /**
   * Reads the cached location lookup of the collection pages.
   *
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationReferenceDto> locations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_LOOKUP, LIST_OF_LOCATION_REFERENCE);
  }

  /**
   * Reads the caller's own profile.
   *
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto currentUser() {
    return backendApiClient.get(CURRENT_USER, UserDto.class);
  }

  /**
   * Reads the material-collection entries of an order.
   *
   * @param id the order
   * @return the entries, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialCollectionEntryDto> materialCollection(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/orders/{id}/material-collection", LIST_OF_MATERIAL_COLLECTION_ENTRY, id);
  }

  /**
   * Reads the cross-order material demand of the caller's unit (REQ-ORDERS-034).
   *
   * @return the overview, or {@code null} when the backend sent no body
   */
  @Nullable
  public MaterialDemandOverviewDto materialDemand() {
    return backendApiClient.get("/api/v1/orders/material-demand", MaterialDemandOverviewDto.class);
  }

  /**
   * Creates an item order.
   *
   * @param dto the order with its item lines
   * @return the created order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto createItemOrder(@NotNull CreateJobOrderItemRequestDto dto) {
    return backendApiClient.post("/api/v1/orders/items", dto, JobOrderDto.class);
  }

  /**
   * Updates an item order's lines and metadata.
   *
   * @param id the item order
   * @param dto the order with its item lines and the version it replaces
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updateItemOrder(@NotNull UUID id, @NotNull CreateJobOrderItemRequestDto dto) {
    return backendApiClient.put("/api/v1/orders/{id}/items", dto, JobOrderDto.class, id);
  }

  /**
   * Creates a material order.
   *
   * @param dto the order with its materials
   * @return the created order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto createOrder(@NotNull CreateJobOrderDto dto) {
    return backendApiClient.post("/api/v1/orders", dto, JobOrderDto.class);
  }

  /**
   * Updates a material order as a logistician.
   *
   * @param id the order
   * @param dto the order with its materials and the version it replaces
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updateOrder(@NotNull UUID id, @NotNull CreateJobOrderDto dto) {
    return backendApiClient.put(ORDER, dto, JobOrderDto.class, id);
  }

  /**
   * Updates a material order as its requester (REQ-ORDERS-023).
   *
   * @param id the order
   * @param dto the comment, materials and the version it replaces
   * @return the updated redacted order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updateOrderAsRequester(@NotNull UUID id, @NotNull CreateJobOrderDto dto) {
    return backendApiClient.put("/api/v1/orders/{id}/requested", dto, JobOrderDto.class, id);
  }

  /**
   * Moves an order to a new priority slot.
   *
   * @param id the order
   * @param priority the new 1-based slot
   * @return the moved order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updatePriority(@NotNull UUID id, @NotNull Integer priority) {
    return backendApiClient.put(
        "/api/v1/orders/{id}/priority?priority={priority}", null, JobOrderDto.class, id, priority);
  }

  /**
   * Moves an order to another status.
   *
   * @param id the order
   * @param dto the target status
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updateStatus(@NotNull UUID id, @NotNull UpdateJobOrderStatusDto dto) {
    return backendApiClient.put("/api/v1/orders/{id}/status", dto, JobOrderDto.class, id);
  }

  /**
   * Switches an item order's blueprint-variant counting mode.
   *
   * @param id the item order
   * @param dto the counting mode and the version it replaces
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto updateBlueprintVariantCounting(
      @NotNull UUID id, @NotNull UpdateJobOrderBlueprintCountingDto dto) {
    return backendApiClient.patch(
        "/api/v1/orders/{id}/blueprint-variant-counting", dto, JobOrderDto.class, id);
  }

  /**
   * Creates or updates a material claim on an order.
   *
   * @param id the order
   * @param dto the claim
   * @return the stored claim, or {@code null} when the backend sent no body
   */
  @Nullable
  public ClaimDto upsertClaim(@NotNull UUID id, @NotNull CreateClaimDto dto) {
    return backendApiClient.post("/api/v1/orders/{id}/claims", dto, ClaimDto.class, id);
  }

  /**
   * Withdraws a material claim.
   *
   * @param id the order
   * @param claimId the claim
   */
  public void withdrawClaim(@NotNull UUID id, @NotNull UUID claimId) {
    backendApiClient.delete("/api/v1/orders/{id}/claims/{claimId}", Void.class, id, claimId);
  }

  /**
   * Cancels (soft-deletes) an order.
   *
   * @param id the order
   */
  public void deleteOrder(@NotNull UUID id) {
    backendApiClient.delete(ORDER, Void.class, id);
  }

  /**
   * Adds an assignee to an order.
   *
   * @param id the order
   * @param userId the user to add
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto addAssignee(@NotNull UUID id, @NotNull UUID userId) {
    return backendApiClient.post(
        "/api/v1/orders/{id}/assignees/{userId}", null, JobOrderDto.class, id, userId);
  }

  /**
   * Removes an assignee from an order.
   *
   * @param id the order
   * @param userId the user to remove
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto removeAssignee(@NotNull UUID id, @NotNull UUID userId) {
    return backendApiClient.delete(
        "/api/v1/orders/{id}/assignees/{userId}", JobOrderDto.class, id, userId);
  }

  /**
   * Sets an assignee's note.
   *
   * @param id the order
   * @param userId the assignee
   * @param body the note and the assignee edge version
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto setAssigneeNote(
      @NotNull UUID id, @NotNull UUID userId, @NotNull AssigneeNoteRequest body) {
    return backendApiClient.put(
        "/api/v1/orders/{id}/assignees/{userId}/note", body, JobOrderDto.class, id, userId);
  }

  /**
   * Clears an assignee's note, checking the edge version when one is given.
   *
   * @param id the order
   * @param userId the assignee
   * @param version the assignee edge version, or {@code null} to skip the check
   * @return the updated order, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderDto deleteAssigneeNote(
      @NotNull UUID id, @NotNull UUID userId, @Nullable Long version) {
    return version != null
        ? backendApiClient.delete(
            "/api/v1/orders/{id}/assignees/{userId}/note?version={version}",
            JobOrderDto.class,
            id,
            userId,
            version)
        : backendApiClient.delete(
            "/api/v1/orders/{id}/assignees/{userId}/note", JobOrderDto.class, id, userId);
  }

  /**
   * Records a material handover.
   *
   * @param id the order
   * @param dto the handover
   * @return the stored handover, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderHandoverDto createHandover(
      @NotNull UUID id, @NotNull JobOrderHandoverCreateDto dto) {
    return backendApiClient.post(
        "/api/v1/orders/{id}/handovers", dto, JobOrderHandoverDto.class, id);
  }

  /**
   * Records an item handover; the backend completes a fully delivered order.
   *
   * @param id the item order
   * @param dto the handover
   * @return the stored item handover, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderItemHandoverDto createItemHandover(
      @NotNull UUID id, @NotNull JobOrderItemHandoverCreateDto dto) {
    return backendApiClient.post(
        "/api/v1/orders/{id}/item-handovers", dto, JobOrderItemHandoverDto.class, id);
  }

  /**
   * Books a production run against one item line (REQ-ORDERS-025).
   *
   * @param id the item order
   * @param itemId the item line
   * @param dto the amount, line version and per-entry consumption
   * @return the updated line, or {@code null} when the backend sent no body
   */
  @Nullable
  public JobOrderItemDto bookProduction(
      @NotNull UUID id, @NotNull UUID itemId, @NotNull JobOrderItemProductionCreateDto dto) {
    return backendApiClient.post(
        "/api/v1/orders/{id}/items/{itemId}/production", dto, JobOrderItemDto.class, id, itemId);
  }

  /**
   * Removes a material requirement from an order, keeping its inventory items.
   *
   * @param id the order
   * @param materialId the material
   */
  public void unlinkMaterial(@NotNull UUID id, @NotNull UUID materialId) {
    backendApiClient.delete(
        "/api/v1/orders/{id}/materials/{materialId}", Void.class, id, materialId);
  }

  /**
   * Detaches an inventory item from an order.
   *
   * @param id the order
   * @param inventoryItemId the inventory item
   */
  public void unlinkInventoryItem(@NotNull UUID id, @NotNull UUID inventoryItemId) {
    backendApiClient.delete(
        "/api/v1/orders/{id}/inventory/{inventoryItemId}/unlink", Void.class, id, inventoryItemId);
  }

  /**
   * Downloads the report PDF of a stored material handover, rendered in the caller's time zone.
   *
   * @param jobOrderId the order
   * @param handoverId the handover
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] handoverReport(
      @NotNull UUID jobOrderId, @NotNull UUID handoverId, @Nullable String userTimeZone) {
    return report(
        "/api/v1/orders/{id}/handovers/{handoverId}/report", jobOrderId, handoverId, userTimeZone);
  }

  /**
   * Downloads the report PDF of a stored item handover, rendered in the caller's time zone.
   *
   * @param jobOrderId the item order
   * @param handoverId the item handover
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] itemHandoverReport(
      @NotNull UUID jobOrderId, @NotNull UUID handoverId, @Nullable String userTimeZone) {
    return report(
        "/api/v1/orders/{id}/item-handovers/{handoverId}/report",
        jobOrderId,
        handoverId,
        userTimeZone);
  }

  /**
   * Renders a preview PDF of an unsaved material handover.
   *
   * @param jobOrderId the order
   * @param body the unsaved handover data
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] handoverReportPreview(
      @NotNull UUID jobOrderId, @NotNull HandoverReportPreviewRequestDto body) {
    String uri = "/api/v1/orders/{id}/handovers/report/preview";
    return backendApiClient.execute(
        HttpMethod.POST,
        uri,
        webClient ->
            webClient
                .post()
                .uri(uri, jobOrderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Downloads one stored handover report PDF, forwarding the caller's time zone.
   *
   * @param uri the report's path template
   * @param jobOrderId the order
   * @param handoverId the handover or item handover
   * @param userTimeZone the caller's IANA time zone, forwarded when not blank
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  private byte @Nullable [] report(
      @NotNull String uri,
      @NotNull UUID jobOrderId,
      @NotNull UUID handoverId,
      @Nullable String userTimeZone) {
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient ->
            webClient
                .get()
                .uri(uri, jobOrderId, handoverId)
                .headers(
                    h -> {
                      if (userTimeZone != null && !userTimeZone.isBlank()) {
                        h.set("X-User-Time-Zone", userTimeZone);
                      }
                    }),
        spec -> spec.bodyToMono(byte[].class));
  }
}
