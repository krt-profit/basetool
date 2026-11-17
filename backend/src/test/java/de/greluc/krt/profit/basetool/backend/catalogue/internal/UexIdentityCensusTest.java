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
    assertThat(census.sharedUuid(shared)).isTrue();
    assertThat(census.uniqueName("Prowler")).isTrue();
  }

  @Test
  void aGameUuidOneSourceIdCarriesIsNotShared() {
    UUID single = UUID.randomUUID();
    UexIdentityCensus census = census(List.of(new Claim(1, single.toString(), "Item")), false);

    assertThat(census.sharedUuid(single)).isFalse();
    assertThat(census.sharedUuid(UUID.randomUUID())).isFalse();
    assertThat(census.sharedUuid(null)).isFalse();
  }

  @Test
  void everyNameFieldClaimsItsNameAndAClaimUnderAnotherFieldMakesItShared() {
    UexIdentityCensus census =
        UexIdentityCensus.of(
            List.of(
                new Named(239, "Guardian QI", "Mirai Guardian QI"),
                new Named(257, "Guardian MX", "Mirai Guardian MX"),
                new Named(300, "Mirai Guardian MX", "Mirai Guardian MX Variant"),
                new Named(301, "Nox", "Nox")),
            Named::id,
            _ -> "",
            List.of(Named::name, Named::fullName),
            true);

    assertThat(census.uniqueName("Mirai Guardian QI")).isTrue();
    assertThat(census.uniqueName("Guardian MX")).isTrue();
    assertThat(census.uniqueName("Mirai Guardian MX")).isFalse();
    assertThat(census.uniqueName("Nox")).isTrue();
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

  /** A source row carrying a short and a full display name. */
  private record Named(Integer id, String name, String fullName) {}
}
