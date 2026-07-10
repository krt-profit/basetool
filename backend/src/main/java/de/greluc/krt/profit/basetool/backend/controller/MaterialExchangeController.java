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
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeItemReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.MaterialExchangeService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * REST API for the Materialbörse — the org-wide material-exchange trade board of Flotte &amp;
 * Logistik (REQ-MARKET-001…). The whole surface is gated on {@code KRT_MEMBER} (decision D2:
 * authenticated-but-roleless guests do not see the internal trade board); per-offer ownership and
 * the interessenten-anonymity redaction are enforced in {@link MaterialExchangeService}.
 */
@RestController
@RequestMapping("/api/v1/material-exchange")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.KRT_MEMBER + "')")
@Tag(name = "Material Exchange", description = "The Materialbörse trade board (Flotte & Logistik).")
@SecurityRequirement(name = "bearerAuth")
public class MaterialExchangeController {

  private final MaterialExchangeService service;

  /**
   * Returns a page of the board — all offers, or the caller's own offers — with the toolbar filters
   * and sort applied.
   *
   * @param tab {@code "mein"} for "Meine Angebote", anything else for "Alle Angebote".
   * @param q a search fragment matched against material and owner, or {@code null}.
   * @param minQuality the inclusive minimum quality 0–1000, or {@code null}.
   * @param minAmount the inclusive minimum quantity in SCU, or {@code null}.
   * @param sort the sort key ({@code qual} / {@code menge} / {@code mat} / {@code neu}).
   * @param page the zero-based page index.
   * @param size the page size.
   * @return the matching page of offers.
   */
  @GetMapping("/offers")
  @Operation(summary = "List Materialbörse offers (board), filtered and sorted.")
  public PageResponse<MaterialExchangeOfferDto> board(
      @RequestParam(required = false) String tab,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer minQuality,
      @RequestParam(required = false) Double minAmount,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.board(tab, q, minQuality, minAmount, sort, page, size);
  }

  /**
   * Returns the board tab counts (all active offers / the caller's own active offers).
   *
   * @return the tab counts.
   */
  @GetMapping("/counts")
  @Operation(summary = "Materialbörse board tab counts.")
  public MaterialExchangeCountsDto counts() {
    return service.counts();
  }

  /**
   * Returns one offer for the detail pane (interessenten names only if the caller is the owner).
   *
   * @param id the offer id.
   * @return the offer detail.
   */
  @GetMapping("/offers/{id}")
  @Operation(summary = "Get one Materialbörse offer detail.")
  public MaterialExchangeOfferDto detail(@PathVariable UUID id) {
    return service.detail(id);
  }

  /**
   * Releases one of the caller's own Lager rows to the board ("Material anbieten" / "Für Börse
   * freigeben").
   *
   * @param request the item id and the trade remark.
   * @return the resulting offer detail.
   */
  @PostMapping("/offers")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Release a Lager row to the Materialbörse.")
  public MaterialExchangeOfferDto release(
      @Valid @RequestBody MaterialExchangeReleaseRequest request) {
    return service.release(request);
  }

  /**
   * Lists a craftable item on the board ("Item anbieten", #1185). Unlike a material release the
   * caller supplies the blueprint product and the quantity; only items an active blueprint produces
   * are accepted.
   *
   * @param request the blueprint product key, the whole-piece quantity and the trade remark.
   * @return the resulting offer detail.
   */
  @PostMapping("/item-offers")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "List a craftable item on the Materialbörse.")
  public MaterialExchangeOfferDto releaseItem(
      @Valid @RequestBody MaterialExchangeItemReleaseRequest request) {
    return service.releaseItem(request);
  }

  /**
   * Edits an offer's offered quantity and trade remark ("Angebot bearbeiten"). Owner-only;
   * version-guarded.
   *
   * @param id the offer id.
   * @param request the new offered amount, remark and last-seen version.
   * @return the updated offer detail.
   */
  @PutMapping("/offers/{id}/remark")
  @Operation(summary = "Edit a Materialbörse offer (offered amount and remark).")
  public MaterialExchangeOfferDto updateOffer(
      @PathVariable UUID id, @Valid @RequestBody MaterialExchangeOfferUpdateRequest request) {
    return service.updateOffer(id, request);
  }

  /**
   * Deactivates an offer by id ("Angebot deaktivieren"). Owner-only.
   *
   * @param id the offer id.
   * @return the resulting offer detail.
   */
  @PostMapping("/offers/{id}/deactivate")
  @Operation(summary = "Deactivate a Materialbörse offer.")
  public MaterialExchangeOfferDto deactivate(@PathVariable UUID id) {
    return service.deactivate(id);
  }

  /**
   * Deactivates the active offer for a Lager row (un-checking "Für Börse freigeben"). Owner-only.
   *
   * @param inventoryItemId the Lager row whose active offer to take off the board.
   * @return the resulting offer detail.
   */
  @PostMapping("/items/{inventoryItemId}/deactivate")
  @Operation(summary = "Deactivate the active offer for a Lager row.")
  public MaterialExchangeOfferDto deactivateForItem(@PathVariable UUID inventoryItemId) {
    return service.deactivateForItem(inventoryItemId);
  }

  /**
   * Registers the caller's interest in an offer ("Interesse anmelden"). Idempotent.
   *
   * @param id the offer id.
   * @return the resulting offer detail.
   */
  @PostMapping("/offers/{id}/interest")
  @Operation(summary = "Register interest in a Materialbörse offer.")
  public MaterialExchangeOfferDto registerInterest(@PathVariable UUID id) {
    return service.registerInterest(id);
  }

  /**
   * Withdraws the caller's interest from an offer ("Interesse zurückziehen"). Idempotent.
   *
   * @param id the offer id.
   * @return the resulting offer detail.
   */
  @DeleteMapping("/offers/{id}/interest")
  @Operation(summary = "Withdraw interest from a Materialbörse offer.")
  public MaterialExchangeOfferDto withdrawInterest(@PathVariable UUID id) {
    return service.withdrawInterest(id);
  }

  /**
   * Returns which of the given Lager rows currently carry an active board offer — the "Auf Börse"
   * status flags for a batch of Mein-Lager leaf rows (no N+1).
   *
   * @param ids the Lager rows to check (repeated {@code ids} query params).
   * @return the subset that have an active offer.
   */
  @GetMapping("/released-item-ids")
  @Operation(summary = "Which of the given Lager rows currently carry an active offer.")
  public List<UUID> releasedItemIds(@RequestParam(name = "ids", required = false) List<UUID> ids) {
    return ids == null ? List.of() : List.copyOf(service.releasedInventoryItemIds(ids));
  }

  /**
   * Returns the caller's own Lager rows eligible for release, for the "Material anbieten" item
   * picker.
   *
   * @param q a material-name fragment, or {@code null} for the caller's whole stock.
   * @return the caller's releasable items.
   */
  @GetMapping("/releasable-items")
  @Operation(summary = "List the caller's own Lager rows eligible for release.")
  public List<MaterialExchangeReleasableItemDto> releasableItems(
      @RequestParam(required = false) String q) {
    return service.myReleasableItems(q);
  }
}
