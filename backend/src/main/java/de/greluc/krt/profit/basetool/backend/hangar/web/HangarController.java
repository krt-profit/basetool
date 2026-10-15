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

package de.greluc.krt.profit.basetool.backend.hangar.web;

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDto;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipMapper;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarImportService;
import de.greluc.krt.profit.basetool.backend.hangar.internal.HangarService;
import de.greluc.krt.profit.basetool.backend.hangar.internal.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.SetHomeLocationResponseDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipBulkOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipBulkOrgUnitResultDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.ShipOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.backend.hangar.internal.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
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
 * <p>{@code /my-ships} derives the owner from the JWT; {@code /admin/users/{userId}/ships} is
 * ADMIN-only. The class-level {@code isAuthenticated()} gate is only the floor (REQ-SEC-052); the
 * squadron overview and the home-location bulk edit carry {@link #HANGAR_ACCESS} on the method,
 * which the URL rule repeats.
 */
@RestController
@RequestMapping("/api/v1/hangar")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HangarController {

  /**
   * Authority gate of the squadron overview and the home-location bulk edit: {@code HANGAR_READ},
   * {@code HANGAR_WRITE} or {@code ROLE_ADMIN}, the same set as the {@code /api/v1/hangar/**} URL
   * rule.
   */
  public static final String HANGAR_ACCESS =
      "hasAnyAuthority('"
          + Permissions.HANGAR_READ
          + "','"
          + Permissions.HANGAR_WRITE
          + "','"
          + Roles.ROLE_PREFIX
          + Roles.ADMIN
          + "')";

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
   * @param fitted optional fitted filter; absent means fitted and unfitted ships
   * @return one ordered, optionally filtered page of the caller's ships
   */
  @GetMapping("/my-ships")
  @Transactional(readOnly = true)
  public PageResponse<ShipDto> getMyShips(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Boolean fitted) {
    Pageable pageable = PaginationUtil.createUnsortedPageRequest(page, size);
    Page<Ship> p =
        hangarService.getMyShipsFiltered(
            userService.getUserIdFromJwt(jwt), search, fitted, pageable);
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
  @PreAuthorize(HANGAR_ACCESS)
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
  @PreAuthorize("isAuthenticated() and @hangarAccessPolicy.canEditShip(#id)")
  @Transactional
  public ShipDto updateMyShip(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ShipRequestDto shipRequest) {
    return shipMapper.toDto(
        hangarService.updateShip(userService.getUserIdFromJwt(jwt), id, shipRequest));
  }

  /**
   * Changes the owning org unit of one of the calling user's ships to one of their direct
   * memberships or none (REQ-HANGAR-009).
   *
   * @param jwt caller's JWT; its {@code sub} claim must own the ship
   * @param id ship id
   * @param request the new org unit and the unit counter the caller last saw
   * @return the ship as it is after the change
   */
  @Operation(
      summary = "Change a ship's org unit",
      description =
          "Moves one of the caller's ships into one of the caller's direct org units, or out of"
              + " every unit. Guarded by the ship's owningOrgUnitVersion only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Ship as it is after the change"),
    @ApiResponse(responseCode = "400", description = "Target is not a direct membership"),
    @ApiResponse(responseCode = "403", description = "Ship belongs to someone else"),
    @ApiResponse(responseCode = "409", description = "Stale owningOrgUnitVersion")
  })
  @PutMapping("/ships/{id}/org-unit")
  @PreAuthorize("isAuthenticated() and @hangarAccessPolicy.canEditShip(#id)")
  @Transactional
  public ShipDto changeMyShipOrgUnit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull ShipOrgUnitRequestDto request) {
    return shipMapper.toDto(
        hangarService.changeShipOrgUnit(
            userService.getUserIdFromJwt(jwt),
            id,
            request.orgUnitId(),
            request.owningOrgUnitVersion()));
  }

  /**
   * Changes the owning org unit of a selection of the calling user's ships to one of their direct
   * memberships or none, in one transaction (REQ-HANGAR-010).
   *
   * @param jwt caller's JWT; its {@code sub} claim must own every selected ship
   * @param request the selected ships and the new org unit
   * @return how many ships moved and how many already were in the target
   */
  @Operation(
      summary = "Change the org unit of several ships",
      description =
          "Moves a selection of the caller's ships into one of the caller's direct org units, or"
              + " out of every unit. Ships already there are skipped; an unknown or a foreign ship"
              + " refuses the whole selection.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed and skipped counts"),
    @ApiResponse(
        responseCode = "400",
        description = "Empty or oversized selection, or a target outside the direct memberships"),
    @ApiResponse(responseCode = "403", description = "A selected ship belongs to someone else"),
    @ApiResponse(responseCode = "404", description = "A selected ship does not exist")
  })
  @PostMapping("/ships/bulk-org-unit")
  @PreAuthorize(HANGAR_ACCESS)
  public ShipBulkOrgUnitResultDto bulkChangeMyShipsOrgUnit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @Valid @NotNull ShipBulkOrgUnitRequestDto request) {
    return hangarService.bulkChangeShipOrgUnit(
        userService.getUserIdFromJwt(jwt), request.shipIds(), request.orgUnitId());
  }

  /**
   * Deletes one of the calling user's ships. Mission-unit references are detached before delete.
   */
  @DeleteMapping("/ships/{id}")
  @PreAuthorize("isAuthenticated() and @hangarAccessPolicy.canEditShip(#id)")
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
  @GetMapping("/admin/users/{userId}/ships")
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
  @PostMapping("/admin/users/{userId}/ships")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional
  public ShipDto addUserShip(
      @PathVariable @NotNull UUID userId, @RequestBody @Valid ShipRequestDto shipRequest) {
    return shipMapper.toDto(hangarService.addShipByAdmin(userId, shipRequest));
  }

  /** Admin-only: updates a target user's ship. */
  @PutMapping("/admin/users/{userId}/ships/{shipId}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional
  public ShipDto updateUserShip(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID shipId,
      @RequestBody @Valid @NotNull ShipRequestDto shipRequest) {
    return shipMapper.toDto(hangarService.updateShipByAdmin(userId, shipId, shipRequest));
  }

  /**
   * Admin-only: changes the owning org unit of a target user's ship to one of that user's direct
   * memberships or none, and tells the user (REQ-HANGAR-012).
   *
   * @param userId the ship's owner
   * @param shipId ship id
   * @param request the new org unit and the unit counter the admin last saw
   * @return the ship as it is after the change
   */
  @Operation(
      summary = "Change the org unit of a member's ship",
      description =
          "Moves a member's ship into one of the member's direct org units, or out of every unit."
              + " Guarded by the ship's owningOrgUnitVersion only; the member is notified.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Ship as it is after the change"),
    @ApiResponse(
        responseCode = "400",
        description = "Target is not a direct membership of the member"),
    @ApiResponse(responseCode = "403", description = "Not an admin, or ship of another member"),
    @ApiResponse(responseCode = "409", description = "Stale owningOrgUnitVersion")
  })
  @PutMapping("/admin/users/{userId}/ships/{shipId}/org-unit")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional
  public ShipDto changeUserShipOrgUnit(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID shipId,
      @RequestBody @Valid @NotNull ShipOrgUnitRequestDto request) {
    return shipMapper.toDto(
        hangarService.changeShipOrgUnitByAdmin(
            userId, shipId, request.orgUnitId(), request.owningOrgUnitVersion()));
  }

  /**
   * Admin-only: changes the owning org unit of a selection of a target user's ships in one
   * transaction, and tells the user once (REQ-HANGAR-012).
   *
   * @param userId the ships' owner
   * @param request the selected ships and the new org unit
   * @return how many ships moved and how many already were in the target
   */
  @Operation(
      summary = "Change the org unit of several ships of a member",
      description =
          "Moves a selection of a member's ships into one of the member's direct org units, or"
              + " out of every unit. Ships already there are skipped; an unknown ship or one of"
              + " another member refuses the whole selection. The member is notified once.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Changed and skipped counts"),
    @ApiResponse(
        responseCode = "400",
        description = "Empty or oversized selection, or a target outside the direct memberships"),
    @ApiResponse(responseCode = "403", description = "Not an admin, or ship of another member"),
    @ApiResponse(responseCode = "404", description = "A selected ship does not exist")
  })
  @PostMapping("/admin/users/{userId}/ships/bulk-org-unit")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public ShipBulkOrgUnitResultDto bulkChangeUserShipsOrgUnit(
      @PathVariable @NotNull UUID userId,
      @RequestBody @Valid @NotNull ShipBulkOrgUnitRequestDto request) {
    return hangarService.bulkChangeShipOrgUnitByAdmin(
        userId, request.shipIds(), request.orgUnitId());
  }

  /** Admin-only: deletes a target user's ship. */
  @DeleteMapping("/admin/users/{userId}/ships/{shipId}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteUserShip(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID shipId) {
    hangarService.deleteShipByAdmin(userId, shipId);
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
  @PreAuthorize(HANGAR_ACCESS)
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
