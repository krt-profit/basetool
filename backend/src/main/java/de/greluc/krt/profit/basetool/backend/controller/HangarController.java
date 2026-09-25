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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation;
import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SetHomeLocationResponseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.HangarImportService;
import de.greluc.krt.profit.basetool.backend.service.HangarService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST surface for the personal hangar, the squadron-wide overview, the admin per-user hangar and
 * the third-party ship-export JSON import.
 *
 * <p>{@code /my-ships} derives the owner from the JWT; {@code /users/{userId}/ships} is ADMIN-only.
 * The class-level {@code isAuthenticated()} gate is only the floor (REQ-SEC-052) and is ANDed with
 * the URL rule requiring {@code HANGAR_READ}, {@code HANGAR_WRITE} or {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/hangar")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HangarController {
  private final HangarService hangarService;
  private final HangarImportService hangarImportService;
  private final UserService userService;
  private final ShipMapper shipMapper;
  private final UserMapper userMapper;
  private final AuthHelperService authHelperService;

  /**
   * Returns one page of the calling user's own ships in the fixed personal-hangar order, with the
   * order and the optional search spanning the whole fleet (REQ-HANGAR-002).
   *
   * @param jwt caller's JWT — its {@code sub} claim derives the owner; never read from the URL
   * @param page zero-based page index
   * @param size page size
   * @param search optional case-insensitive ship-type/manufacturer name filter; blank means none
   * @return one ordered, optionally filtered page of the caller's ships
   */
  @GetMapping("/my-ships")
  @Transactional(readOnly = true)
  public PageResponse<ShipDto> getMyShips(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search) {
    Pageable pageable = PaginationUtil.createUnsortedPageRequest(page, size);
    Page<Ship> p =
        hangarService.getMyShipsFiltered(userService.getUserIdFromJwt(jwt), search, pageable);
    return PageResponse.of(p.map(shipMapper::toDto));
  }

  /**
   * Lists ships across all users. Requires the {@code HANGAR_READ} authority.
   *
   * @return paged ship DTOs
   */
  @GetMapping("/ships")
  @PreAuthorize("hasAuthority('" + Permissions.HANGAR_READ + "')")
  @Transactional(readOnly = true)
  public PageResponse<ShipDto> getAllShips(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "insurance", "fitted", "id"), "name");
    Page<Ship> p = hangarService.getAllShips(pageable);
    userMapper.primeStaffelMemberships(p.getContent().stream().map(Ship::getOwner).toList());
    return PageResponse.of(p.map(shipMapper::toDto));
  }

  /**
   * Returns per-ship-type counts across the squadron; admins and officers additionally see the
   * per-ship owner, location and fitted breakdown (REQ-HANGAR-001).
   *
   * @param page zero-based page index
   * @param size page size
   * @param sort sort parameter ({@code shipType.name} only)
   * @param search optional ship-type/manufacturer name filter; blank means no filter
   * @return paged overview DTOs
   */
  @GetMapping("/squadron-overview")
  @Transactional(readOnly = true)
  public PageResponse<SquadronShipOverviewDto> getSquadronOverview(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String search) {
    boolean includeOwnerDetails = authHelperService.isAdminOrOfficer();
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("shipType.name"), "shipType.name");
    Page<SquadronShipOverviewDto> p =
        hangarService.getSquadronOverview(pageable, includeOwnerDetails, search);
    return PageResponse.of(p);
  }

  /**
   * Adds a ship to the calling user's hangar.
   *
   * @return the persisted ship DTO
   */
  @PostMapping("/ships")
  @PreAuthorize("isAuthenticated()")
  @Transactional
  public ShipDto addShip(
      @AuthenticationPrincipal Jwt jwt, @RequestBody @Valid ShipRequestDto shipRequest) {
    return shipMapper.toDto(hangarService.addShip(userService.getUserIdFromJwt(jwt), shipRequest));
  }

  /**
   * Updates one of the calling user's ships. Service-layer ownership check ensures cross-user
   * access is rejected.
   *
   * @return the persisted ship DTO
   */
  @PutMapping("/ships/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditShip(#id)")
  @Transactional
  public ShipDto updateMyShip(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ShipRequestDto shipRequest) {
    return shipMapper.toDto(
        hangarService.updateShip(userService.getUserIdFromJwt(jwt), id, shipRequest));
  }

  /**
   * Deletes one of the calling user's ships. Mission-unit references are detached before delete.
   */
  @DeleteMapping("/ships/{id}")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canEditShip(#id)")
  public void deleteMyShip(@AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID id) {
    hangarService.deleteShip(userService.getUserIdFromJwt(jwt), id);
  }

  /**
   * Removes every ship the calling user owns. Mission-unit references to those ships are detached
   * before delete so no FK constraint fires.
   *
   * @return 204 No Content
   */
  @Operation(
      summary = "Delete all own ships",
      description =
          "Deletes all ships of the authenticated user. Links to mission units are safely"
              + " dissolved.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "All ships deleted successfully"),
    @ApiResponse(responseCode = "401", description = "Not authenticated"),
    @ApiResponse(responseCode = "403", description = "Not authorized")
  })
  @DeleteMapping("/ships")
  @PreAuthorize("hasAuthority('" + Permissions.HANGAR_WRITE + "')")
  public ResponseEntity<Void> deleteAllMyShips(@AuthenticationPrincipal Jwt jwt) {
    hangarService.deleteAllShipsForUser(userService.getUserIdFromJwt(jwt));
    return ResponseEntity.noContent().build();
  }

  /**
   * Admin-only: lists a target user's hangar. User id comes from the path (not the JWT) so admins
   * can inspect any user's fleet.
   *
   * @return paged ship DTOs
   */
  @GetMapping("/users/{userId}/ships")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional(readOnly = true)
  public PageResponse<ShipDto> getUserShips(
      @PathVariable @NotNull UUID userId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, sort, Set.of("name", "insurance", "fitted", "id"), "name");
    Page<Ship> p = hangarService.getMyShips(userId, pageable);
    return PageResponse.of(p.map(shipMapper::toDto));
  }

  /** Admin-only: adds a ship to a target user's hangar. */
  @PostMapping("/users/{userId}/ships")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional
  public ShipDto addUserShip(
      @PathVariable @NotNull UUID userId, @RequestBody @Valid ShipRequestDto shipRequest) {
    return shipMapper.toDto(hangarService.addShip(userId, shipRequest));
  }

  /** Admin-only: updates a target user's ship. */
  @PutMapping("/users/{userId}/ships/{shipId}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional
  public ShipDto updateUserShip(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID shipId,
      @RequestBody @Valid @NotNull ShipRequestDto shipRequest) {
    return shipMapper.toDto(hangarService.updateShip(userId, shipId, shipRequest));
  }

  /** Admin-only: deletes a target user's ship. */
  @DeleteMapping("/users/{userId}/ships/{shipId}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteUserShip(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID shipId) {
    hangarService.deleteShip(userId, shipId);
  }

  /**
   * Imports a ship-export JSON file (CCU Game Fleetview, HangarXPLOR, Fleetyards or StarJump
   * FleetViewer; format auto-detected), creating only missing rows owned by the caller.
   *
   * @param jwt caller's JWT — its {@code sub} claim becomes the new rows' owner id
   * @param file uploaded JSON file
   * @return import summary (created / skipped / duplicate counts plus the unmatched-ship list)
   */
  @PostMapping("/import/ships")
  @PreAuthorize("isAuthenticated()")
  @Transactional
  public FleetviewImportResponseDto importShips(
      @AuthenticationPrincipal Jwt jwt, @RequestParam("file") @NotNull MultipartFile file) {
    return hangarImportService.importShips(userService.getUserIdFromJwt(jwt), file);
  }

  /**
   * Alias of {@link #importShips(Jwt, MultipartFile)} under the {@code /import/fleetview} path,
   * with an identical response.
   *
   * @param jwt caller's JWT — its {@code sub} claim becomes the new rows' owner id
   * @param file uploaded JSON file
   * @return import summary (created / skipped / duplicate counts plus the unmatched-ship list)
   * @deprecated use {@code /api/v1/hangar/import/ships}; the {@code Sunset} and {@code Link}
   *     response headers carry the same hint
   */
  @PostMapping("/import/fleetview")
  @PreAuthorize("isAuthenticated()")
  @Transactional
  @ApiDeprecation(sunset = "2027-05-14", replacement = "/api/v1/hangar/import/ships")
  @Deprecated(since = "2026-05-14", forRemoval = true)
  public FleetviewImportResponseDto importFleetview(
      @AuthenticationPrincipal Jwt jwt, @RequestParam("file") @NotNull MultipartFile file) {
    return hangarImportService.importShips(userService.getUserIdFromJwt(jwt), file);
  }

  /** Bulk reset of the {@code fitted} flag on every ship in the squadron. ADMIN/OFFICER-only. */
  @PostMapping("/ships/reset-fitted")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  public void resetAllFittedStatus() {
    hangarService.resetAllFittedStatus();
  }

  /**
   * Bulk-sets the chosen curated home location on every ship the calling user owns. The location id
   * comes from the request body; the owner is derived from the JWT, so the action can never touch
   * another user's ships.
   *
   * @param jwt caller's JWT — its {@code sub} claim is the owner whose ships are updated
   * @param request the curated home location id
   * @return the number of ships updated
   */
  @NotNull
  @Operation(
      summary = "Set home location for all own ships",
      description =
          "Sets the location of all ships of the authenticated user to the chosen, curated"
              + " home location.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Number of ships updated"),
    @ApiResponse(responseCode = "400", description = "Invalid or missing home location"),
    @ApiResponse(responseCode = "401", description = "Not authenticated")
  })
  @PostMapping("/ships/home-location")
  @PreAuthorize("isAuthenticated()")
  @Transactional
  public SetHomeLocationResponseDto setHomeLocationForMyShips(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @Valid @NotNull SetHomeLocationRequestDto request) {
    int updated =
        hangarService.setHomeLocationForMyShips(
            userService.getUserIdFromJwt(jwt), request.locationId());
    return new SetHomeLocationResponseDto(updated);
  }
}
