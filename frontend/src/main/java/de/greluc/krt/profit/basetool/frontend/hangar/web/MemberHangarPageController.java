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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipForm;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Renders the admin member hangar ({@code /admin/members/{userId}/hangar}), the ships of one member
 * with the dialog an admin adds and edits them in (REQ-HANGAR-011); {@link
 * MemberHangarShipProxyController} sends the writes.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/members/{userId}/hangar")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class MemberHangarPageController {

  /** Page sizes the picker offers, the shared REQ-INV-013 trio. */
  private static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** Page size applied when the request carries none or one outside {@link #PAGE_SIZES}. */
  private static final int DEFAULT_PAGE_SIZE = 50;

  private static final ParameterizedTypeReference<PageResponse<ShipTypeDto>> SHIP_TYPE_PAGE_TYPE =
      new ParameterizedTypeReference<PageResponse<ShipTypeDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<LocationDto>> LOCATION_PAGE_TYPE =
      new ParameterizedTypeReference<PageResponse<LocationDto>>() {};

  private static final ParameterizedTypeReference<PageResponse<ManufacturerDto>>
      MANUFACTURER_PAGE_TYPE = new ParameterizedTypeReference<PageResponse<ManufacturerDto>>() {};

  /** Reads the member's ships, the member and the member's org units, and sends the writes. */
  private final HangarBackendClient hangarClient;

  /** Runs the page's independent reads in parallel. */
  private final ParallelPageLoader parallelPageLoader;

  /** Reads the cached reference catalogues behind the ship form. */
  private final CachedCatalogListLoader catalogListLoader;

  /**
   * Renders the member's hangar, paginated, with the ship form's catalogues and the member's org
   * units for the add dialog's owner picker; {@code fragment=results} renders only the ship list.
   *
   * @param userId the member whose hangar is shown
   * @param page zero-based page index; negatives are clamped to 0
   * @param size page size, validated against {@link #PAGE_SIZES}
   * @param fragment {@code "results"} renders only the ship-list fragment (REQ-FE-005)
   * @param model model populated with the member, the ship page, the form catalogues and the
   *     member's hangar live-sync room
   * @return the {@code hangar/member-hangar} view, or its {@code memberHangarResults} fragment
   */
  @NotNull
  @GetMapping
  public String viewMemberHangar(
      @PathVariable @NotNull UUID userId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String fragment,
      Model model) {
    int effectiveSize = size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
    int effectivePage = page == null || page < 0 ? 0 : page;
    String baseUrl = "/admin/members/" + userId + "/hangar";

    PageResponse<ShipDto> ships = loadShips(userId, effectivePage, effectiveSize, model);
    model.addAttribute("memberId", userId);
    model.addAttribute("ships", ships.content() != null ? ships.content() : List.of());
    model.addAttribute("shipsPage", ships);
    model.addAttribute("pageSizes", PAGE_SIZES);
    model.addAttribute("pageSize", effectiveSize);
    model.addAttribute("paginationBaseUrl", baseUrl);
    if ("results".equalsIgnoreCase(fragment)) {
      return "hangar/member-hangar :: memberHangarResults";
    }

    CompletableFuture<UserDto> memberFuture =
        parallelPageLoader
            .<UserDto>loadAsync(() -> hangarClient.member(userId))
            .exceptionally(
                e -> {
                  log.warn("Failed to read the member for the admin member hangar", e);
                  return null;
                });
    CompletableFuture<List<OrgUnitMembershipOptionDto>> unitsFuture =
        parallelPageLoader
            .<List<OrgUnitMembershipOptionDto>>loadAsync(
                () -> {
                  List<OrgUnitMembershipOptionDto> units = hangarClient.memberOrgUnits(userId);
                  return units != null ? units : List.of();
                })
            .exceptionally(
                e -> {
                  log.warn("Failed to read the member's org units for the admin member hangar", e);
                  return List.of();
                });
    CompletableFuture<List<ShipTypeDto>> shipTypesFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.SHIP_TYPES, SHIP_TYPE_PAGE_TYPE, "ship types"));
    CompletableFuture<List<LocationDto>> locationsFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.LOCATIONS, LOCATION_PAGE_TYPE, "locations"));
    CompletableFuture<List<ManufacturerDto>> manufacturersFuture =
        parallelPageLoader.loadAsync(
            () ->
                catalogListLoader.loadPageContent(
                    CachedCatalog.MANUFACTURERS, MANUFACTURER_PAGE_TYPE, "manufacturers"));
    CompletableFuture.allOf(
            memberFuture, unitsFuture, shipTypesFuture, locationsFuture, manufacturersFuture)
        .join();

    List<ShipTypeDto> shipTypes = shipTypesFuture.join();
    shipTypes.sort(Comparator.comparing(ShipTypeDto::name, String.CASE_INSENSITIVE_ORDER));
    List<LocationDto> locations = locationsFuture.join();
    locations.sort(Comparator.comparing(LocationDto::name, String.CASE_INSENSITIVE_ORDER));
    List<ManufacturerDto> manufacturers = manufacturersFuture.join();
    manufacturers.sort(Comparator.comparing(ManufacturerDto::name, String.CASE_INSENSITIVE_ORDER));

    model.addAttribute("memberName", memberName(memberFuture.join()));
    model.addAttribute("ownerOptions", unitsFuture.join());
    model.addAttribute("shipForm", new ShipForm());
    model.addAttribute("shipTypes", shipTypes);
    model.addAttribute("locations", locations);
    model.addAttribute("manufacturers", manufacturers);
    model.addAttribute("memberHangarLiveSyncTopic", "hangar:" + userId);
    return "hangar/member-hangar";
  }

  /**
   * Reads one page of the member's ships; a failure renders an empty page with an error alert.
   *
   * @param userId the member
   * @param page zero-based page index
   * @param size page size
   * @param model model that receives the {@code error} key on failure
   * @return the page, never {@code null}
   */
  private @NotNull PageResponse<ShipDto> loadShips(
      @NotNull UUID userId, int page, int size, @NotNull Model model) {
    try {
      PageResponse<ShipDto> ships = hangarClient.memberShips(userId, page, size);
      if (ships != null) {
        return ships;
      }
    } catch (Exception e) {
      log.error("Failed to read the member's ships for the admin member hangar", e);
      model.addAttribute("error", "error.hangar.ships.load");
    }
    return new PageResponse<>(List.of(), page, size, 0L, 0, List.of());
  }

  /**
   * Picks the name the page head shows for the member.
   *
   * @param member the member, or {@code null} when it could not be read
   * @return the effective name, or {@code null} when none is known
   */
  @Contract("null -> null")
  private static @Nullable String memberName(@Nullable UserDto member) {
    if (member == null) {
      return null;
    }
    return member.effectiveName() != null ? member.effectiveName() : member.username();
  }
}
