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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin side of the P4K shops (REQ-P4K-042, REQ-P4K-043, ADR-0347): lists the shops with their
 * UEX terminal links, finds terminals to link to, and sets, clears or releases a link by hand.
 * Every link change records one {@code CATALOGUE} audit event (REQ-P4K-046).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShopAdminService {

  /** The most terminals a terminal search returns. */
  static final int TERMINAL_OPTIONS = 20;

  private final ShopRepository shopRepository;
  private final TerminalRepository terminalRepository;
  private final StarmapObjectRepository objectRepository;
  private final StarmapPlaceRows placeRows;
  private final AuditRecorder auditRecorder;
  private final EntityManager entityManager;

  /**
   * Pages the shops with their links.
   *
   * @param starSystem the star system to show, or {@code null} for all
   * @param query a fragment of the entity or registry name, or {@code null}
   * @param unlinkedOnly whether only shops without a terminal are listed
   * @param pageable the page and its sort
   * @return the page of shops
   */
  @NotNull
  public Page<ShopAdminDto> shops(
      @Nullable String starSystem,
      @Nullable String query,
      boolean unlinkedOnly,
      @NotNull Pageable pageable) {
    Page<Shop> page =
        shopRepository.search(
            lower(starSystem),
            query == null ? null : LikePatterns.contains(query.toLowerCase(Locale.ROOT)),
            unlinkedOnly,
            pageable);
    Map<UUID, String> terminals = terminalNames(page.getContent());
    Map<UUID, String> places = placeNames(page.getContent());
    return page.map(s -> toDto(s, terminals, places));
  }

  /**
   * Finds the UEX terminals whose name contains a fragment, in name order.
   *
   * @param query the fragment
   * @return at most {@value #TERMINAL_OPTIONS} terminals
   */
  @NotNull
  public List<ShopTerminalOptionDto> terminals(@NotNull String query) {
    String fragment = query.strip().toLowerCase(Locale.ROOT);
    return placeRows.rows(StarmapPlaceKind.TERMINAL).stream()
        .filter(r -> r.name() != null && r.name().toLowerCase(Locale.ROOT).contains(fragment))
        .sorted(Comparator.comparing(StarmapPlaceRow::name, String.CASE_INSENSITIVE_ORDER))
        .limit(TERMINAL_OPTIONS)
        .map(r -> new ShopTerminalOptionDto(r.id(), r.name(), r.starSystem()))
        .toList();
  }

  /**
   * Sets, clears or releases a shop's terminal link by hand (REQ-P4K-043), when the shop still
   * holds the link the admin saw.
   *
   * @param id the shop
   * @param action what the admin does
   * @param terminalId the terminal for {@link StarmapLinkAction#SET}, else ignored
   * @param expectedTerminalId the link the admin saw
   * @param expectedManual the manual flag the admin saw
   * @return the shop after the change
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the shop or the
   *     terminal does not exist
   * @throws BadRequestException when {@code SET} names no terminal
   * @throws ObjectOptimisticLockingFailureException when the shop's link changed meanwhile
   */
  @Transactional
  @NotNull
  public ShopAdminDto changeLink(
      @NotNull UUID id,
      @NotNull StarmapLinkAction action,
      @Nullable UUID terminalId,
      @Nullable UUID expectedTerminalId,
      boolean expectedManual) {
    Shop shop = Entities.require(shopRepository.findById(id), "Shop not found");
    UUID previous = shop.getTerminalId();
    UUID target =
        switch (action) {
          case SET -> {
            if (terminalId == null) {
              throw new BadRequestException("A link names the terminal.");
            }
            yield Entities.require(terminalRepository.findById(terminalId), "Terminal not found")
                .getId();
          }
          case CLEAR -> null;
          case RELEASE -> previous;
        };
    if (shopRepository.manualLinkTerminal(
            id, target, action != StarmapLinkAction.RELEASE, expectedTerminalId, expectedManual)
        != 1) {
      throw new ObjectOptimisticLockingFailureException(Shop.class, id);
    }
    auditRecorder.record(
        switch (action) {
          case SET -> AuditEventType.CATALOGUE_SHOP_LINK_SET;
          case CLEAR -> AuditEventType.CATALOGUE_SHOP_LINK_CLEARED;
          case RELEASE -> AuditEventType.CATALOGUE_SHOP_LINK_RELEASED;
        },
        id,
        shop.getEntityName(),
        null,
        AuditDetails.of("terminalId", target).with("previousTerminalId", previous));
    entityManager.refresh(shop);
    List<Shop> one = List.of(shop);
    return toDto(shop, terminalNames(one), placeNames(one));
  }

  /**
   * Reads the names of the terminals a page of shops links to.
   *
   * @param shops the shops
   * @return the terminal names by id
   */
  @NotNull
  private Map<UUID, String> terminalNames(@NotNull List<Shop> shops) {
    List<UUID> ids = shops.stream().map(Shop::getTerminalId).filter(Objects::nonNull).toList();
    return ids.isEmpty()
        ? Map.of()
        : terminalRepository.findAllById(ids).stream()
            .filter(t -> t.getName() != null)
            .collect(Collectors.toMap(Terminal::getId, Terminal::getName));
  }

  /**
   * Reads the English names of the starmap objects a page of shops sits at.
   *
   * @param shops the shops
   * @return the place names by object id
   */
  @NotNull
  private Map<UUID, String> placeNames(@NotNull List<Shop> shops) {
    List<UUID> ids = shops.stream().map(Shop::getStarmapObjectId).filter(Objects::nonNull).toList();
    return ids.isEmpty()
        ? Map.of()
        : objectRepository.findByIdIn(ids).stream()
            .filter(o -> o.getName() != null)
            .collect(Collectors.toMap(StarmapObject::getId, StarmapObject::getName));
  }

  /**
   * Maps a shop.
   *
   * @param s the shop
   * @param terminals the terminal names by id
   * @param places the place names by object id
   * @return its DTO
   */
  @NotNull
  private static ShopAdminDto toDto(
      @NotNull Shop s, @NotNull Map<UUID, String> terminals, @NotNull Map<UUID, String> places) {
    return new ShopAdminDto(
        s.getId(),
        s.getEntityName(),
        s.getRegistryName(),
        s.getStarSystem(),
        s.getKind(),
        s.getBrand(),
        s.isCommodityExchange(),
        s.hasItemList(),
        s.getStarmapObjectId() == null ? null : places.get(s.getStarmapObjectId()),
        s.getTerminalId(),
        s.getTerminalId() == null ? null : terminals.get(s.getTerminalId()),
        s.isTerminalLinkManual(),
        s.isP4kRetired());
  }

  /**
   * Lower-cases and strips a filter.
   *
   * @param value the filter, or {@code null}
   * @return the lower-case filter, or {@code null} when blank
   */
  @Nullable
  private static String lower(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip().toLowerCase(Locale.ROOT);
  }
}
