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

package de.greluc.krt.profit.basetool.frontend.inventory.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryMergeCandidatesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the inventory domain: the Lager reads and drilldowns, the stack entries,
 * the bookings and their allocations, notes and markers, and the catalogues and member lookups the
 * Lager pages offer, over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class InventoryBackendClient {

  /** The backend path that clears the global inventory. */
  private static final String DELETE_ALL_URI = "/api/v1/inventory/all";

  private static final ParameterizedTypeReference<PageResponse<AggregatedInventoryDto>>
      AGGREGATED_INVENTORY_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<InventoryItemDto>>
      INVENTORY_ITEM_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<UUID>> UUID_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<InventoryGameItemReferenceDto>>
      GAME_ITEM_REFERENCE_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<GroupedInventoryDto>>
      GROUPED_INVENTORY_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      ORG_UNIT_MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<UserReferenceDto>> USER_REFERENCE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialReferenceDto>>
      MATERIAL_REFERENCE_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<LocationReferenceDto>>
      LOCATION_REFERENCE_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<JobOrderReferenceDto>>
      JOB_ORDER_REFERENCE_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MissionReferenceDto>>
      MISSION_REFERENCE_LIST = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /** The two grouped Lager listings. */
  public enum Listing {
    /** The caller's own Lager, {@code /inventory/my}. */
    MY,
    /** The Lager of every member in the caller's scope, {@code /inventory/all}. */
    ALL
  }

  /**
   * The filters of the item view of a Lager listing (REQ-INV-030, REQ-INV-040, REQ-INV-053).
   *
   * @param gameItemIds the game items to narrow to, or {@code null}
   * @param locationIds the storage locations to narrow to, or {@code null}
   * @param jobOrderIds the job orders to narrow to, or {@code null}
   * @param personalOnly whether only the caller's personal entries are listed
   * @param nonPersonalOnly whether only the caller's shared entries are listed
   * @param stolenOnly whether only stock marked „gestohlen" is listed
   * @param nonStolenOnly whether stock marked „gestohlen" is hidden
   */
  public record ItemFilter(
      @Nullable List<UUID> gameItemIds,
      @Nullable List<UUID> locationIds,
      @Nullable List<UUID> jobOrderIds,
      boolean personalOnly,
      boolean nonPersonalOnly,
      boolean stolenOnly,
      boolean nonStolenOnly) {

    /** No narrowing at all. */
    public static final ItemFilter NONE =
        new ItemFilter(null, null, null, false, false, false, false);
  }

  /**
   * The filters of the material view of a Lager listing (REQ-INV-040, REQ-INV-053).
   *
   * @param materialIds the materials to narrow to, or {@code null}
   * @param locationIds the storage locations to narrow to, or {@code null}
   * @param minQuality the quality floor, or {@code null}
   * @param jobOrderIds the job orders to narrow to, or {@code null}
   * @param missionIds the missions to narrow to, or {@code null}
   * @param personalOnly whether only the caller's personal entries are listed
   * @param nonPersonalOnly whether only the caller's shared entries are listed
   * @param stolenOnly whether only stock marked „gestohlen" is listed
   * @param nonStolenOnly whether stock marked „gestohlen" is hidden
   */
  public record MaterialFilter(
      @Nullable List<UUID> materialIds,
      @Nullable List<UUID> locationIds,
      @Nullable Integer minQuality,
      @Nullable List<UUID> jobOrderIds,
      @Nullable List<UUID> missionIds,
      boolean personalOnly,
      boolean nonPersonalOnly,
      boolean stolenOnly,
      boolean nonStolenOnly) {

    /** No narrowing at all. */
    public static final MaterialFilter NONE =
        new MaterialFilter(null, null, null, null, null, false, false, false, false);
  }

  /**
   * Reads one page of the squadron-wide aggregated Lager, by material or by game item
   * (REQ-INV-030).
   *
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @param itemsView whether the game-item catalogue is aggregated instead of the material one
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<AggregatedInventoryDto> aggregated(
      @Nullable Integer page, @Nullable Integer size, boolean itemsView) {
    StringBuilder uri = new StringBuilder("/api/v1/inventory/aggregated?");
    List<Object> uriVariables = new ArrayList<>();
    if (page != null) {
      uri.append("page={page}&");
      uriVariables.add(page);
    }
    if (size != null) {
      uri.append("size={size}&");
      uriVariables.add(size);
    }
    if (itemsView) {
      uri.append("catalog=ITEM");
    } else {
      uri.append("sort=material.name,asc;quality,desc;amount,desc");
    }
    return backendApiClient.get(uri.toString(), AGGREGATED_INVENTORY_PAGE, uriVariables.toArray());
  }

  /**
   * Reads one page of a material or game-item drilldown (REQ-INV-033).
   *
   * @param gameItem whether the game-item drilldown is read instead of the material one
   * @param id the drilled-into material or game item
   * @param page the zero-based page index
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryItemDto> drilldownPage(
      boolean gameItem, @NotNull UUID id, int page, int size) {
    String uri =
        gameItem
            ? "/api/v1/inventory/game-item/{id}?page={page}&size={size}"
            : "/api/v1/inventory/material/{id}?page={page}&size={size}";
    return backendApiClient.get(uri, INVENTORY_ITEM_PAGE, id, page, size);
  }

  /**
   * Searches the bookable game items by name for the {@code remote-game-items} combobox.
   *
   * @param pageSize the number of matches to read
   * @param q the search term, empty for every item
   * @return the matches, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryGameItemReferenceDto> itemCatalog(int pageSize, @NotNull String q) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/inventory/item-catalog")
            .queryParam("size", pageSize)
            .queryParam("sort", "name,asc")
            .toUriString();
    return backendApiClient.get(uri + "&q={q}", GAME_ITEM_REFERENCE_PAGE, q);
  }

  /**
   * Reads the item view of a grouped Lager listing (REQ-INV-030).
   *
   * @param listing the listing
   * @param filter the item view's filters
   * @return the groups, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<GroupedInventoryDto> groupedItems(
      @NotNull Listing listing, @NotNull ItemFilter filter) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath(groupedPath(listing)).queryParam("catalog", "ITEM");
    appendIdParams(uriBuilder, "gameItemIds", filter.gameItemIds());
    appendIdParams(uriBuilder, "locationIds", filter.locationIds());
    appendIdParams(uriBuilder, "jobOrderIds", filter.jobOrderIds());
    appendFlags(
        uriBuilder,
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly());
    return backendApiClient.get(uriBuilder.build().toUriString(), GROUPED_INVENTORY_LIST);
  }

  /**
   * Reads the material view of a grouped Lager listing.
   *
   * @param listing the listing
   * @param filter the material view's filters
   * @return the groups, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<GroupedInventoryDto> groupedMaterials(
      @NotNull Listing listing, @NotNull MaterialFilter filter) {
    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath(groupedPath(listing));
    appendIdParams(uriBuilder, "materialIds", filter.materialIds());
    appendIdParams(uriBuilder, "locationIds", filter.locationIds());
    if (filter.minQuality() != null) {
      uriBuilder.queryParam("minQuality", filter.minQuality());
    }
    appendIdParams(uriBuilder, "jobOrderIds", filter.jobOrderIds());
    appendIdParams(uriBuilder, "missionIds", filter.missionIds());
    appendFlags(
        uriBuilder,
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly());
    return backendApiClient.get(uriBuilder.build().toUriString(), GROUPED_INVENTORY_LIST);
  }

  /**
   * Reads the ids of every own entry matching the item view's filters (REQ-INV-034).
   *
   * @param filter the item view's filters
   * @return the ids, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<UUID> myItemEntryIds(@NotNull ItemFilter filter) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/my-inventory/entry-ids");
    uriBuilder.queryParam("catalog", "ITEM");
    appendIdParams(uriBuilder, "gameItemIds", filter.gameItemIds());
    appendIdParams(uriBuilder, "jobOrderIds", filter.jobOrderIds());
    appendIdParams(uriBuilder, "locationIds", filter.locationIds());
    appendFlags(
        uriBuilder,
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly());
    return backendApiClient.get(uriBuilder.build().toUriString(), UUID_LIST);
  }

  /**
   * Reads the ids of every own entry matching the material view's filters (REQ-INV-034).
   *
   * @param filter the material view's filters
   * @return the ids, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<UUID> myMaterialEntryIds(@NotNull MaterialFilter filter) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/my-inventory/entry-ids");
    appendIdParams(uriBuilder, "materialIds", filter.materialIds());
    if (filter.minQuality() != null) {
      uriBuilder.queryParam("minQuality", filter.minQuality());
    }
    appendIdParams(uriBuilder, "jobOrderIds", filter.jobOrderIds());
    appendIdParams(uriBuilder, "missionIds", filter.missionIds());
    appendIdParams(uriBuilder, "locationIds", filter.locationIds());
    appendFlags(
        uriBuilder,
        filter.personalOnly(),
        filter.nonPersonalOnly(),
        filter.stolenOnly(),
        filter.nonStolenOnly());
    return backendApiClient.get(uriBuilder.build().toUriString(), UUID_LIST);
  }

  /**
   * Reads one page of a personal material stack's entries, oldest first.
   *
   * @param materialId the stack's material
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param personal whether the stack holds the caller's private stock
   * @param stolen whether the stack holds stock marked „gestohlen"
   * @param owningOrgUnitId the stack's owning org unit, or {@code null}
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryItemDto> myStackEntries(
      @NotNull UUID materialId,
      @NotNull UUID locationId,
      @Nullable Integer quality,
      boolean personal,
      boolean stolen,
      @Nullable UUID owningOrgUnitId,
      @Nullable Integer page,
      @Nullable Integer size) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/my-inventory/stack/entries")
            .queryParam("materialId", materialId)
            .queryParam("locationId", locationId)
            .queryParam("personal", personal);
    if (stolen) {
      uriBuilder.queryParam("stolen", true);
    }
    if (quality != null) {
      uriBuilder.queryParam("quality", quality);
    }
    appendStackTail(uriBuilder, owningOrgUnitId, page, size);
    return backendApiClient.get(uriBuilder.build().toUriString(), INVENTORY_ITEM_PAGE);
  }

  /**
   * Reads one page of a personal game-item stack's entries, oldest first (REQ-INV-030).
   *
   * @param gameItemId the stack's game item
   * @param locationId the stack's storage location
   * @param personal whether the stack holds the caller's private stock
   * @param stolen whether the stack holds stock marked „gestohlen"
   * @param owningOrgUnitId the stack's owning org unit, or {@code null}
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryItemDto> myGameItemStackEntries(
      @NotNull UUID gameItemId,
      @NotNull UUID locationId,
      boolean personal,
      boolean stolen,
      @Nullable UUID owningOrgUnitId,
      @Nullable Integer page,
      @Nullable Integer size) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/my-inventory/stack/entries")
            .queryParam("catalog", "ITEM")
            .queryParam("gameItemId", gameItemId)
            .queryParam("locationId", locationId)
            .queryParam("personal", personal);
    if (stolen) {
      uriBuilder.queryParam("stolen", true);
    }
    appendStackTail(uriBuilder, owningOrgUnitId, page, size);
    return backendApiClient.get(uriBuilder.build().toUriString(), INVENTORY_ITEM_PAGE);
  }

  /**
   * Reads one page of a squadron-wide material stack's entries, oldest first.
   *
   * @param materialId the stack's material
   * @param userId the stack's owning member
   * @param locationId the stack's storage location
   * @param quality the stack's quality grade, or {@code null}
   * @param stolen whether the stack holds stock marked „gestohlen"
   * @param owningOrgUnitId the stack's owning org unit, or {@code null}
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryItemDto> allStackEntries(
      @NotNull UUID materialId,
      @NotNull UUID userId,
      @NotNull UUID locationId,
      @Nullable Integer quality,
      boolean stolen,
      @Nullable UUID owningOrgUnitId,
      @Nullable Integer page,
      @Nullable Integer size) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/all/stack/entries")
            .queryParam("materialId", materialId)
            .queryParam("userId", userId)
            .queryParam("locationId", locationId);
    if (stolen) {
      uriBuilder.queryParam("stolen", true);
    }
    if (quality != null) {
      uriBuilder.queryParam("quality", quality);
    }
    appendStackTail(uriBuilder, owningOrgUnitId, page, size);
    return backendApiClient.get(uriBuilder.build().toUriString(), INVENTORY_ITEM_PAGE);
  }

  /**
   * Reads one page of a squadron-wide game-item stack's entries, oldest first (REQ-INV-030).
   *
   * @param gameItemId the stack's game item
   * @param userId the stack's owning member
   * @param locationId the stack's storage location
   * @param stolen whether the stack holds stock marked „gestohlen"
   * @param owningOrgUnitId the stack's owning org unit, or {@code null}
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryItemDto> allGameItemStackEntries(
      @NotNull UUID gameItemId,
      @NotNull UUID userId,
      @NotNull UUID locationId,
      boolean stolen,
      @Nullable UUID owningOrgUnitId,
      @Nullable Integer page,
      @Nullable Integer size) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath("/api/v1/inventory/all/stack/entries")
            .queryParam("catalog", "ITEM")
            .queryParam("gameItemId", gameItemId)
            .queryParam("userId", userId)
            .queryParam("locationId", locationId);
    if (stolen) {
      uriBuilder.queryParam("stolen", true);
    }
    appendStackTail(uriBuilder, owningOrgUnitId, page, size);
    return backendApiClient.get(uriBuilder.build().toUriString(), INVENTORY_ITEM_PAGE);
  }

  /**
   * Reads which of the given Lager rows carry an active Materialbörse offer.
   *
   * @param ids the rows to check, at least one
   * @return the ids among them on offer, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<UUID> releasedItemIds(@NotNull List<UUID> ids) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/material-exchange/released-item-ids");
    for (UUID id : ids) {
      uri.queryParam("ids", id);
    }
    return backendApiClient.get(uri.build().toUriString(), UUID_LIST);
  }

  /**
   * Probes whether a booking would merge into an existing row (REQ-INV-026).
   *
   * @param userId the member booked for, or {@code null} for the caller
   * @param materialId the material being booked in
   * @param locationId the target location
   * @param quality the quality grade
   * @param personal whether the row would be personal
   * @param stolen whether the row would be marked „gestohlen"
   * @param owningOrgUnitId the picked owning org unit, or {@code null} for the auto-stamp
   * @return the probe's answer, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryMergeCandidatesDto mergeCandidates(
      @Nullable UUID userId,
      @NotNull UUID materialId,
      @NotNull UUID locationId,
      int quality,
      boolean personal,
      boolean stolen,
      @Nullable UUID owningOrgUnitId) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath("/api/v1/inventory/merge-candidates")
            .queryParam("materialId", materialId)
            .queryParam("locationId", locationId)
            .queryParam("quality", quality)
            .queryParam("personal", personal)
            .queryParam("stolen", stolen);
    if (userId != null) {
      uri.queryParam("userId", userId);
    }
    if (owningOrgUnitId != null) {
      uri.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    return backendApiClient.get(uri.toUriString(), InventoryMergeCandidatesDto.class);
  }

  /**
   * Reads one member's profile.
   *
   * @param id the member
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto user(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/users/{id}", UserDto.class, id);
  }

  /**
   * Reads every org-unit membership of a member as owner-picker options.
   *
   * @param userId the member
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> memberships(@NotNull UUID userId) {
    return backendApiClient.get(
        "/api/v1/users/{id}/memberships?allKinds=true", ORG_UNIT_MEMBERSHIP_OPTION_LIST, userId);
  }

  /**
   * Reads the org units the caller may pick as a row's owner.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> pickableOrgUnits() {
    return backendApiClient.get(
        "/api/v1/users/me/pickable-org-units", ORG_UNIT_MEMBERSHIP_OPTION_LIST);
  }

  /**
   * Reads the member lookup behind the target-user and user-filter dropdowns.
   *
   * @return the members, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<UserReferenceDto> users() {
    return backendApiClient.get("/api/v1/users/lookup", USER_REFERENCE_LIST);
  }

  /**
   * Reads the cached material lookup catalogue.
   *
   * @return the materials, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialReferenceDto> materials() {
    return backendApiClient.getCached(CachedCatalog.MATERIALS_LOOKUP, MATERIAL_REFERENCE_LIST);
  }

  /**
   * Reads the cached location lookup catalogue.
   *
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationReferenceDto> locations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_LOOKUP, LOCATION_REFERENCE_LIST);
  }

  /**
   * Reads the active job orders the caller may see, optionally with their outstanding needs
   * (REQ-INV-039).
   *
   * @param withNeeds whether each order's per-material and per-item needs are requested
   * @return the orders, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<JobOrderReferenceDto> activeJobOrders(boolean withNeeds) {
    return backendApiClient.get(
        withNeeds ? "/api/v1/orders/lookup?withNeeds=true" : "/api/v1/orders/lookup",
        JOB_ORDER_REFERENCE_LIST);
  }

  /**
   * Reads the mission lookup behind the mission filter and association dropdowns.
   *
   * @return the missions, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MissionReferenceDto> missions() {
    return backendApiClient.get("/api/v1/missions/lookup", MISSION_REFERENCE_LIST);
  }

  /**
   * Books a new Lager row in.
   *
   * @param request the booking
   * @return the stored row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto create(@NotNull InventoryItemCreateDto request) {
    return backendApiClient.post("/api/v1/inventory", request, InventoryItemDto.class);
  }

  /**
   * Books a row out from the classic form, discarding the backend's answer.
   *
   * @param id the row
   * @param request the book-out
   */
  public void bookOut(@NotNull UUID id, @NotNull InventoryItemBookOutDto request) {
    backendApiClient.post("/api/v1/inventory/{id}/book-out", request, Void.class, id);
  }

  /**
   * Transfers a row to another owner or location through the book-out endpoint.
   *
   * @param id the row
   * @param request the transfer
   * @return the resulting row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto transfer(@NotNull UUID id, @NotNull InventoryItemBookOutDto request) {
    return backendApiClient.post(
        "/api/v1/inventory/{id}/book-out", request, InventoryItemDto.class, id);
  }

  /**
   * Rebooks part of a row onto the opposite personal marker (REQ-INV-007).
   *
   * @param id the source row
   * @param request the amount, version and target org unit
   * @return the new row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto personalRebook(
      @NotNull UUID id, @NotNull InventoryItemPersonalRebookDto request) {
    return backendApiClient.post(
        "/api/v1/inventory/{id}/personal-rebook", request, InventoryItemDto.class, id);
  }

  /**
   * Books several owned rows out in full.
   *
   * @param request the rows
   */
  public void bulkCheckout(@NotNull BulkCheckoutRequest request) {
    backendApiClient.post("/api/v1/inventory/bulk-checkout", request, Void.class);
  }

  /**
   * Rebooks several owned rows at once (REQ-INV-036).
   *
   * @param request the rows, the mode and its target fields
   * @return the moved and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public BulkRebookResultDto bulkRebook(@NotNull BulkRebookRequest request) {
    return backendApiClient.post(
        "/api/v1/inventory/bulk-rebook", request, BulkRebookResultDto.class);
  }

  /**
   * Earmarks part of a row to a job order or mission (REQ-INV-027).
   *
   * @param id the row
   * @param request the dimension, target, amount and version
   * @return the updated row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto addAllocation(
      @NotNull UUID id, @NotNull InventoryAllocationWriteDto request) {
    return backendApiClient.post(
        "/api/v1/inventory/{id}/allocation", request, InventoryItemDto.class, id);
  }

  /**
   * Changes the amount of an allocation (REQ-INV-027).
   *
   * @param id the row
   * @param request the dimension, target, new amount and version
   * @return the updated row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto changeAllocation(
      @NotNull UUID id, @NotNull InventoryAllocationWriteDto request) {
    return backendApiClient.patch(
        "/api/v1/inventory/{id}/allocation", request, InventoryItemDto.class, id);
  }

  /**
   * Removes an allocation, naming the slice in the request body (REQ-INV-027).
   *
   * @param id the row
   * @param request the dimension, target and version
   * @return the updated row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto removeAllocation(
      @NotNull UUID id, @NotNull InventoryAllocationWriteDto request) {
    return backendApiClient.delete(
        "/api/v1/inventory/{id}/allocation", request, InventoryItemDto.class, id);
  }

  /**
   * Sets, edits or removes a row's note.
   *
   * @param id the row
   * @param request the note and version
   * @return the updated row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto updateNote(
      @NotNull UUID id, @NotNull InventoryItemNoteUpdateRequest request) {
    return backendApiClient.put("/api/v1/inventory/{id}/note", request, InventoryItemDto.class, id);
  }

  /**
   * Sets a row's delivered flag.
   *
   * @param id the row
   * @param request the flag and version
   * @return the updated row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto updateDelivered(
      @NotNull UUID id, @NotNull UpdateDeliveredRequest request) {
    return backendApiClient.patch(
        "/api/v1/inventory/{id}/delivered", request, InventoryItemDto.class, id);
  }

  /**
   * Moves one personal row to another org unit (REQ-INV-052).
   *
   * @param id the row
   * @param request the version, target unit and merge opt-in
   * @return the resulting row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto changeOrgUnit(
      @NotNull UUID id, @NotNull InventoryItemOrgUnitChangeDto request) {
    return backendApiClient.post(
        "/api/v1/inventory/{id}/org-unit", request, InventoryItemDto.class, id);
  }

  /**
   * Moves several personal rows to another org unit (REQ-INV-052).
   *
   * @param request the rows, target unit and merge opt-in
   * @return the changed and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public BulkOrgUnitChangeResultDto bulkChangeOrgUnit(@NotNull BulkOrgUnitChangeRequest request) {
    return backendApiClient.post(
        "/api/v1/inventory/bulk-org-unit", request, BulkOrgUnitChangeResultDto.class);
  }

  /**
   * Sets or removes the „gestohlen" marker on a row or a part of it (REQ-INV-053).
   *
   * @param id the row
   * @param request the version, marker and amount
   * @return the resulting row, or {@code null} when the backend sent no body
   */
  @Nullable
  public InventoryItemDto markStolen(
      @NotNull UUID id, @NotNull InventoryItemStolenMarkDto request) {
    return backendApiClient.post(
        "/api/v1/inventory/{id}/stolen", request, InventoryItemDto.class, id);
  }

  /**
   * Sets or removes the „gestohlen" marker on several rows (REQ-INV-053).
   *
   * @param request the rows and marker
   * @return the changed and skipped counts, or {@code null} when the backend sent no body
   */
  @Nullable
  public BulkStolenMarkResultDto bulkMarkStolen(@NotNull BulkStolenMarkRequest request) {
    return backendApiClient.post(
        "/api/v1/inventory/bulk-stolen", request, BulkStolenMarkResultDto.class);
  }

  /**
   * Clears the global inventory.
   *
   * @return the backend's bodiless answer, or {@code null} when none arrived
   */
  @Nullable
  public ResponseEntity<Void> deleteAll() {
    return backendApiClient.execute(
        HttpMethod.DELETE,
        DELETE_ALL_URI,
        webClient -> webClient.delete().uri(DELETE_ALL_URI),
        WebClient.ResponseSpec::toBodilessEntity);
  }

  /**
   * Names the backend resource of a grouped listing.
   *
   * @param listing the listing
   * @return its path
   */
  @NotNull
  private static String groupedPath(@NotNull Listing listing) {
    return switch (listing) {
      case MY -> "/api/v1/inventory/my-inventory/grouped";
      case ALL -> "/api/v1/inventory/all/grouped";
    };
  }

  /**
   * Appends one repeated query parameter per id; a {@code null} or empty list appends nothing.
   *
   * @param uriBuilder the builder
   * @param name the parameter name
   * @param ids the ids, or {@code null}
   */
  private static void appendIdParams(
      @NotNull UriComponentsBuilder uriBuilder, @NotNull String name, @Nullable List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (UUID id : ids) {
      uriBuilder.queryParam(name, id.toString());
    }
  }

  /**
   * Appends the set personal and „gestohlen" narrowing flags.
   *
   * @param uriBuilder the builder
   * @param personalOnly relay {@code personalOnly=true}
   * @param nonPersonalOnly relay {@code nonPersonalOnly=true}
   * @param stolenOnly relay {@code stolenOnly=true}
   * @param nonStolenOnly relay {@code nonStolenOnly=true}
   */
  private static void appendFlags(
      @NotNull UriComponentsBuilder uriBuilder,
      boolean personalOnly,
      boolean nonPersonalOnly,
      boolean stolenOnly,
      boolean nonStolenOnly) {
    if (personalOnly) {
      uriBuilder.queryParam("personalOnly", true);
    }
    if (nonPersonalOnly) {
      uriBuilder.queryParam("nonPersonalOnly", true);
    }
    if (stolenOnly) {
      uriBuilder.queryParam("stolenOnly", true);
    }
    if (nonStolenOnly) {
      uriBuilder.queryParam("nonStolenOnly", true);
    }
  }

  /**
   * Appends the owning org unit and the page of a stack-entries read, each when present.
   *
   * @param uriBuilder the builder
   * @param owningOrgUnitId the owning org unit, or {@code null}
   * @param page the page index, or {@code null}
   * @param size the page size, or {@code null}
   */
  private static void appendStackTail(
      @NotNull UriComponentsBuilder uriBuilder,
      @Nullable UUID owningOrgUnitId,
      @Nullable Integer page,
      @Nullable Integer size) {
    if (owningOrgUnitId != null) {
      uriBuilder.queryParam("owningOrgUnitId", owningOrgUnitId);
    }
    if (page != null) {
      uriBuilder.queryParam("page", page);
    }
    if (size != null) {
      uriBuilder.queryParam("size", size);
    }
  }
}
