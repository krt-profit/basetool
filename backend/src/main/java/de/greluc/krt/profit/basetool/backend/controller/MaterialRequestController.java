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

import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeCountsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.MaterialRequestBoardService;
import de.greluc.krt.profit.basetool.backend.service.MaterialRequestService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Materialbörse Gesuche (wanted-listings) — the request-side board of Flotte &amp;
 * Logistik (REQ-MARKET-015…). Mirrors the offer surface ({@link MaterialExchangeController}): the
 * whole board is gated on {@code KRT_MEMBER}; the read endpoints delegate to {@link
 * MaterialRequestBoardService} (board / detail / counts + the supplier-anonymity redaction), the
 * write endpoints to {@link MaterialRequestService} (create / edit / deactivate / fulfilment-signal
 * lifecycle), which enforces per-request ownership. Unlike an offer, a request has no backing Lager
 * row, so there is no release picker / per-item deactivate surface.
 */
@RestController
@RequestMapping("/api/v1/material-requests")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
@Tag(
    name = "Material Requests",
    description = "The Materialbörse Gesuche (wanted-listings) board (Flotte & Logistik).")
@SecurityRequirement(name = "bearer-jwt")
public class MaterialRequestController {

  private final MaterialRequestService service;
  private final MaterialRequestBoardService boardService;

  /**
   * Returns a page of the board — all requests, or the caller's own requests — with the toolbar
   * filters and sort applied.
   *
   * @param tab {@code "mein"} for "Meine Gesuche", anything else for "Alle Gesuche".
   * @param q a search fragment matched against material/item name and owner, or {@code null}.
   * @param minQuality the inclusive minimum quality floor 0–1000, or {@code null}.
   * @param minAmount the inclusive minimum desired quantity, or {@code null}.
   * @param sort the sort key ({@code qual} / {@code menge} / {@code mat} / {@code neu}).
   * @param page the zero-based page index.
   * @param size the page size.
   * @return the matching page of requests.
   */
  @GetMapping
  @Operation(summary = "List Materialbörse requests (board), filtered and sorted.")
  public PageResponse<MaterialRequestDto> board(
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) Double minAmount,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return boardService.board(tab, q, minQuality, minAmount, sort, page, size);
  }

  /**
   * Returns the board tab counts (all active requests / the caller's own active requests).
   *
   * @return the tab counts.
   */
  @GetMapping("/counts")
  @Operation(summary = "Materialbörse Gesuche board tab counts.")
  public MaterialExchangeCountsDto counts() {
    return boardService.counts();
  }

  /**
   * Returns one request for the detail pane (supplier names only if the caller is the owner).
   *
   * @param id the request id.
   * @return the request detail.
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get one Materialbörse request detail.")
  public MaterialRequestDto detail(@PathVariable UUID id) {
    return boardService.detail(id);
  }

  /**
   * Posts a material wanted-listing to the board ("Material suchen").
   *
   * @param request the material id, desired amount, optional minimum quality and description.
   * @return the resulting request detail.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Post a material request (Gesuch) to the Materialbörse.")
  public MaterialRequestDto createMaterialRequest(
      @Valid @RequestBody MaterialRequestCreateRequest request) {
    return service.createMaterialRequest(request);
  }

  /**
   * Posts a craftable-item wanted-listing to the board ("Item suchen"). Only items an active
   * blueprint produces are accepted.
   *
   * @param request the blueprint product key, the whole-piece quantity, optional minimum quality
   *     and description.
   * @return the resulting request detail.
   */
  @PostMapping("/item")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Post a craftable-item request (Gesuch) to the Materialbörse.")
  public MaterialRequestDto createItemRequest(
      @Valid @RequestBody MaterialItemRequestCreateRequest request) {
    return service.createItemRequest(request);
  }

  /**
   * Edits a request's desired quantity, minimum quality and description ("Gesuch bearbeiten").
   * Owner-only; version-guarded.
   *
   * @param id the request id.
   * @param request the new desired amount, minimum quality, description and last-seen version.
   * @return the updated request detail.
   */
  @PutMapping("/{id}")
  @Operation(summary = "Edit a Materialbörse request (desired quantity, minimum quality, remark).")
  public MaterialRequestDto updateRequest(
      @PathVariable UUID id, @Valid @RequestBody MaterialRequestUpdateRequest request) {
    return service.updateRequest(id, request);
  }

  /**
   * Deactivates a request by id ("Gesuch zurückziehen"). Owner-only.
   *
   * @param id the request id.
   * @return the resulting request detail.
   */
  @PostMapping("/{id}/deactivate")
  @Operation(summary = "Deactivate a Materialbörse request.")
  public MaterialRequestDto deactivate(@PathVariable UUID id) {
    return service.deactivate(id);
  }

  /**
   * Signals that the caller can supply a request ("Ich kann liefern"). Idempotent.
   *
   * @param id the request id.
   * @return the resulting request detail.
   */
  @PostMapping("/{id}/interest")
  @Operation(summary = "Signal that you can supply a Materialbörse request.")
  public MaterialRequestDto signalFulfillment(@PathVariable UUID id) {
    return service.signalFulfillment(id);
  }

  /**
   * Withdraws the caller's fulfilment signal from a request ("doch nicht liefern"). Idempotent.
   *
   * @param id the request id.
   * @return the resulting request detail.
   */
  @DeleteMapping("/{id}/interest")
  @Operation(summary = "Withdraw a fulfilment signal from a Materialbörse request.")
  public MaterialRequestDto withdrawFulfillment(@PathVariable UUID id) {
    return service.withdrawFulfillment(id);
  }
}
