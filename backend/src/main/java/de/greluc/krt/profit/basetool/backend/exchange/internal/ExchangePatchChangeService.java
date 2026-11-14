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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLocationLinks.Links;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeHomeLocationChosenDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeHomeLocationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeItemRefDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeLocationDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangePatchChangeRefDto;
import de.greluc.krt.profit.basetool.backend.patchchange.api.HomeLocationChoiceRefusedException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeReader;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeView;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The patch change for an exchange client (REQ-PATCH-050…052): reads the latest patch change and
 * the member's home-location prompt, and makes the member's choice, each in the exchange's shapes.
 *
 * <p>A location carries its UEX place and starmap GUID as the stock feed names it, so a client can
 * match it with its own places.
 */
@Service
@RequiredArgsConstructor
public class ExchangePatchChangeService {

  private final PatchChangeExchangeReader reader;
  private final PatchChangeExchangeWriter writer;
  private final ExchangeLocationLinks locationLinks;

  /**
   * Reads the latest patch change and the member's home-location prompt.
   *
   * @param caller the client, installation and member
   * @return the patch change as the client sees it
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangePatchChangeDto read(@NotNull ExchangeCaller caller) {
    PatchChangeExchangeView view = reader.viewFor(caller.member());
    PatchChangeExchangeView.HomeLocation prompt = view.homeLocation();
    Map<UUID, Links> links = locationLinks.byId(locationIds(prompt));
    return new ExchangePatchChangeDto(
        view.current() == null ? null : current(view.current()),
        new ExchangePatchChangeDto.HomeLocation(
            prompt.state(),
            prompt.patchChangeId() == null || prompt.label() == null
                ? null
                : new ExchangePatchChangeRefDto(prompt.patchChangeId(), prompt.label()),
            prompt.liveAt(),
            prompt.chosen() == null ? null : homeLocation(prompt.chosen(), links),
            prompt.chosenAt(),
            prompt.offered().stream().map(place -> homeLocation(place, links)).toList(),
            prompt.preview().stream().map(entry -> preview(entry, links)).toList()));
  }

  /**
   * Makes the member's home-location choice and relocates the member, in the relocation's own
   * transaction, which the change feed attributes to the phase's patch change.
   *
   * @param caller the client, installation and member
   * @param locationId the chosen home location
   * @return the chosen home location and what moved
   * @throws HomeLocationChoiceRefusedException when no open phase asks the member, the member chose
   *     already or the location is not on offer
   */
  public @NotNull ExchangeHomeLocationChosenDto choose(
      @NotNull ExchangeCaller caller, @NotNull UUID locationId) {
    PatchChangeExchangeWriter.Chosen chosen =
        writer.chooseHomeLocation(caller.member(), locationId, caller.clientId());
    return new ExchangeHomeLocationChosenDto(
        homeLocation(chosen.location(), locationLinks.byId(Set.of(chosen.location().id()))),
        chosen.rows(),
        chosen.ships());
  }

  /**
   * Describes the latest patch change.
   *
   * @param current the patch change
   * @return its exchange shape
   */
  private static @NotNull ExchangePatchChangeDto.Current current(
      @NotNull PatchChangeExchangeView.Current current) {
    return new ExchangePatchChangeDto.Current(
        current.id(),
        current.label(),
        current.kind(),
        current.liveAt(),
        new ExchangePatchChangeDto.Options(
            current.wipeGroups().stream()
                .map(group ->
                    new ExchangePatchChangeDto.WipeGroup(
                        group.group(),
                        group.selected(),
                        group.types().stream()
                            .map(type ->
                                new ExchangePatchChangeDto.WipeGroupType(
                                    type.source(), type.value()))
                            .toList()))
                .toList(),
            current.wipeExceptions().stream()
                .map(exception ->
                    new ExchangePatchChangeDto.WipeException(
                        new ExchangeItemRefDto(exception.id().toString(), exception.name()),
                        exception.target(),
                        exception.mode()))
                .toList(),
            current.cancelRefineryOrders(),
            current.currencyWipe(),
            current.homeLocationPrompt()));
  }

  /**
   * Describes one location's preview.
   *
   * @param entry the preview
   * @param links the UEX place and GUID by location
   * @return its exchange shape
   */
  private static @NotNull ExchangePatchChangeDto.Preview preview(
      @NotNull PatchChangeExchangeView.Preview entry, @NotNull Map<UUID, Links> links) {
    PatchChangeExchangeView.Place place = entry.location();
    Links linked = place == null ? Links.NONE : links.getOrDefault(place.id(), Links.NONE);
    return new ExchangePatchChangeDto.Preview(
        place == null ? null : new ExchangeLocationDto(place.name(), linked.uex(), linked.guid()),
        entry.rows(),
        entry.pools().stream()
            .map(pool ->
                new ExchangePatchChangeDto.PoolRows(
                    new ExchangePatchChangeDto.OrgUnit(pool.orgUnitId(), pool.orgUnitName()),
                    pool.rows()))
            .toList(),
        entry.ships());
  }

  /**
   * Describes a home location.
   *
   * @param place the location
   * @param links the UEX place and GUID by location
   * @return its exchange shape
   */
  private static @NotNull ExchangeHomeLocationDto homeLocation(
      @NotNull PatchChangeExchangeView.Place place, @NotNull Map<UUID, Links> links) {
    Links linked = links.getOrDefault(place.id(), Links.NONE);
    return new ExchangeHomeLocationDto(
        place.id(), place.name(), place.system(), linked.uex(), linked.guid());
  }

  /**
   * Collects every location a prompt names.
   *
   * @param prompt the prompt
   * @return the chosen, offered and current locations
   */
  private static @NotNull Set<UUID> locationIds(
      @NotNull PatchChangeExchangeView.HomeLocation prompt) {
    Set<UUID> ids = new HashSet<>();
    if (prompt.chosen() != null) {
      ids.add(prompt.chosen().id());
    }
    prompt.offered().forEach(place -> ids.add(place.id()));
    prompt.preview().stream()
        .map(PatchChangeExchangeView.Preview::location)
        .filter(Objects::nonNull)
        .forEach(place -> ids.add(place.id()));
    return ids;
  }
}
