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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Terminal;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Review links use current location context and the existing admin commands. */
class P4kLocationReviewActionsTest {

  @Test
  void aPlaceBecomesActionableAfterItsParentIsLinked() {
    StarmapPlaceRows placeRows = mock(StarmapPlaceRows.class);
    StarmapObjectRepository objects = mock(StarmapObjectRepository.class);
    StarmapAdminService admin = mock(StarmapAdminService.class);
    P4kStarmapReviewActions actions = new P4kStarmapReviewActions(placeRows, objects, admin);
    StarmapObject star = object("Stanton", "Star", null);
    StarmapObject planet = object("Hurston", "Planet", star.getId());
    StarmapObject depot = object("Depot", "Manmade", planet.getId());
    StarmapObject elsewhere = object("Depot", "Manmade", null);
    StarmapObject wrongType = object("Depot", "Planet", planet.getId());
    UUID placeId = UUID.randomUUID();
    UUID planetId = UUID.randomUUID();
    StarmapPlaceRow terminal = place(StarmapPlaceKind.TERMINAL, placeId, "Depot", "Hurston", null);
    when(placeRows.rows(StarmapPlaceKind.TERMINAL)).thenReturn(List.of(terminal));
    when(placeRows.rows(StarmapPlaceKind.PLANET))
        .thenReturn(List.of(place(StarmapPlaceKind.PLANET, planetId, "Hurston", null, null)))
        .thenReturn(
            List.of(place(StarmapPlaceKind.PLANET, planetId, "Hurston", null, planet.getId())));
    when(objects.findAll()).thenReturn(List.of(star, planet, depot, elsewhere, wrongType));
    P4kReviewCaseDto review =
        review(
            P4kKind.STARMAP_OBJECT,
            P4kStarmapLinker.caseGuid(terminal.kind(), terminal.id()),
            P4kReviewReason.STARMAP_LINK_NOT_FOUND,
            "terminal",
            "Depot",
            null);

    P4kReviewOptions blocked = actions.options(review, null);
    assertThat(blocked.readiness()).isEqualTo(P4kReviewReadiness.BLOCKED);
    assertThat(blocked.contextPath()).isEqualTo("Stanton / Hurston / Depot");
    assertThat(blocked.candidates()).isEmpty();

    P4kReviewOptions available = actions.options(review, null);
    assertThat(available.readiness()).isEqualTo(P4kReviewReadiness.ACTIONABLE);
    assertThat(available.candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.LINK_UEX_PLACE);
          assertThat(candidate.targetId()).isEqualTo(depot.getId());
          assertThat(candidate.name()).isEqualTo("Stanton / Hurston / Depot");
          assertThat(candidate.evidence())
              .containsExactlyInAnyOrder(
                  P4kReviewEvidence.SAME_TYPE,
                  P4kReviewEvidence.SAME_NAME,
                  P4kReviewEvidence.PARENT_LINK);
          actions.apply(review, candidate, UUID.randomUUID());
        });
    verify(admin)
        .changeLink(
            terminal.kind(), terminal.id(), StarmapLinkAction.SET, depot.getId(), null, false);
  }

  @Test
  void aShopOffersOnlyTerminalsAtItsPlaceAndShowsTheirUexIds() {
    ShopRepository shops = mock(ShopRepository.class);
    TerminalRepository terminals = mock(TerminalRepository.class);
    StarmapObjectRepository objects = mock(StarmapObjectRepository.class);
    P4kShopLinker linker = mock(P4kShopLinker.class);
    ShopAdminService admin = mock(ShopAdminService.class);
    P4kShopReviewActions actions =
        new P4kShopReviewActions(shops, terminals, objects, linker, admin);
    StarmapObject city = object("Lorville", "LandingZone", null);
    Shop shop = new Shop();
    shop.setId(UUID.randomUUID());
    shop.setRegistryName("Cargo Admin");
    Terminal terminal = new Terminal();
    terminal.setId(UUID.randomUUID());
    terminal.setIdTerminal(417);
    StarmapPlaceRow matching =
        place(StarmapPlaceKind.TERMINAL, terminal.getId(), "Cargo Admin - Lorville", null, null);
    StarmapPlaceRow wrongPlace =
        place(StarmapPlaceKind.TERMINAL, UUID.randomUUID(), "Cargo Admin - Orison", null, null);
    when(shops.findById(shop.getId())).thenReturn(Optional.of(shop));
    when(objects.findAll()).thenReturn(List.of(city));
    when(linker.terminalsByPlace())
        .thenReturn(
            Map.of(city.getId(), List.of(matching), UUID.randomUUID(), List.of(wrongPlace)));
    when(terminals.findAllById(List.of(terminal.getId()))).thenReturn(List.of(terminal));
    P4kReviewCaseDto review =
        review(
            P4kKind.SHOP,
            UUID.randomUUID(),
            P4kReviewReason.SHOP_LINK_NOT_FOUND,
            "SCShop_Cargo",
            "Cargo Admin",
            shop.getId());

    P4kReviewOptions blocked = actions.options(review, null);
    assertThat(blocked.readiness()).isEqualTo(P4kReviewReadiness.BLOCKED);
    assertThat(blocked.candidates()).isEmpty();

    shop.setStarmapObjectId(city.getId());
    P4kReviewOptions available = actions.options(review, null);
    assertThat(available.contextPath()).isEqualTo("Lorville");
    assertThat(available.readiness()).isEqualTo(P4kReviewReadiness.ACTIONABLE);
    assertThat(available.candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.LINK_UEX_TERMINAL);
          assertThat(candidate.targetId()).isEqualTo(terminal.getId());
          assertThat(candidate.uexIds()).containsExactly(417);
          assertThat(candidate.evidence())
              .containsExactly(P4kReviewEvidence.SAME_PLACE, P4kReviewEvidence.SHOP_NAME_MATCH);
          actions.apply(review, candidate, UUID.randomUUID());
        });
    verify(admin).changeLink(shop.getId(), StarmapLinkAction.SET, terminal.getId(), null, false);
  }

  @Test
  void starmapReadinessUsesOneTreeAndOneReadPerSourceTable() {
    StarmapPlaceRows rows = mock(StarmapPlaceRows.class);
    StarmapObjectRepository objects = mock(StarmapObjectRepository.class);
    P4kStarmapReviewActions actions =
        new P4kStarmapReviewActions(rows, objects, mock(StarmapAdminService.class));
    StarmapObject planet = object("Hurston", "Planet", null);
    StarmapObject depot = object("Depot", "Manmade", planet.getId());
    StarmapPlaceRow exact =
        place(StarmapPlaceKind.TERMINAL, UUID.randomUUID(), "Depot", "Hurston", null);
    StarmapPlaceRow unmatched =
        place(StarmapPlaceKind.TERMINAL, UUID.randomUUID(), "Warehouse", "Hurston", null);
    StarmapPlaceRow blocked =
        place(StarmapPlaceKind.TERMINAL, UUID.randomUUID(), "Station", "ArcCorp", null);
    when(rows.rows(StarmapPlaceKind.TERMINAL)).thenReturn(List.of(exact, unmatched, blocked));
    when(rows.rows(StarmapPlaceKind.PLANET))
        .thenReturn(
            List.of(
                place(
                    StarmapPlaceKind.PLANET, UUID.randomUUID(), "Hurston", null, planet.getId())));
    when(objects.findAll()).thenReturn(List.of(planet, depot));
    P4kReviewCaseSource a = starmapReview(exact);
    P4kReviewCaseSource b = starmapReview(unmatched);
    P4kReviewCaseSource c = starmapReview(blocked);

    assertThat(actions.summarize(List.of(a, b, c)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                a.review().id(),
                P4kReviewReadiness.ACTIONABLE,
                b.review().id(),
                P4kReviewReadiness.CHECK_REQUIRED,
                c.review().id(),
                P4kReviewReadiness.BLOCKED));
    verify(objects).findAll();
    verify(rows).rows(StarmapPlaceKind.TERMINAL);
    verify(rows).rows(StarmapPlaceKind.PLANET);
  }

  @Test
  void shopReadinessUsesOneShopPlaceAndTerminalBatch() {
    ShopRepository shops = mock(ShopRepository.class);
    TerminalRepository terminals = mock(TerminalRepository.class);
    StarmapObjectRepository objects = mock(StarmapObjectRepository.class);
    P4kShopLinker linker = mock(P4kShopLinker.class);
    P4kShopReviewActions actions =
        new P4kShopReviewActions(shops, terminals, objects, linker, mock(ShopAdminService.class));
    StarmapObject city = object("Lorville", "LandingZone", null);
    Shop exact = shop("Cargo Admin", city.getId());
    Shop unmatched = shop("Clothing", city.getId());
    Shop blocked = shop("Cargo Admin", null);
    List<UUID> ids = List.of(exact.getId(), unmatched.getId(), blocked.getId());
    when(shops.findAllById(ids)).thenReturn(List.of(exact, unmatched, blocked));
    when(objects.findByIdIn(List.of(city.getId()))).thenReturn(List.of(city));
    when(linker.terminalsByPlace())
        .thenReturn(
            Map.of(
                city.getId(),
                List.of(
                    place(
                        StarmapPlaceKind.TERMINAL,
                        UUID.randomUUID(),
                        "Cargo Admin - Lorville",
                        null,
                        null))));
    P4kReviewCaseSource a = shopReview(exact);
    P4kReviewCaseSource b = shopReview(unmatched);
    P4kReviewCaseSource c = shopReview(blocked);

    assertThat(actions.summarize(List.of(a, b, c)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                a.review().id(),
                P4kReviewReadiness.ACTIONABLE,
                b.review().id(),
                P4kReviewReadiness.CHECK_REQUIRED,
                c.review().id(),
                P4kReviewReadiness.BLOCKED));
    verify(shops).findAllById(ids);
    verify(objects).findByIdIn(List.of(city.getId()));
    verify(linker).terminalsByPlace();
    verifyNoInteractions(terminals);
  }

  private static P4kReviewCaseSource starmapReview(StarmapPlaceRow row) {
    return new P4kReviewCaseSource(
        review(
            P4kKind.STARMAP_OBJECT,
            P4kStarmapLinker.caseGuid(row.kind(), row.id()),
            P4kReviewReason.STARMAP_LINK_NOT_FOUND,
            row.kind().getTable(),
            row.name(),
            null),
        null);
  }

  private static P4kReviewCaseSource shopReview(Shop shop) {
    return new P4kReviewCaseSource(
        review(
            P4kKind.SHOP,
            UUID.randomUUID(),
            P4kReviewReason.SHOP_LINK_NOT_FOUND,
            "SCShop_Test",
            shop.getRegistryName(),
            shop.getId()),
        null);
  }

  private static Shop shop(String name, UUID placeId) {
    Shop shop = new Shop();
    shop.setId(UUID.randomUUID());
    shop.setRegistryName(name);
    shop.setStarmapObjectId(placeId);
    return shop;
  }

  private static StarmapPlaceRow place(
      StarmapPlaceKind kind, UUID id, String name, String planet, UUID linked) {
    return new StarmapPlaceRow(
        kind, id, name, "Stanton", planet, null, null, null, null, null, linked, false);
  }

  private static StarmapObject object(String name, String type, UUID parentId) {
    StarmapObject object = new StarmapObject();
    object.setId(UUID.randomUUID());
    object.setP4kUuid(UUID.randomUUID());
    object.setName(name);
    object.setType(type);
    object.setParentId(parentId);
    return object;
  }

  private static P4kReviewCaseDto review(
      P4kKind kind, UUID guid, P4kReviewReason reason, String className, String name, UUID rowId) {
    UUID runId = UUID.randomUUID();
    return new P4kReviewCaseDto(
        UUID.randomUUID(),
        0,
        kind,
        guid,
        reason,
        P4kReviewStatus.OPEN,
        className,
        name,
        rowId,
        null,
        null,
        runId,
        runId,
        null,
        null,
        null);
  }
}
