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
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.dto.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SetHomeLocationResponseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronShipOverviewDto;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * REST surface for the personal hangar (own ships), the squadron-wide overview, the admin per-user
 * hangar, and the third-party ship-export JSON import (CCU Game Fleetview / HangarXPLOR Shiplist /
 * Fleetyards / StarJump FleetViewer "Hangar Link").
 *
 * <p>{@code /my-ships} reads the calling user's JWT to derive the owner id — never accepts it from
 * the URL — so a caller cannot view another user's hangar via this endpoint. The admin-only {@code
 * /users/{userId}/ships} surface takes the user id from the path explicitly and is gated by {@code
 * hasRole('ADMIN')}. The {@code /squadron-overview} endpoint shapes its response based on the
 * caller's role: only ADMIN/OFFICER see the per-ship owner details, every other authenticated
 * caller gets just the aggregated counts.
 *
 * <p>REQ-SEC-052: the class-level {@code @PreAuthorize("isAuthenticated()")} is the floor, not the
 * ceiling — it is stated here so an endpoint added later inherits it rather than relying on a URL
 * matcher elsewhere being right, and a method-level gate still wins where one is present.
 *
 * <p><b>It is weaker than the URL rule above it, and that is not a licence to delete either.</b>
 * The chain gates {@code /api/v1/hangar/**} on {@code hasAnyAuthority(HANGAR_READ, HANGAR_WRITE,
 * ROLE_ADMIN)}, which every request must pass as well — the two are ANDed. Reading this annotation
 * as the whole rule and removing the matcher as "redundant" would widen the surface from that set
 * to any authenticated caller.
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

  /**
   * One server-side page of the calling user's own ships (REQ-HANGAR-002). The page is ordered by
   * the rich personal-hangar comparator (manufacturer, ship type, insurance tier/amount, location,
   * fitted, name) entirely in the repository, so the order — and the optional {@code search} filter
   * — span the user's whole fleet rather than a single fetched page. There is no caller-supplied
   * {@code sort}: the ordering is fixed and includes a computed insurance-tier bucket that no
   * column {@code Sort} could express, so the request carries page/size/search only.
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
    return PageResponse.of(p.map(shipMapper::toDto));
  }

  /**
   * Per-ship-type aggregated count across the squadron. Admins and officers additionally see the
   * per-ship owner / location / fitted breakdown; everyone else sees only the totals — the
   * role-driven shaping happens at the HTTP boundary so the service stays free of {@code
   * SecurityContextHolder} reads (the ArchUnit rule). The optional {@code search} term filters the
   * ship types server-side (case-insensitive contains on ship-type or manufacturer name), so a
   * filtered result is still correctly paginated across the whole scoped fleet (REQ-HANGAR-001).
   *
   * @param page zero-based page index
   * @param size page size
   * @param sort sort parameter ({@code shipType.name} only)
   * @param search optional ship-type/manufacturer name filter; blank means no filter
   * @param authentication caller's authentication, used for the role-driven response shaping
   * @return paged overview DTOs
   */
  @GetMapping("/squadron-overview")
  @Transactional(readOnly = true)
  public PageResponse<SquadronShipOverviewDto> getSquadronOverview(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String search,
      Authentication authentication) {
    // Role-based shaping of the response is decided HERE, at the HTTP boundary, so
    // the service stays pure business logic and does not need to read
    // SecurityContextHolder itself (architecture rule enforced by ArchitectureTest).
    boolean includeOwnerDetails =
        authentication != null
            && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_ADMIN".equals(role) || "ROLE_OFFICER".equals(role));
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

  // Admin endpoints

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
   * Imports a ship-export JSON file (CCU Game Fleetview, HangarXPLOR Shiplist, Fleetyards or
   * StarJump FleetViewer / "Hangar Link" — the format is auto-detected from the payload shape).
   * Parses the file via {@code HangarImportService} and creates only the missing rows so existing
   * hangar contents are never lost or duplicated. The caller's JWT is the owner of the new rows.
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
   * Legacy path for the ship-import endpoint, kept for one year so existing automation does not
   * break. Delegates to the same service as {@link #importShips(Jwt, MultipartFile)}; the response
   * is identical. New clients should target {@code /api/v1/hangar/import/ships} which is
   * format-neutral (the original {@code /import/fleetview} name predates HangarXPLOR support).
   *
   * @param jwt caller's JWT — its {@code sub} claim becomes the new rows' owner id
   * @param file uploaded JSON file
   * @return import summary (created / skipped / duplicate counts plus the unmatched-ship list)
   * @deprecated use {@link #importShips(Jwt, MultipartFile)} via {@code
   *     /api/v1/hangar/import/ships} instead — the {@code Sunset} and {@code Link} response headers
   *     carry the same hint.
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
