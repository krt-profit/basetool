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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Ship review attaches corroborated identities and exposes stored variant evidence for inspection.
 */
class P4kShipReviewActionsTest {

  private static final String CLASS = "ANVL_Carrack";

  private ShipTypeRepository ships;
  private ShipTypeP4kVariantRepository variants;
  private P4kReviewCaseRepository cases;
  private P4kShipReviewActions actions;
  private UUID incoming;
  private ShipType ship;

  @BeforeEach
  void setUp() {
    ships = mock(ShipTypeRepository.class);
    variants = mock(ShipTypeP4kVariantRepository.class);
    cases = mock(P4kReviewCaseRepository.class);
    actions = new P4kShipReviewActions(ships, variants, cases, mock(P4kShipClassAliases.class));
    incoming = UUID.randomUUID();
    ship = wikiShip(incoming, CLASS);
    when(ships.findByExternalUuid(incoming)).thenReturn(Optional.of(ship));
    when(ships.findByClassNameOrUexAlias(CLASS)).thenReturn(List.of(ship));
  }

  @Test
  void anUnclaimedGuidWithExactWikiIdentityAndClassAttachesToTheSameShip() {
    when(ships.findById(ship.getId())).thenReturn(Optional.of(ship));
    P4kReviewCaseDto review = review(incoming, CLASS);
    P4kReviewOptions options = actions.options(review, null);
    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.ACTIONABLE);
    assertThat(options.candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.ATTACH_P4K);
          assertThat(candidate.targetId()).isEqualTo(ship.getId());
          assertThat(candidate.evidence())
              .containsExactly(P4kReviewEvidence.EXACT_SCWIKI_UUID, P4kReviewEvidence.EXACT_CLASS);
          assertThat(candidate.uexIds()).containsExactly(417);
          actions.apply(review, null, candidate, UUID.randomUUID());
        });
    assertThat(ship.getP4kUuid()).isEqualTo(incoming);
    assertThat(ship.getExternalUuid()).isEqualTo(incoming);
    assertThat(ship.getUexVehicleId()).isEqualTo(417);
    assertThat(ship.getClassName()).isEqualTo(CLASS);
    verify(ships).saveAndFlush(ship);
  }

  @ParameterizedTest
  @EnumSource(AttachmentBlock.class)
  void unsafeAttachmentsOnlyOfferInspection(AttachmentBlock block) {
    switch (block) {
      case UEX_ONLY -> ship.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
      case WIKI_DELETED -> ship.setScwikiDeletedAt(Instant.EPOCH);
      case RETIRED -> ship.setP4kDeletedAt(Instant.EPOCH);
      case ADMIN_HIDDEN -> ship.setHidden(true);
      case CLASS_MISMATCH -> ship.setClassName("ANVL_Carrack_AI");
      case OTHER_CLASS_ROW ->
          when(ships.findByClassNameOrUexAlias(CLASS))
              .thenReturn(List.of(ship, wikiShip(UUID.randomUUID(), CLASS)));
      case OTHER_GUID_ON_ROW -> ship.setP4kUuid(UUID.randomUUID());
      case GUID_CLAIMED -> {
        ShipType owner = wikiShip(UUID.randomUUID(), CLASS);
        owner.setP4kUuid(incoming);
        when(ships.findByP4kUuid(incoming)).thenReturn(Optional.of(owner));
      }
    }
    P4kReviewOptions options = actions.options(review(incoming, CLASS), null);
    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.CHECK_REQUIRED);
    assertThat(options.candidates())
        .extracting(P4kReviewCandidate::action)
        .containsOnly(P4kReviewAction.INSPECT);
  }

  @Test
  void anExactWikiVariantKeepsItsOwnIdentityAndTheRecordedBaseLink() {
    UUID baseGuid = UUID.randomUUID();
    var relation = new ShipTypeP4kVariant(incoming, CLASS, baseGuid);
    when(variants.findById(incoming)).thenReturn(Optional.of(relation));
    when(ships.findById(ship.getId())).thenReturn(Optional.of(ship));
    ship.setUexVehicleId(null);
    ship.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    P4kReviewCaseDto review = review(incoming, CLASS);

    assertThat(actions.options(review, null).candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.ATTACH_P4K);
          assertThat(candidate.evidence()).contains(P4kReviewEvidence.P4K_VARIANT);
          actions.apply(review, null, candidate, UUID.randomUUID());
        });

    assertThat(ship.getP4kUuid()).isEqualTo(incoming);
    assertThat(ship.getUexVehicleId()).isNull();
    assertThat(relation.getBaseUuid()).isEqualTo(baseGuid);
  }

  @Test
  void anIncomingVariantShowsItsRecordedBaseWithoutProposingASecondIdentity() {
    when(ships.findByExternalUuid(incoming)).thenReturn(Optional.empty());
    UUID base = UUID.randomUUID();
    when(variants.findById(incoming))
        .thenReturn(Optional.of(new ShipTypeP4kVariant(incoming, CLASS, base)));
    ShipType baseShip = wikiShip(base, "ANVL_Carrack_Base");
    baseShip.setP4kUuid(base);
    when(ships.findByP4kUuid(base)).thenReturn(Optional.of(baseShip));

    P4kReviewOptions options = actions.options(review(incoming, CLASS), null);

    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.CHECK_REQUIRED);
    assertThat(options.candidates())
        .filteredOn(candidate -> candidate.targetId().equals(baseShip.getId()))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.INSPECT);
          assertThat(candidate.evidence()).contains(P4kReviewEvidence.P4K_VARIANT);
        });
  }

  @Test
  void aProvenVariantStampQueuesTheBaseWithoutChangingTheShip() {
    UUID oldGuid = UUID.randomUUID();
    String oldClass = "ANVL_Carrack_AI";
    ship.setP4kUuid(oldGuid);
    ship.setClassName(oldClass);
    when(variants.findAllById(List.of(oldGuid)))
        .thenReturn(List.of(new ShipTypeP4kVariant(oldGuid, oldClass, incoming)));
    when(ships.findById(ship.getId())).thenReturn(Optional.of(ship));
    P4kReviewCaseDto review = review(incoming, CLASS);
    P4kReviewCase pending = new P4kReviewCase();
    when(cases.findById(review.id())).thenReturn(Optional.of(pending));
    UUID admin = UUID.randomUUID();

    P4kReviewOptions options = actions.options(review, null);

    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.ACTIONABLE);
    assertThat(options.candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.CORRECT_P4K_GUID);
          assertThat(candidate.evidence())
              .contains(P4kReviewEvidence.P4K_VARIANT, P4kReviewEvidence.OTHER_P4K_GUID);
          actions.apply(review, null, candidate, admin);
        });
    assertThat(ship.getP4kUuid()).isEqualTo(oldGuid);
    assertThat(ship.getClassName()).isEqualTo(oldClass);
    assertThat(pending.isShipRepairPending()).isTrue();
    assertThat(pending.getShipRepairVariantGuid()).isEqualTo(oldGuid);
    assertThat(pending.getShipRepairBaseClass()).isEqualTo(CLASS);
    assertThat(pending.getShipRepairBy()).isEqualTo(admin);
    assertThat(actions.options(review, null).readiness())
        .isEqualTo(P4kReviewReadiness.WAITING_FOR_IMPORT);
    assertThat(actions.options(review, null).candidates())
        .extracting(P4kReviewCandidate::action)
        .containsExactly(P4kReviewAction.CANCEL_SHIP_REPAIR);
  }

  @Test
  void aPendingChoiceCanBeDiscardedAfterTheSelectedShipIsGone() {
    P4kReviewCaseDto review = review(incoming, CLASS);
    UUID removedShip = UUID.randomUUID();
    P4kReviewCase pending = new P4kReviewCase();
    pending.setGuid(incoming);
    pending.queueShipRepair(
        removedShip, UUID.randomUUID(), CLASS + "_AI", CLASS, UUID.randomUUID());
    when(cases.findById(review.id())).thenReturn(Optional.of(pending));

    P4kReviewOptions options = actions.options(review, null);

    assertThat(options.readiness()).isEqualTo(P4kReviewReadiness.WAITING_FOR_IMPORT);
    assertThat(options.candidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.action()).isEqualTo(P4kReviewAction.CANCEL_SHIP_REPAIR);
          assertThat(candidate.targetId()).isEqualTo(removedShip);
          actions.apply(review, null, candidate, UUID.randomUUID());
        });
    verify(ships, never()).findById(removedShip);
    assertThat(pending.isShipRepairPending()).isFalse();
    assertThat(pending.getStatus()).isEqualTo(P4kReviewStatus.OPEN);
    assertThat(pending.getResolution()).isNull();
    assertThat(pending.getShipRepairBy()).isNull();
  }

  @Test
  void aClassSuffixAloneDoesNotQualifyForAQueuedRepair() {
    ship.setP4kUuid(UUID.randomUUID());
    ship.setClassName(CLASS + "_AI");

    assertThat(actions.options(review(incoming, CLASS), null).candidates())
        .extracting(P4kReviewCandidate::action)
        .containsOnly(P4kReviewAction.INSPECT);
  }

  private static ShipType wikiShip(UUID external, String className) {
    ShipType row = new ShipType();
    row.setId(UUID.randomUUID());
    row.setVersion(2L);
    row.setName("Carrack");
    row.setSourceSystems(GameItemSourceSystem.BOTH);
    row.setExternalUuid(external);
    row.setClassName(className);
    row.setUexVehicleId(417);
    return row;
  }

  private static P4kReviewCaseDto review(UUID guid, String className) {
    UUID run = UUID.randomUUID();
    return new P4kReviewCaseDto(
        UUID.randomUUID(),
        0,
        P4kKind.SHIP,
        guid,
        P4kReviewReason.GUID_CONFLICT,
        P4kReviewStatus.OPEN,
        className,
        "Carrack",
        null,
        null,
        null,
        run,
        run,
        null,
        null,
        null);
  }

  /** Evidence changes that prevent a direct P4K attachment. */
  private enum AttachmentBlock {
    UEX_ONLY,
    WIKI_DELETED,
    RETIRED,
    ADMIN_HIDDEN,
    CLASS_MISMATCH,
    OTHER_CLASS_ROW,
    OTHER_GUID_ON_ROW,
    GUID_CLAIMED
  }
}
