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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * The members a Logistician may book a unit's stock in for: the unit's direct members who do not
 * withhold their name, each with their pseudonym at the calling client (REQ-XCH-044, REQ-XCH-045).
 */
@Component
@RequiredArgsConstructor
public class ExchangeOrgStockRoster {

  private final OrgUnitMembershipQueryService membershipQueryService;
  private final UserRepository userRepository;
  private final ExchangeNameWithholdings nameWithholdings;
  private final ExchangeHolderRefs holderRefs;

  /**
   * Lists a unit's members as a client sees them.
   *
   * @param clientId the client, whose salt the refs are made with
   * @param unit the unit
   * @return the members, ordered by name and then by ref
   */
  public @NotNull @Unmodifiable List<Holder> of(@NotNull String clientId, @NotNull UUID unit) {
    Set<UUID> members = new LinkedHashSet<>(membershipQueryService.findDirectMemberUserIds(unit));
    if (members.isEmpty()) {
      return List.of();
    }
    members.removeAll(nameWithholdings.withholding(members));
    Function<UUID, String> refs = holderRefs.forClient(clientId);
    List<Holder> holders = new ArrayList<>();
    for (User user : userRepository.findAllById(members)) {
      holders.add(
          new Holder(
              refs.apply(user.getId()),
              user.getId(),
              ExchangeStockFeedService.cut(
                  Objects.requireNonNullElse(user.getEffectiveName(), ""))));
    }
    holders.sort(
        Comparator.comparing(Holder::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Holder::ref));
    return List.copyOf(holders);
  }

  /**
   * One member of a unit's roster.
   *
   * @param ref the member's pseudonym at the client
   * @param userId the member
   * @param name the name the web shows
   */
  public record Holder(@NotNull String ref, @NotNull UUID userId, @NotNull String name) {}
}
