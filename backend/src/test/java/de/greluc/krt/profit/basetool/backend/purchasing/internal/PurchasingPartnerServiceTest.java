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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * The partners of the trade deals and their erasure (REQ-PUR-004, REQ-PUR-006), and the rule that
 * the audit trail never carries a partner's name, handle or note (REQ-PUR-025).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingPartnerServiceTest {

  private static final String NAME = "Crimson Haulers Ltd";
  private static final String HANDLE = "ShadyTrader77";
  private static final String CONDITIONS = "pays only in advance";
  private static final String REPUTATION = "burned two crews";

  @Mock private PurchasingPartnerRepository partnerRepository;
  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private CriteriaBuilder criteriaBuilder;
  @Mock private CriteriaQuery<PurchasingPartner> criteriaQuery;
  @Mock private Root<PurchasingPartner> root;

  private PurchasingPartnerService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingPartnerService(
            partnerRepository,
            dealRepository,
            settingsService,
            accessPolicy,
            authHelperService,
            auditRecorder);
  }

  private static PurchasingPartner partner(PartnerKind kind, String name, long version) {
    PurchasingPartner partner = new PurchasingPartner();
    partner.setId(UUID.randomUUID());
    partner.setVersion(version);
    partner.setKind(kind);
    partner.setName(name);
    partner.setChannel(PartnerChannel.DISCORD);
    partner.setContactVia(PartnerContact.HB);
    partner.setConditionsNote(CONDITIONS);
    partner.setReputationNote(REPUTATION);
    return partner;
  }

  private void maintainer() {
    when(accessPolicy.canMaintainPartners()).thenReturn(true);
  }

  private void stubSave() {
    when(partnerRepository.saveAndFlush(any(PurchasingPartner.class)))
        .thenAnswer(invocation -> {
          PurchasingPartner saved = invocation.getArgument(0);
          if (saved.getId() == null) {
            saved.setId(UUID.randomUUID());
          }
          return saved;
        });
  }

  private void assertAuditCarriesNoPartnerText(AuditEventType type, String... forbidden) {
    ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder).record(eq(type), any(), label.capture(), isNull(), details.capture());
    String recorded = label.getValue() + " " + details.getValue();
    for (String text : forbidden) {
      assertThat(recorded).doesNotContain(text);
    }
  }

  @Test
  void listIsRefusedForACallerWhoMaintainsNoPartners() {
    when(accessPolicy.canMaintainPartners()).thenReturn(false);

    assertThatThrownBy(() -> service.list(null, null, null, PageRequest.of(0, 10)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(partnerRepository);
  }

  @Test
  void listComputesTheHistoryAndHidesTheNotesFromACallerWhoMayNotReadThem() {
    maintainer();
    when(accessPolicy.canReadPartnerNotes()).thenReturn(false);
    PurchasingPartner first = partner(PartnerKind.ORG, NAME, 1L);
    PurchasingPartner second = partner(PartnerKind.INDIVIDUAL, HANDLE, 1L);
    Pageable pageable = PageRequest.of(0, 10);
    when(partnerRepository.findAll(
            ArgumentMatchers.<Specification<PurchasingPartner>>any(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));
    when(dealRepository.countByPartners(anyCollection()))
        .thenReturn(List.of(new PartnerCountRow(first.getId(), 3L)));
    when(dealRepository.countCleanSettledByPartners(anyCollection()))
        .thenReturn(List.of(new PartnerCountRow(first.getId(), 2L)));

    Page<PartnerDto> page = service.list(null, null, null, pageable);

    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getContent().get(0).dealCount()).isEqualTo(3L);
    assertThat(page.getContent().get(0).settledClean()).isEqualTo(2L);
    assertThat(page.getContent().get(1).dealCount()).isZero();
    assertThat(page.getContent().get(1).settledClean()).isZero();
    assertThat(page.getContent()).allSatisfy(dto -> assertThat(dto.conditionsNote()).isNull());
    assertThat(page.getContent()).allSatisfy(dto -> assertThat(dto.reputationNote()).isNull());
  }

  @Test
  void listReturnsTheNotesToTheCircleThatMayReadThem() {
    maintainer();
    when(accessPolicy.canReadPartnerNotes()).thenReturn(true);
    PurchasingPartner only = partner(PartnerKind.ORG, NAME, 1L);
    Pageable pageable = PageRequest.of(0, 10);
    when(partnerRepository.findAll(
            ArgumentMatchers.<Specification<PurchasingPartner>>any(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(only), pageable, 1));

    Page<PartnerDto> page = service.list(null, null, null, pageable);

    assertThat(page.getContent().get(0).conditionsNote()).isEqualTo(CONDITIONS);
    assertThat(page.getContent().get(0).reputationNote()).isEqualTo(REPUTATION);
  }

  @Test
  void listFiltersByTheEscapedNameFragmentTheKindAndTheStatus() {
    maintainer();
    Pageable pageable = PageRequest.of(0, 10);
    ArgumentCaptor<Specification<PurchasingPartner>> spec = ArgumentCaptor.captor();
    when(partnerRepository.findAll(spec.capture(), eq(pageable))).thenReturn(Page.empty(pageable));

    Page<PartnerDto> page =
        service.list(" Ab_c ", PartnerKind.ORG, PartnerStatus.BLOCKED, pageable);

    assertThat(page.getContent()).isEmpty();
    verifyNoInteractions(dealRepository);
    spec.getValue().toPredicate(root, criteriaQuery, criteriaBuilder);
    verify(criteriaBuilder).like(any(), eq("%ab\\_c%"), eq('\\'));
    verify(criteriaBuilder).equal(any(), eq(PartnerKind.ORG));
    verify(criteriaBuilder).equal(any(), eq(PartnerStatus.BLOCKED));
  }

  @Test
  void listWithoutFiltersAddsNoNameKindOrStatusCriterion() {
    maintainer();
    Pageable pageable = PageRequest.of(0, 10);
    ArgumentCaptor<Specification<PurchasingPartner>> spec = ArgumentCaptor.captor();
    when(partnerRepository.findAll(spec.capture(), eq(pageable))).thenReturn(Page.empty(pageable));

    service.list("  ", null, null, pageable);

    spec.getValue().toPredicate(root, criteriaQuery, criteriaBuilder);
    verify(criteriaBuilder, never()).like(any(), any(String.class), any(char.class));
    verify(criteriaBuilder, never()).equal(any(), any(Object.class));
  }

  @Test
  void getReturnsThePartnerWithItsNotesAndHistory() {
    maintainer();
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 2L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));
    when(dealRepository.countByPartners(anyCollection()))
        .thenReturn(List.of(new PartnerCountRow(partner.getId(), 4L)));
    when(dealRepository.countCleanSettledByPartners(anyCollection()))
        .thenReturn(List.of(new PartnerCountRow(partner.getId(), 1L)));

    PartnerDto dto = service.get(partner.getId());

    assertThat(dto.name()).isEqualTo(NAME);
    assertThat(dto.conditionsNote()).isEqualTo(CONDITIONS);
    assertThat(dto.dealCount()).isEqualTo(4L);
    assertThat(dto.settledClean()).isEqualTo(1L);
    assertThat(dto.version()).isEqualTo(2L);
  }

  @Test
  void getOfAnUnknownPartnerIsNotFound() {
    maintainer();
    UUID id = UUID.randomUUID();
    when(partnerRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void creatingAnOrganisationNormalisesTheInputAndAuditsKindAndIdOnly() {
    maintainer();
    stubSave();
    UUID actor = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(actor));

    PartnerDto dto =
        service.create(
            new CreatePartnerRequest(
                PartnerKind.ORG,
                "  " + NAME + "  ",
                " CHL ",
                PartnerChannel.RSI,
                "  " + CONDITIONS + " ",
                "   ",
                PartnerContact.DIPLOMACY));

    assertThat(dto.name()).isEqualTo(NAME);
    assertThat(dto.orgTag()).isEqualTo("CHL");
    assertThat(dto.conditionsNote()).isEqualTo(CONDITIONS);
    assertThat(dto.reputationNote()).isNull();
    assertThat(dto.status()).isEqualTo(PartnerStatus.ACTIVE);
    assertThat(dto.dealCount()).isZero();
    ArgumentCaptor<PurchasingPartner> stored = ArgumentCaptor.forClass(PurchasingPartner.class);
    verify(partnerRepository).saveAndFlush(stored.capture());
    assertThat(stored.getValue().getCreatedBy()).isEqualTo(actor);
    ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PARTNER_CREATED),
            eq(dto.id()),
            label.capture(),
            isNull(),
            details.capture());
    assertThat(label.getValue()).isEqualTo("Partner ORG");
    assertThat(details.getValue().toString()).isEqualTo("kind=ORG");
    assertAuditCarriesNoPartnerTextIn(label.getValue(), details.getValue());
  }

  private static void assertAuditCarriesNoPartnerTextIn(String label, CharSequence details) {
    String recorded = label + " " + details;
    assertThat(recorded)
        .doesNotContain(NAME, HANDLE, CONDITIONS, REPUTATION, "CHL", "Crimson", "Shady");
  }

  @Test
  void anIndividualKeepsNoOrganisationTagAndItsHandleStaysOutOfTheAudit() {
    maintainer();
    stubSave();

    PartnerDto dto =
        service.create(
            new CreatePartnerRequest(
                PartnerKind.INDIVIDUAL,
                HANDLE,
                "TAG",
                PartnerChannel.UEX,
                null,
                REPUTATION,
                PartnerContact.OTHER));

    assertThat(dto.orgTag()).isNull();
    assertThat(dto.kind()).isEqualTo(PartnerKind.INDIVIDUAL);
    assertAuditCarriesNoPartnerText(
        AuditEventType.PARTNER_CREATED, HANDLE, REPUTATION, "TAG", "Shady");
  }

  @Test
  void creatingIsRefusedForACallerWhoMaintainsNoPartners() {
    when(accessPolicy.canMaintainPartners()).thenReturn(false);

    assertThatThrownBy(() ->
            service.create(
                new CreatePartnerRequest(
                    PartnerKind.ORG,
                    NAME,
                    null,
                    PartnerChannel.OTHER,
                    null,
                    null,
                    PartnerContact.OTHER)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(partnerRepository, auditRecorder);
  }

  @Test
  void updatingChangesTheEditableFieldsAndKeepsTheNameOutOfTheAudit() {
    maintainer();
    stubSave();
    PurchasingPartner partner = partner(PartnerKind.ORG, "Old Name", 5L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    PartnerDto dto =
        service.update(
            partner.getId(),
            new UpdatePartnerRequest(
                " " + NAME + " ",
                "CHL",
                PartnerChannel.RSI,
                CONDITIONS,
                REPUTATION,
                PartnerContact.DIPLOMACY,
                5L));

    assertThat(partner.getName()).isEqualTo(NAME);
    assertThat(partner.getOrgTag()).isEqualTo("CHL");
    assertThat(partner.getChannel()).isEqualTo(PartnerChannel.RSI);
    assertThat(partner.getContactVia()).isEqualTo(PartnerContact.DIPLOMACY);
    assertThat(dto.name()).isEqualTo(NAME);
    ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PARTNER_UPDATED),
            eq(partner.getId()),
            label.capture(),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).isEqualTo("kind=ORG");
    assertAuditCarriesNoPartnerTextIn(label.getValue(), details.getValue());
  }

  @Test
  void updatingWithAStaleVersionIsAConflict() {
    maintainer();
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 5L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() ->
            service.update(
                partner.getId(),
                new UpdatePartnerRequest(
                    "Other", null, PartnerChannel.OTHER, null, null, PartnerContact.OTHER, 4L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(partnerRepository, never()).saveAndFlush(any());
    assertThat(partner.getName()).isEqualTo(NAME);
  }

  @Test
  void anErasedPartnerCannotBeEdited() {
    maintainer();
    PurchasingPartner partner = partner(PartnerKind.INDIVIDUAL, PurchasingPartner.ERASED_NAME, 1L);
    partner.setErased(true);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() ->
            service.update(
                partner.getId(),
                new UpdatePartnerRequest(
                    "Back Again",
                    null,
                    PartnerChannel.OTHER,
                    null,
                    null,
                    PartnerContact.OTHER,
                    1L)))
        .isInstanceOf(BadRequestException.class);

    verify(partnerRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void blockingSetsTheStatusAndAuditsWithoutDetails() {
    maintainer();
    stubSave();
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 1L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    PartnerDto dto = service.block(partner.getId(), 1L);

    assertThat(dto.status()).isEqualTo(PartnerStatus.BLOCKED);
    assertThat(partner.getStatus()).isEqualTo(PartnerStatus.BLOCKED);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PARTNER_BLOCKED),
            eq(partner.getId()),
            eq("Partner ORG"),
            isNull(),
            isNull());
  }

  @Test
  void unblockingLiftsTheBlock() {
    maintainer();
    stubSave();
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 1L);
    partner.setStatus(PartnerStatus.BLOCKED);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    PartnerDto dto = service.unblock(partner.getId(), 1L);

    assertThat(dto.status()).isEqualTo(PartnerStatus.ACTIVE);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PARTNER_UNBLOCKED),
            eq(partner.getId()),
            eq("Partner ORG"),
            isNull(),
            isNull());
  }

  @Test
  void anErasedPartnerStaysBlocked() {
    maintainer();
    PurchasingPartner partner = partner(PartnerKind.INDIVIDUAL, PurchasingPartner.ERASED_NAME, 1L);
    partner.setErased(true);
    partner.setStatus(PartnerStatus.BLOCKED);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() -> service.unblock(partner.getId(), 1L))
        .isInstanceOf(BadRequestException.class);

    assertThat(partner.getStatus()).isEqualTo(PartnerStatus.BLOCKED);
    verify(partnerRepository, never()).saveAndFlush(any());
  }

  @Test
  void blockingWithAStaleVersionIsAConflict() {
    maintainer();
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 3L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() -> service.block(partner.getId(), 2L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void blockingIsRefusedForACallerWhoMaintainsNoPartners() {
    when(accessPolicy.canMaintainPartners()).thenReturn(false);

    assertThatThrownBy(() -> service.block(UUID.randomUUID(), 1L))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(partnerRepository);
  }

  @Test
  void erasingWipesTheHandleAndTheNotesBlocksThePartnerAndAuditsKindOnly() {
    when(accessPolicy.isAdmin()).thenReturn(true);
    stubSave();
    PurchasingPartner partner = partner(PartnerKind.INDIVIDUAL, HANDLE, 2L);
    partner.setOrgTag("TAG");
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    PartnerDto dto = service.erase(partner.getId(), 2L);

    assertThat(partner.getName()).isEqualTo(PurchasingPartner.ERASED_NAME);
    assertThat(partner.getConditionsNote()).isNull();
    assertThat(partner.getReputationNote()).isNull();
    assertThat(partner.getOrgTag()).isNull();
    assertThat(partner.isErased()).isTrue();
    assertThat(partner.getStatus()).isEqualTo(PartnerStatus.BLOCKED);
    assertThat(dto.erased()).isTrue();
    assertThat(dto.name()).isEqualTo(PurchasingPartner.ERASED_NAME);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.PARTNER_ERASED),
            eq(partner.getId()),
            eq("Partner INDIVIDUAL"),
            isNull(),
            isNull());
  }

  @Test
  void onlyAnAdminErases() {
    when(accessPolicy.isAdmin()).thenReturn(false);

    assertThatThrownBy(() -> service.erase(UUID.randomUUID(), 1L))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(partnerRepository, auditRecorder);
  }

  @Test
  void anOrganisationIsNotErased() {
    when(accessPolicy.isAdmin()).thenReturn(true);
    PurchasingPartner partner = partner(PartnerKind.ORG, NAME, 1L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() -> service.erase(partner.getId(), 1L))
        .isInstanceOf(BadRequestException.class);

    assertThat(partner.getName()).isEqualTo(NAME);
    assertThat(partner.isErased()).isFalse();
    verify(partnerRepository, never()).saveAndFlush(any());
  }

  @Test
  void erasingWithAStaleVersionIsAConflict() {
    when(accessPolicy.isAdmin()).thenReturn(true);
    PurchasingPartner partner = partner(PartnerKind.INDIVIDUAL, HANDLE, 7L);
    when(partnerRepository.findById(partner.getId())).thenReturn(Optional.of(partner));

    assertThatThrownBy(() -> service.erase(partner.getId(), 6L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(partner.getName()).isEqualTo(HANDLE);
  }
}
