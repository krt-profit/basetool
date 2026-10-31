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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.LegalBasis;
import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.platform.api.UserReassignmentParticipant.Moved;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The purchasing module's part of the person search, the data export and the account merge
 * (REQ-PUR-028, REQ-SEC-045, REQ-SEC-058, REQ-SEC-060).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingParticipantsTest {

  private static final UUID SOURCE = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  @Mock private OwnedRowsMover mover;
  @Mock private PurchasingDelegateRepository delegateRepository;

  private PurchasingUserReassignment reassignment;

  @BeforeEach
  void setUp() {
    reassignment = new PurchasingUserReassignment(mover, delegateRepository);
  }

  @Test
  void thePersonSearchReadsTheNameAndTheFreeTextColumnsOfAPartner() {
    List<PersonMentionSource.Target> targets = new PurchasingMentionSource().targets();

    assertThat(targets)
        .extracting(PersonMentionSource.Target::column)
        .containsExactly("name", "org_tag", "conditions_note", "reputation_note");
    assertThat(targets)
        .allSatisfy(target -> {
          assertThat(target.area()).isEqualTo("PURCHASING");
          assertThat(target.table()).isEqualTo("purchasing_partner");
          assertThat(target.idColumn()).isEqualTo("id");
          assertThat(target.linkKind()).isNull();
        });
  }

  @Test
  void everyCodeColumnIsExemptAndNoSearchedColumnIs() {
    PurchasingMentionSource source = new PurchasingMentionSource();

    Set<String> exempt = source.exemptColumns();

    assertThat(exempt)
        .containsExactlyInAnyOrder(
            "purchasing_partner.kind",
            "purchasing_partner.channel",
            "purchasing_partner.contact_via",
            "purchasing_partner.status",
            "purchasing_setting.budget_period",
            "purchasing_setting.pause_reason",
            "purchasing_unit_pause.reason",
            "purchasing_risk_class.code",
            "purchasing_deal.payment_model",
            "purchasing_deal.risk_class",
            "purchasing_deal.status",
            "purchasing_deal_tranche.kind",
            "purchasing_deal_tranche.state",
            "purchasing_deal_delivery.status",
            "purchasing_deal_delivery.reject_reason",
            "purchasing_deal_settlement.loss_reason");
    for (PersonMentionSource.Target target : source.targets()) {
      assertThat(exempt).doesNotContain(target.table() + "." + target.column());
    }
  }

  @Test
  void theExportHasTheDealsAndTheAppointmentsOfTheMember() {
    List<Section> sections = new PurchasingExportSections().sections();

    assertThat(sections)
        .extracting(Section::key)
        .containsExactly("purchasingDeals", "purchasingAppointments");
    assertThat(sections).extracting(Section::position).containsExactly(490, 491);
    assertThat(sections).extracting(Section::legalBasis).containsOnly(LegalBasis.ART_15_20);
  }

  @Test
  void everyExportStatementIsBoundToTheMemberAndLeavesThePartnerOut() {
    List<Section> sections = new PurchasingExportSections().sections();

    for (Section section : sections) {
      assertThat(section.sql()).contains(":userId").doesNotContain("partner");
    }
    assertThat(sections.get(0).sql())
        .contains("FROM purchasing_deal d")
        .contains("d.delegate_user_id = :userId");
    assertThat(sections.get(1).sql())
        .contains("FROM purchasing_delegate p")
        .contains("p.user_id = :userId");
  }

  @Test
  void theCatalogueEntryOfADealIsDeclaredAsNoPerson() {
    Section deals = new PurchasingExportSections().sections().get(0);

    assertThat(deals.unscrubbedPersonColumns()).containsOnlyKeys("entry");
    assertThat(deals.scrubbedColumns()).isEmpty();
  }

  @Test
  void theMergeMovesTheDeclaredColumnsInTheDeclaredOrder() {
    assertThat(reassignment.position()).isEqualTo(PurchasingUserReassignment.POSITION);
    assertThat(reassignment.followsTheMember())
        .extracting(OwnedRows::qualified)
        .containsExactly(
            "purchasing_delegate.user_id",
            "purchasing_delegate.appointed_by",
            "purchasing_delegate.revoked_by",
            "purchasing_partner.created_by",
            "purchasing_deal.delegate_user_id",
            "purchasing_deal_delivery.recorded_by",
            "purchasing_deal_delivery.accepted_by",
            "purchasing_deal_settlement.recorded_by",
            "purchasing_setting.paused_by",
            "purchasing_unit_pause.paused_by");
    assertThat(reassignment.staysWithTheAct()).isEmpty();
  }

  @Test
  void reassigningHandsTheColumnsToTheMover() {
    List<Moved> moved = List.of(new Moved("purchasing_deal.delegate_user_id", 3));
    when(mover.move(reassignment.followsTheMember(), SOURCE, TARGET)).thenReturn(moved);

    assertThat(reassignment.reassign(SOURCE, TARGET)).isSameAs(moved);
  }

  @Test
  void twoActiveAppointmentsCannotMerge() {
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(SOURCE))
        .thenReturn(Optional.of(new PurchasingDelegate()));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(TARGET))
        .thenReturn(Optional.of(new PurchasingDelegate()));

    assertThatThrownBy(() -> reassignment.assertMergeable(SOURCE, TARGET))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aMergeIsAllowedWhenOnlyOneAccountHoldsAnAppointment() {
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(SOURCE))
        .thenReturn(Optional.of(new PurchasingDelegate()));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(TARGET))
        .thenReturn(Optional.empty());

    assertThatCode(() -> reassignment.assertMergeable(SOURCE, TARGET)).doesNotThrowAnyException();
  }

  @Test
  void aMergeIsAllowedWhenTheSourceHoldsNoAppointment() {
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(SOURCE))
        .thenReturn(Optional.empty());

    assertThatCode(() -> reassignment.assertMergeable(SOURCE, TARGET)).doesNotThrowAnyException();
    verifyNoInteractions(mover);
  }
}
