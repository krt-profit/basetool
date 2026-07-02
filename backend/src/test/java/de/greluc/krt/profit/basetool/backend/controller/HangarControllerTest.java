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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.multipart.MultipartFile;

/**
 * Pure-Mockito unit tests for {@link HangarController}. Two non-pass-through behaviours are pinned
 * in detail: (1) the {@code /my-ships} family derives the owner from the JWT via {@code
 * UserService.getUserIdFromJwt} — the test confirms that derived id, never a URL-supplied one,
 * reaches the service (the controller's core data-isolation guarantee for personal-hangar
 * endpoints); (2) {@code /squadron-overview} shapes its response payload based on the caller's role
 * at the HTTP boundary, so the service stays free of {@code SecurityContextHolder} reads (the
 * ArchUnit rule). The role-driven branch is exercised for ADMIN, OFFICER, plain authenticated user,
 * and anonymous — only the first two pass {@code includeOwnerDetails=true} downstream.
 */
@ExtendWith(MockitoExtension.class)
class HangarControllerTest {

  @Mock private HangarService hangarService;
  @Mock private HangarImportService hangarImportService;
  @Mock private UserService userService;
  @Mock private ShipMapper shipMapper;

  @InjectMocks private HangarController controller;

  private static ShipDto shipDto(String name) {
    return new ShipDto(UUID.randomUUID(), name, null, "LTI", null, true, null, null, 1L);
  }

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

  // ── GET /my-ships ─────────────────────────────────────────────────────

  @Test
  void getMyShips_resolvesOwnerFromJwt_andWrapsPageThroughMapper() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    Ship s1 = new Ship();
    Ship s2 = new Ship();
    ShipDto d1 = shipDto("Aurora");
    ShipDto d2 = shipDto("Cutlass");
    Page<Ship> page = new PageImpl<>(List.of(s1, s2), PageRequest.of(0, 20), 2);
    when(hangarService.getMyShipsFiltered(eq(ownerId), isNull(), any(Pageable.class)))
        .thenReturn(page);
    when(shipMapper.toDto(s1)).thenReturn(d1);
    when(shipMapper.toDto(s2)).thenReturn(d2);

    PageResponse<ShipDto> result = controller.getMyShips(jwt, 0, 20, null);

