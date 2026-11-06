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

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves feed membership and cross-source identity ambiguity have independent safety verdicts. */
class UexIdentityCensusTest {

  @Test
  void distinctSourceIdsSharingAGameUuidRemainACompleteMembershipCensus() {
    UUID shared = UUID.randomUUID();
    UexIdentityCensus census =
        census(
            List.of(
                new Claim(149, shared.toString(), "Prowler"),
                new Claim(262, shared.toString(), "Prowler Utility")),
            true);

    assertThat(census.complete()).isTrue();
    assertThat(census.ids()).containsExactlyInAnyOrder(149, 262);
    assertThat(census.uniqueUuid(shared)).isFalse();
    assertThat(census.uniqueName("Prowler")).isTrue();
  }

  @Test
  void repeatedOrMissingSourceIdsWithdrawTheSweepVerdict() {
    UUID uuid = UUID.randomUUID();
    UexIdentityCensus duplicate =
        census(
            List.of(new Claim(9, uuid.toString(), "Item"), new Claim(9, uuid.toString(), "Item")),
            true);
    UexIdentityCensus invalid = census(List.of(new Claim(null, uuid.toString(), "Item")), true);

    assertThat(duplicate.complete()).isFalse();
    assertThat(duplicate.uniqueUuid(uuid)).isFalse();
    assertThat(invalid.complete()).isFalse();
    assertThat(invalid.ids()).isEmpty();
  }

  @Test
  void exactNamesPreserveVariantQualifiersAndRefuseAmbiguousClaimants() {
    UexIdentityCensus census =
        census(
            List.of(
                new Claim(1, "", "  Helmet White "),
                new Claim(2, "", "HELMET WHITE"),
                new Claim(3, "", "Helmet Black")),
            true);

    assertThat(census.uniqueName("helmet white")).isFalse();
    assertThat(census.uniqueName("Helmet Black")).isTrue();
    assertThat(census.uniqueName("Helmet")).isFalse();
  }

  @Test
  void partialFetchesKeepTheirKnownIdsButPermitNoNewCrossSourceClaim() {
    UUID uuid = UUID.randomUUID();
    UexIdentityCensus census = census(List.of(new Claim(1, uuid.toString(), "Item")), false);

    assertThat(census.ids()).containsExactly(1);
    assertThat(census.complete()).isFalse();
    assertThat(census.uniqueUuid(uuid)).isFalse();
    assertThat(census.uniqueName("Item")).isFalse();
  }

  private static UexIdentityCensus census(List<Claim> rows, boolean complete) {
    return UexIdentityCensus.of(rows, Claim::id, Claim::uuid, Claim::name, complete);
  }

  /** Source identities used to exercise the catalogue census independently of transport DTOs. */
  private record Claim(Integer id, String uuid, String name) {}
}
