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

package de.greluc.krt.profit.basetool.frontend.personalinventory.client;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryLocationOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the personal-inventory domain: the caller's own items, the admin
 * management of a member's items and the location type-ahead behind the item form, over {@link
 * BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class PersonalInventoryBackendClient {

  /** The type-ahead's result cap when the page names none. */
  private static final int DEFAULT_LOCATION_LIMIT = 25;

  /** The largest type-ahead result the backend serves. */
  private static final int MAX_LOCATION_LIMIT = 2000;

  private static final ParameterizedTypeReference<List<PersonalInventoryLocationOption>>
      LOCATION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PersonalInventoryItemDto>>
      ITEM_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PersonalBlueprintDto>>
      PERSONAL_BLUEPRINT_PAGE = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * The paging, sorting and filter values of one item-list page.
   *
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size
   * @param sort a well-formed {@code field,asc|desc} sort spec, or {@code null}
   * @param q the free-text filter, or {@code null}/blank for none
   */
  public record ItemQuery(
      @Nullable Integer page, int size, @Nullable String sort, @Nullable String q) {}

  /**
   * Reads a one-element page of the caller's owned blueprints, whose total feeds the „Blueprints"
   * tab count.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalBlueprintDto> blueprintCountPage() {
    return backendApiClient.get("/api/v1/personal-blueprints?size=1", PERSONAL_BLUEPRINT_PAGE);
  }

  /**
   * Reads one page of the caller's items.
   *
   * @param query the page, sort and filter
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalInventoryItemDto> itemPage(@NotNull ItemQuery query) {
    StringBuilder uri = new StringBuilder("/api/v1/personal-inventory?");
    List<Object> variables = new ArrayList<>();
    if (query.page() != null) {
      uri.append("page={page}&");
      variables.add(query.page());
    }
    uri.append("size={size}");
    variables.add(query.size());
    if (query.sort() != null) {
      uri.append("&sort={sort}");
      variables.add(query.sort());
    }
    String q = query.q();
    if (q != null && !q.isBlank()) {
      uri.append("&q={q}");
      variables.add(q);
    }
    return backendApiClient.get(uri.toString(), ITEM_PAGE, variables.toArray());
  }

  /**
   * Creates an item for the caller.
   *
   * @param request the item
   * @return the stored item, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalInventoryItemDto create(@NotNull PersonalInventoryItemCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/personal-inventory", request, PersonalInventoryItemDto.class);
  }

  /**
   * Updates one of the caller's items.
   *
   * @param id the item
   * @param request the new values and the version they replace
   * @return the stored item, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalInventoryItemDto update(
      @NotNull UUID id, @NotNull PersonalInventoryItemUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/personal-inventory/{id}", request, PersonalInventoryItemDto.class, id);
  }

  /**
   * Deletes one of the caller's items.
   *
   * @param id the item
   */
  public void delete(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/personal-inventory/{id}", Void.class, id);
  }

  /**
   * Searches the locations the caller may pick, by name, for the item form's type-ahead.
   *
   * @param q the name fragment, empty for the first locations
   * @param limit the result cap, {@code null} for 25, clamped to {@code [1, 2000]}
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PersonalInventoryLocationOption> searchLocations(
      @NotNull String q, @Nullable Integer limit) {
    return backendApiClient.get(
        "/api/v1/personal-inventory/locations?q={q}&limit={limit}",
        LOCATION_LIST,
        q,
        locationLimit(limit));
  }

  /**
   * Searches the locations a member's items may take, by name, for the admin item form.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param q the name fragment, empty for the first locations
   * @param limit the result cap, {@code null} for 25, clamped to {@code [1, 2000]}
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PersonalInventoryLocationOption> searchMemberLocations(
      @NotNull UUID userSub, @NotNull String q, @Nullable Integer limit) {
    return backendApiClient.get(
        "/api/v1/personal-inventory/admin/{userSub}/locations?q={q}&limit={limit}",
        LOCATION_LIST,
        userSub,
        q,
        locationLimit(limit));
  }

  /**
   * Applies the type-ahead's default and bounds to a requested result cap.
   *
   * @param limit the requested cap, or {@code null}
   * @return the cap sent to the backend
   */
  private static int locationLimit(@Nullable Integer limit) {
    return limit == null ? DEFAULT_LOCATION_LIMIT : Math.clamp(limit, 1, MAX_LOCATION_LIMIT);
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
   * Reads one page of a member's items for the admin page.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param query the page, sort and filter
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PersonalInventoryItemDto> memberItemPage(
      @NotNull UUID userSub, @NotNull ItemQuery query) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath("/api/v1/personal-inventory/admin/{userSub}");
    if (query.page() != null) {
      builder.queryParam("page", query.page());
    }
    builder.queryParam("size", query.size());
    if (query.sort() != null) {
      builder.queryParam("sort", query.sort());
    }
    String q = query.q();
    if (q != null && !q.isBlank()) {
      builder.queryParam("q", "{q}");
      String uri = builder.encode().build().toUriString();
      return backendApiClient.get(uri, ITEM_PAGE, userSub, q);
    }
    String uri = builder.encode().build().toUriString();
    return backendApiClient.get(uri, ITEM_PAGE, userSub);
  }

  /**
   * Creates an item for a member.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param request the item
   * @return the stored item, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalInventoryItemDto createForMember(
      @NotNull UUID userSub, @NotNull PersonalInventoryItemCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/personal-inventory/admin/{userSub}",
        request,
        PersonalInventoryItemDto.class,
        userSub);
  }

  /**
   * Updates any member's item.
   *
   * @param id the item
   * @param request the new values and the version they replace
   * @return the stored item, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonalInventoryItemDto updateForMember(
      @NotNull UUID id, @NotNull PersonalInventoryItemUpdateRequest request) {
    return backendApiClient.put(
        "/api/v1/personal-inventory/admin/items/{id}", request, PersonalInventoryItemDto.class, id);
  }

  /**
   * Deletes any member's item.
   *
   * @param id the item
   */
  public void deleteForMember(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/personal-inventory/admin/items/{id}", Void.class, id);
  }
}