    // The owner id must come from UserService.getUserIdFromJwt, NEVER a URL parameter — this is
    // the personal-hangar data-isolation guarantee. The captured Pageable plus the JWT-derived
    // owner id together prove the controller doesn't accept caller-supplied owners.
    ArgumentCaptor<UUID> ownerCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(hangarService).getMyShipsFiltered(ownerCaptor.capture(), isNull(), any(Pageable.class));
    assertThat(ownerCaptor.getValue()).isEqualTo(ownerId);
    assertThat(result.content()).containsExactly(d1, d2);
    assertThat(result.totalElements()).isEqualTo(2L);
  }

  @Test
  void getMyShips_forwardsSearchTermToService() {
    // covers REQ-HANGAR-002 — the personal-hangar text filter travels from the HTTP boundary into
    // the service untouched, so a filtered result is ordered + paginated across the whole fleet
    // rather than the rows of a single client-fetched page.
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    Page<Ship> page = new PageImpl<>(List.of());
    when(hangarService.getMyShipsFiltered(eq(ownerId), eq("Cutlass"), any(Pageable.class)))
        .thenReturn(page);

    controller.getMyShips(jwt, 0, 50, "Cutlass");

    verify(hangarService).getMyShipsFiltered(eq(ownerId), eq("Cutlass"), any(Pageable.class));
  }

  // ── GET /ships (admin/officer wide read) ─────────────────────────────

  @Test
  void getAllShips_wrapsPageThroughMapper() {
    Ship s = new Ship();
    ShipDto d = shipDto("Carrack");
    Page<Ship> page =
        new PageImpl<>(
            List.of(s), PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("name")), 1);
    when(hangarService.getAllShips(any(Pageable.class))).thenReturn(page);
    when(shipMapper.toDto(s)).thenReturn(d);

    PageResponse<ShipDto> result = controller.getAllShips(0, 20, "name,asc");

    assertThat(result.content()).containsExactly(d);
    assertThat(result.sort()).isNotEmpty();
    verify(hangarService).getAllShips(any(Pageable.class));
  }

  // ── GET /squadron-overview (role-shaped payload) ─────────────────────

  @Test
  void getSquadronOverview_whenCallerIsAdmin_includesOwnerDetails() {
    Authentication admin =
        new UsernamePasswordAuthenticationToken(
            "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), eq(true), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(0, 20, null, null, admin);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(true), isNull());
  }

  @Test
  void getSquadronOverview_whenCallerIsOfficer_includesOwnerDetails() {
    Authentication officer =
        new UsernamePasswordAuthenticationToken(
            "bob", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OFFICER")));
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), eq(true), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(0, 20, null, null, officer);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(true), isNull());
  }

  @Test
  void getSquadronOverview_whenCallerIsPlainUser_hidesOwnerDetails() {
    Authentication user =
        new UsernamePasswordAuthenticationToken(
            "carol", "n/a", List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), eq(false), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(0, 20, null, null, user);

    // Plain authenticated callers see only the aggregated counts — the per-ship owner is hidden
    // at the HTTP boundary so the service doesn't have to read SecurityContextHolder.
    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(false), isNull());
  }

  @Test
  void getSquadronOverview_whenCallerIsAnonymous_hidesOwnerDetails() {
    Authentication anon =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), eq(false), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(0, 20, null, null, anon);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(false), isNull());
  }

  @Test
  void getSquadronOverview_whenAuthenticationIsNull_hidesOwnerDetails() {
    // Defensive branch — Spring should always pass a non-null Authentication, but the controller
    // documents the null-check explicitly and the test pins it so a future cleanup cannot delete
    // the guard without surfacing here.
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), eq(false), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(0, 20, null, null, null);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(false), isNull());
  }

  // ── POST /ships ───────────────────────────────────────────────────────

  @Test
  void addShip_forwardsJwtDerivedOwnerToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    ShipRequestDto request =
        new ShipRequestDto("Cutlass", UUID.randomUUID(), "LTI", null, true, 0L, null);
    Ship created = new Ship();
    ShipDto dto = shipDto("Cutlass");
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(hangarService.addShip(ownerId, request)).thenReturn(created);
    when(shipMapper.toDto(created)).thenReturn(dto);

    ShipDto result = controller.addShip(jwt, request);

    assertThat(result).isSameAs(dto);
    verify(hangarService).addShip(ownerId, request);
  }

  // ── PUT /ships/{id} ───────────────────────────────────────────────────

  @Test
  void updateMyShip_forwardsJwtDerivedOwnerAndShipIdToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    ShipRequestDto request =
        new ShipRequestDto("Cutlass II", UUID.randomUUID(), "1", null, false, 5L, null);
    Ship updated = new Ship();
    ShipDto dto = shipDto("Cutlass II");
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(hangarService.updateShip(ownerId, shipId, request)).thenReturn(updated);
    when(shipMapper.toDto(updated)).thenReturn(dto);

    ShipDto result = controller.updateMyShip(jwt, shipId, request);

    assertThat(result).isSameAs(dto);
    verify(hangarService).updateShip(ownerId, shipId, request);
  }

  // ── DELETE /ships/{id} ────────────────────────────────────────────────

  @Test
  void deleteMyShip_forwardsJwtDerivedOwnerAndShipIdToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);

    controller.deleteMyShip(jwt, shipId);

    verify(hangarService).deleteShip(ownerId, shipId);
  }

  // ── DELETE /ships ─────────────────────────────────────────────────────

  @Test
  void deleteAllMyShips_returns204_andUsesJwtDerivedOwner() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);

    var response = controller.deleteAllMyShips(jwt);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    verify(hangarService).deleteAllShipsForUser(ownerId);
  }

  // ── GET /users/{userId}/ships (admin) ─────────────────────────────────

  @Test
  void getUserShips_admin_usesPathUserId_notJwt() {
    UUID targetUser = UUID.randomUUID();
    Ship s = new Ship();
    ShipDto d = shipDto("Carrack");
    Page<Ship> page = new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1);
    when(hangarService.getMyShips(eq(targetUser), any(Pageable.class))).thenReturn(page);
    when(shipMapper.toDto(s)).thenReturn(d);

    PageResponse<ShipDto> result = controller.getUserShips(targetUser, 0, 20, null);

    // Admin endpoint MUST take the user id from the path — the test would catch a regression that
    // accidentally reads the calling admin's own JWT id instead.
    verify(hangarService).getMyShips(eq(targetUser), any(Pageable.class));
    verify(userService, never()).getUserIdFromJwt(any());
    assertThat(result.content()).containsExactly(d);
  }

  // ── POST /users/{userId}/ships (admin) ───────────────────────────────

  @Test
  void addUserShip_admin_passesPathUserIdAndRequestToService() {
    UUID targetUser = UUID.randomUUID();
    ShipRequestDto request =
        new ShipRequestDto("Cutlass", UUID.randomUUID(), "LTI", null, true, 0L, null);
    Ship created = new Ship();
    ShipDto dto = shipDto("Cutlass");
    when(hangarService.addShip(targetUser, request)).thenReturn(created);
    when(shipMapper.toDto(created)).thenReturn(dto);

    ShipDto result = controller.addUserShip(targetUser, request);

    assertThat(result).isSameAs(dto);
    verify(hangarService).addShip(targetUser, request);
  }

  // ── PUT /users/{userId}/ships/{shipId} ───────────────────────────────

  @Test
  void updateUserShip_admin_passesPathUserIdShipIdAndRequest() {
    UUID targetUser = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();
    ShipRequestDto request =
        new ShipRequestDto("Cutlass", UUID.randomUUID(), "LTI", null, true, 1L, null);
    Ship updated = new Ship();
    ShipDto dto = shipDto("Cutlass");
    when(hangarService.updateShip(targetUser, shipId, request)).thenReturn(updated);
    when(shipMapper.toDto(updated)).thenReturn(dto);

    ShipDto result = controller.updateUserShip(targetUser, shipId, request);

    assertThat(result).isSameAs(dto);
    verify(hangarService).updateShip(targetUser, shipId, request);
  }

  // ── DELETE /users/{userId}/ships/{shipId} ────────────────────────────

  @Test
  void deleteUserShip_admin_passesPathUserIdAndShipId() {
    UUID targetUser = UUID.randomUUID();
    UUID shipId = UUID.randomUUID();

    controller.deleteUserShip(targetUser, shipId);

    verify(hangarService).deleteShip(targetUser, shipId);
  }

  // ── POST /import/ships ────────────────────────────────────────────────

  @Test
  void importShips_forwardsJwtDerivedOwnerAndFileToService() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "ships.json", "application/json", "[]".getBytes());
    FleetviewImportResponseDto response =
        new FleetviewImportResponseDto(3, 0, 0, List.of(), List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(hangarImportService.importShips(ownerId, file)).thenReturn(response);

    FleetviewImportResponseDto result = controller.importShips(jwt, file);

    assertThat(result).isSameAs(response);
    verify(hangarImportService).importShips(ownerId, file);
  }

  // ── POST /import/fleetview (deprecated) ──────────────────────────────

  // Deliberately invokes the deprecated-for-removal HangarController.importFleetview to pin its
  // grace-period routing; the [removal] warning is expected and unavoidable when testing the
  // deprecated endpoint directly.
  @Test
  @SuppressWarnings("removal")
  void importFleetview_deprecatedPath_routesToSameService() {
    // The deprecated endpoint MUST end up at the same HangarImportService.importShips so the
    // grace-period clients keep getting the modern import behaviour (UEX-aware ship matching,
    // duplicate detection). A regression that sends the deprecated path to a different code
    // path would silently change behaviour for existing automation.
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    MultipartFile file =
        new MockMultipartFile("file", "fleetview.json", "application/json", "[]".getBytes());
    FleetviewImportResponseDto response =
        new FleetviewImportResponseDto(2, 1, 0, List.of("oldship"), List.of());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(hangarImportService.importShips(ownerId, file)).thenReturn(response);

    FleetviewImportResponseDto result = controller.importFleetview(jwt, file);

    assertThat(result).isSameAs(response);
    verify(hangarImportService).importShips(ownerId, file);
  }

  // ── POST /ships/reset-fitted ──────────────────────────────────────────

  @Test
  void resetAllFittedStatus_delegatesToService() {
    controller.resetAllFittedStatus();

    verify(hangarService).resetAllFittedStatus();
  }

  // ── POST /ships/home-location ─────────────────────────────────────────

  @Test
  void setHomeLocationForMyShips_forwardsJwtDerivedOwnerAndLocation_andWrapsCount() {
    Jwt jwt = jwt("alice-sub");
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(ownerId);
    when(hangarService.setHomeLocationForMyShips(ownerId, locationId)).thenReturn(4);

    SetHomeLocationResponseDto result =
        controller.setHomeLocationForMyShips(jwt, new SetHomeLocationRequestDto(locationId));

    // The owner is JWT-derived (never client-supplied); the controller wraps the affected-ship
    // count returned by the service.
    assertThat(result.updatedCount()).isEqualTo(4);
    verify(hangarService).setHomeLocationForMyShips(ownerId, locationId);
  }

  @Test
  void getSquadronOverview_paginationDefaults_neverFailIncludeFlagPropagation() {
    // Sanity-check: the role-decision branch must continue to feed includeOwnerDetails even
    // when the page/size/sort params arrive as null (defaults applied by PaginationUtil).
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), anyBoolean(), isNull()))
        .thenReturn(page);

    controller.getSquadronOverview(null, null, null, null, null);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(false), isNull());
  }

  @Test
  void getSquadronOverview_forwardsSearchTermToService() {
    // covers REQ-HANGAR-001 — the server-side ship-type filter travels from the HTTP boundary
    // into the service untouched, so a filtered result is paginated across the whole fleet.
    Page<SquadronShipOverviewDto> page = new PageImpl<>(List.of());
    when(hangarService.getSquadronOverview(any(Pageable.class), anyBoolean(), eq("Cutlass")))
        .thenReturn(page);

    controller.getSquadronOverview(0, 10, null, "Cutlass", null);

    verify(hangarService).getSquadronOverview(any(Pageable.class), eq(false), eq("Cutlass"));
  }
}
