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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * The deal header: create, edit, cancel, read and list, and the rules every submit re-checks
 * (REQ-PUR-007 … REQ-PUR-011, REQ-PUR-021, REQ-PUR-022, REQ-PUR-025).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingDealServiceTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID NEED_ID = UUID.randomUUID();
  private static final UUID UNIT_ID = UUID.randomUUID();
  private static final UUID PARTNER_ID = UUID.randomUUID();
  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID DEAL_ID = UUID.randomUUID();
  private static final String PARTNER_NAME = "Nine Tails Traders";
  private static final UUID NO_ACCOUNT = new UUID(0L, 0L);

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDealDeliveryRepository deliveryRepository;
  @Mock private PurchasingPartnerRepository partnerRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private PurchasingDealAssembler assembler;
  @Mock private SupplyNeeds supplyNeeds;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private QualityTierRepository qualityTierRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private PurposeBoundRequests purposeBoundRequests;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Root<PurchasingDeal> root;

  @Mock private CriteriaQuery<?> criteriaQuery;
  @Mock private CriteriaBuilder criteriaBuilder;

  @Captor private ArgumentCaptor<PurchasingDeal> dealCaptor;
  @Captor private ArgumentCaptor<CharSequence> detailsCaptor;
  @Captor private ArgumentCaptor<Specification<PurchasingDeal>> specCaptor;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private PurchasingDealService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingDealService(
            dealRepository,
            trancheRepository,
            deliveryRepository,
            partnerRepository,
            settingsService,
            accessPolicy,
            assembler,
            supplyNeeds,
            userRepository,
            materialRepository,
            gameItemRepository,
            qualityTierRepository,
            missionRepository,
            purposeBoundRequests,
            authHelperService,
            auditRecorder,
            meterRegistry);
  }

  private static NeedRef need(
      UUID materialId,
      UUID gameItemId,
      UUID tierId,
      boolean piece,
      double amount,
      double fulfilled,
      boolean purchasable,
      Long maxUnitPrice,
      NeedStatus status) {
    return new NeedRef(
        NEED_ID,
        UNIT_ID,
        materialId,
        gameItemId,
        tierId,
        0,
        "Quantainium",
        piece,
        amount,
        fulfilled,
        0.0,
        purchasable,
        maxUnitPrice,
        status);
  }

  private static NeedRef openNeed() {
    return need(MATERIAL_ID, null, null, false, 100.0, 0.0, true, null, NeedStatus.OPEN);
  }

  private static PurchasingPartner partner(PartnerKind kind, PartnerStatus status) {
    PurchasingPartner partner = new PurchasingPartner();
    partner.setId(PARTNER_ID);
    partner.setKind(kind);
    partner.setName(PARTNER_NAME);
    partner.setChannel(PartnerChannel.DISCORD);
    partner.setContactVia(PartnerContact.HB);
    partner.setStatus(status);
    return partner;
  }

  private static PurchasingPartner activeOrg() {
    return partner(PartnerKind.ORG, PartnerStatus.ACTIVE);
  }

  private static PurchasingRiskClass riskClass(
      RiskClassCode code, long maxTotal, int advancePercent, boolean requiresKnownPartner) {
    PurchasingRiskClass riskClass = new PurchasingRiskClass();
    riskClass.setId(UUID.randomUUID());
    riskClass.setCode(code);
    riskClass.setMaxTotal(maxTotal);
    riskClass.setMaxAdvancePercent(advancePercent);
    riskClass.setRequiresKnownPartner(requiresKnownPartner);
    return riskClass;
  }

  private static List<PurchasingRiskClass> classes() {
    return List.of(
        riskClass(RiskClassCode.A, 100_000L, 0, false),
        riskClass(RiskClassCode.B, 500_000L, 50, true),
        riskClass(RiskClassCode.C, 5_000_000L, 0, true));
  }

  private static PurchasingDeal draftDeal() {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(DEAL_ID);
    deal.setDealNo(42L);
    deal.setNeedId(NEED_ID);
    deal.setRaisingOrgUnitId(UNIT_ID);
    deal.setPartnerId(PARTNER_ID);
    deal.setDelegateUserId(CALLER);
    deal.setAmount(10.0);
    deal.setUnitPrice(5000L);
    deal.setTotal(50_000L);
    deal.setPaymentModel(PaymentModel.ON_DELIVERY);
    deal.setRiskClass(RiskClassCode.A);
    deal.setStatus(DealStatus.DRAFT);
    deal.setVersion(3L);
    return deal;
  }

  private static CreateDealRequest request(
      double amount, long unitPrice, RiskClassCode riskClass, UUID missionId) {
    return new CreateDealRequest(
        NEED_ID, PARTNER_ID, amount, unitPrice, PaymentModel.PARTIAL, riskClass, false, missionId);
  }

  private static UpdateDealRequest updateRequest(
      double amount, long unitPrice, RiskClassCode riskClass, long version) {
    return new UpdateDealRequest(
        PARTNER_ID, amount, unitPrice, PaymentModel.ADVANCE, riskClass, true, null, version);
  }

  private static DealDto dto(PurchasingDeal deal) {
    return new DealDto(
        deal.getId(),
        42L,
        NEED_ID,
        new PurchasingRefDto(UNIT_ID, "Staffel Alpha"),
        null,
        null,
        "SCU",
        null,
        0.0,
        0.0,
        0.0,
        0L,
        0L,
        null,
        PaymentModel.PARTIAL,
        RiskClassCode.A,
        false,
        null,
        DealStatus.DRAFT,
        false,
        null,
        false,
        0L,
        0L,
        new DealPartnerDto(PARTNER_ID, PartnerKind.ORG, PARTNER_NAME, null, null, null, null),
        null,
        true,
        List.of(),
        List.of(),
        null,
        false,
        false,
        false,
        false,
        false,
        false,
        0L,
        Instant.EPOCH);
  }

  private void signedInDelegate() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(accessPolicy.canCreateDeals()).thenReturn(true);
  }

  private void forCreate(NeedRef need, PurchasingPartner partner) {
    signedInDelegate();
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(need));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(partner));
  }

  private void savingAssignsIds() {
    when(dealRepository.saveAndFlush(any(PurchasingDeal.class)))
        .thenAnswer(invocation -> {
          PurchasingDeal saved = invocation.getArgument(0);
          if (saved.getId() == null) {
            saved.setId(DEAL_ID);
            saved.setDealNo(42L);
          }
          return saved;
        });
  }

  private double counted(String event) {
    return meterRegistry
        .get(MetricNames.PURCHASING_DEALS)
        .tag(MetricNames.TAG_EVENT, event)
        .counter()
        .count();
  }

  private static void assertProblem(ThrowingCallable call, PurchasingProblemCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            PurchasingProblemException.class, e -> assertThat(e.getProblemCode()).isEqualTo(code));
  }

  @Test
  void createsADraftWhoseRiskClassComesFromTheClassTable() {
    forCreate(openNeed(), activeOrg());
    Material material = new Material();
    material.setId(MATERIAL_ID);
    UUID mission = UUID.randomUUID();
    when(missionRepository.existsById(mission)).thenReturn(true);
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(material);
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(dealRepository.countCleanSettled(PARTNER_ID)).thenReturn(3L);
    savingAssignsIds();
    DealDto expected = dto(draftDeal());
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(expected);

    DealDto created = service.create(request(10.0, 20_000L, null, mission));

    assertThat(created).isSameAs(expected);
    verify(dealRepository).saveAndFlush(dealCaptor.capture());
    PurchasingDeal saved = dealCaptor.getValue();
    assertThat(saved.getStatus()).isEqualTo(DealStatus.DRAFT);
    assertThat(saved.getNeedId()).isEqualTo(NEED_ID);
    assertThat(saved.getRaisingOrgUnitId()).isEqualTo(UNIT_ID);
    assertThat(saved.getDelegateUserId()).isEqualTo(CALLER);
    assertThat(saved.getPartnerId()).isEqualTo(PARTNER_ID);
    assertThat(saved.getAmount()).isEqualTo(10.0);
    assertThat(saved.getUnitPrice()).isEqualTo(20_000L);
    assertThat(saved.getTotal()).isEqualTo(200_000L);
    assertThat(saved.getRiskClass()).isEqualTo(RiskClassCode.B);
    assertThat(saved.getPaymentModel()).isEqualTo(PaymentModel.PARTIAL);
    assertThat(saved.getMissionId()).isEqualTo(mission);
    assertThat(saved.getMaterial()).isSameAs(material);
    assertThat(saved.getGameItem()).isNull();
    assertThat(saved.getQualityTier()).isNull();
    assertThat(counted("created")).isEqualTo(1.0);
  }

  @Test
  void theCreateAuditCarriesIdsEnumsAndAmountsButNeverThePartnerName() {
    forCreate(openNeed(), activeOrg());
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    when(settingsService.classesInOrder()).thenReturn(classes());
    savingAssignsIds();
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(dto(draftDeal()));

    service.create(request(10.0, 5_000L, null, null));

    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_CREATED),
            eq(DEAL_ID),
            eq("Deal 42"),
            ArgumentMatchers.<UUID>isNull(),
            detailsCaptor.capture());
    String details = detailsCaptor.getValue().toString();
    assertThat(details)
        .contains("need=" + NEED_ID)
        .contains("partner=" + PARTNER_ID)
        .contains("amount=10.0")
        .contains("total=50000")
        .contains("status=DRAFT")
        .contains("riskClass=A")
        .doesNotContain(PARTNER_NAME);
  }

  @Test
  void aSmallDealIsClassAAndNeedsNoKnownPartner() {
    forCreate(openNeed(), activeOrg());
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    when(settingsService.classesInOrder()).thenReturn(classes());
    savingAssignsIds();
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(dto(draftDeal()));

    service.create(request(5.0, 10_000L, null, null));

    verify(dealRepository).saveAndFlush(dealCaptor.capture());
    assertThat(dealCaptor.getValue().getRiskClass()).isEqualTo(RiskClassCode.A);
    verify(dealRepository, never()).countCleanSettled(any());
  }

  @Test
  void aRequestedClassMayRaiseTheComputedClass() {
    forCreate(openNeed(), activeOrg());
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(dealRepository.countCleanSettled(PARTNER_ID)).thenReturn(1L);
    savingAssignsIds();
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(dto(draftDeal()));

    service.create(request(5.0, 10_000L, RiskClassCode.C, null));

    verify(dealRepository).saveAndFlush(dealCaptor.capture());
    assertThat(dealCaptor.getValue().getRiskClass()).isEqualTo(RiskClassCode.C);
  }

  @Test
  void aRequestedClassBelowTheComputedClassIsRefused() {
    forCreate(openNeed(), activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertThatThrownBy(() -> service.create(request(10.0, 20_000L, RiskClassCode.A, null)))
        .isInstanceOf(BadRequestException.class);

    verify(dealRepository, never()).saveAndFlush(any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void aCallerWhoIsNoActiveDelegateMayNotCreateADeal() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(accessPolicy.canCreateDeals()).thenReturn(false);

    assertThatThrownBy(() -> service.create(request(10.0, 1_000L, null, null)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(supplyNeeds, dealRepository);
  }

  @Test
  void aSignedOutCallerMayNotCreateADeal() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(request(10.0, 1_000L, null, null)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(supplyNeeds, dealRepository, accessPolicy);
  }

  @Test
  void aSwitchedOffSurfaceRefusesBeforeAnythingIsLoaded() {
    doThrow(
            new PurchasingProblemException(
                PurchasingProblemCode.PURCHASING_DISABLED,
                "The purchasing surface is switched off"))
        .when(settingsService)
        .requireEnabled();

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_DISABLED);

    verifyNoInteractions(dealRepository, supplyNeeds, authHelperService);
  }

  @Test
  void anUnknownNeedIsRefused() {
    signedInDelegate();
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(request(10.0, 1_000L, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void anUnknownPartnerIsRefused() {
    signedInDelegate();
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(openNeed()));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(request(10.0, 1_000L, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anUnknownEinsatzIsRefused() {
    forCreate(openNeed(), activeOrg());
    UUID mission = UUID.randomUUID();
    when(missionRepository.existsById(mission)).thenReturn(false);

    assertThatThrownBy(() -> service.create(request(10.0, 1_000L, null, mission)))
        .isInstanceOf(BadRequestException.class);

    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void anAmountThatRoundsToZeroIsRefused() {
    forCreate(openNeed(), activeOrg());

    assertThatThrownBy(() -> service.create(request(0.0004, 1_000L, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aPieceNeedAcceptsWholeUnitsOnly() {
    forCreate(
        need(MATERIAL_ID, null, null, true, 100.0, 0.0, true, null, NeedStatus.OPEN), activeOrg());

    assertThatThrownBy(() -> service.create(request(2.5, 1_000L, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aGameItemNeedAcceptsWholeUnitsOnly() {
    forCreate(
        need(null, UUID.randomUUID(), null, false, 100.0, 0.0, true, null, NeedStatus.OPEN),
        activeOrg());

    assertThatThrownBy(() -> service.create(request(1.5, 1_000L, null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aNeedThatIsNotOpenIsRefused() {
    forCreate(
        need(MATERIAL_ID, null, null, false, 100.0, 0.0, true, null, NeedStatus.PAUSED),
        activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_NEED_NOT_OPEN);
  }

  @Test
  void aNeedTheRaisingUnitDoesNotAllowToBuyIsRefused() {
    forCreate(
        need(MATERIAL_ID, null, null, false, 100.0, 0.0, false, null, NeedStatus.OPEN),
        activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_NEED_NOT_PURCHASABLE);
  }

  @Test
  void aUnitPriceAboveTheNeedsMaximumIsRefused() {
    forCreate(
        need(MATERIAL_ID, null, null, false, 100.0, 0.0, true, 500L, NeedStatus.OPEN), activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.create(request(10.0, 600L, null, null)),
        PurchasingProblemCode.PURCHASING_PRICE_ABOVE_MAXIMUM);
  }

  @Test
  void aQualityTierPurchasingMayNotBuyIsRefused() {
    UUID tier = UUID.randomUUID();
    forCreate(
        need(MATERIAL_ID, null, tier, false, 100.0, 0.0, true, null, NeedStatus.OPEN), activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());
    PurchasingSetting setting = new PurchasingSetting();
    setting.getAllowedQualityTierIds().add(UUID.randomUUID());
    when(settingsService.current()).thenReturn(setting);

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_TIER_NOT_ALLOWED);
  }

  @Test
  void aBlockedPartnerIsRefused() {
    forCreate(openNeed(), partner(PartnerKind.ORG, PartnerStatus.BLOCKED));
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_PARTNER_BLOCKED);
  }

  @Test
  void anIndividualWhoIsAMemberIsRefusedAsCounterparty() {
    forCreate(openNeed(), partner(PartnerKind.INDIVIDUAL, PartnerStatus.ACTIVE));
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(userRepository.existsOtherAccountWithName("nine tails traders", NO_ACCOUNT))
        .thenReturn(true);

    assertProblem(
        () -> service.create(request(10.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_COUNTERPARTY_IS_MEMBER);

    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void anOrganisationIsNeverCheckedAgainstTheMembers() {
    forCreate(openNeed(), activeOrg());
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    when(settingsService.classesInOrder()).thenReturn(classes());
    savingAssignsIds();
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(dto(draftDeal()));

    service.create(request(10.0, 1_000L, null, null));

    verifyNoInteractions(userRepository);
  }

  @Test
  void aTotalAboveEveryClassMaximumIsRefused() {
    forCreate(openNeed(), activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.create(request(100.0, 100_000L, null, null)),
        PurchasingProblemCode.PURCHASING_TOTAL_ABOVE_CLASS_MAXIMUM);
  }

  @Test
  void aClassThatRequiresAKnownPartnerRefusesAPartnerWithoutACleanSettledDeal() {
    forCreate(openNeed(), activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(dealRepository.countCleanSettled(PARTNER_ID)).thenReturn(0L);

    assertProblem(
        () -> service.create(request(10.0, 20_000L, null, null)),
        PurchasingProblemCode.PURCHASING_PARTNER_NOT_KNOWN);

    verify(dealRepository, never()).saveAndFlush(any());
  }

  private PurchasingDeal committedDeal(double amount) {
    PurchasingDeal other = draftDeal();
    other.setId(UUID.randomUUID());
    other.setStatus(DealStatus.SUBMITTED);
    other.setAmount(amount);
    return other;
  }

  private void anotherDealHoldsFiftyOfWhichTenAreDelivered() {
    PurchasingDeal other = committedDeal(50.0);
    PurchasingDealDelivery accepted = new PurchasingDealDelivery();
    accepted.setDealId(other.getId());
    accepted.setAcceptedAmount(10.0);
    PurchasingDealDelivery rejected = new PurchasingDealDelivery();
    rejected.setDealId(other.getId());
    rejected.setAcceptedAmount(null);
    when(dealRepository.findByNeedIdAndStatusIn(
            NEED_ID, Set.of(DealStatus.SUBMITTED, DealStatus.FUNDED, DealStatus.IN_DELIVERY)))
        .thenReturn(List.of(other));
    when(deliveryRepository.findByDealIdIn(Set.of(other.getId())))
        .thenReturn(List.of(accepted, rejected));
  }

  @Test
  void anAmountAboveWhatTheNeedStillAsksForAfterCommittedDealsIsRefused() {
    forCreate(
        need(MATERIAL_ID, null, null, false, 100.0, 20.0, true, null, NeedStatus.OPEN),
        activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());
    anotherDealHoldsFiftyOfWhichTenAreDelivered();

    assertProblem(
        () -> service.create(request(45.0, 1_000L, null, null)),
        PurchasingProblemCode.PURCHASING_AMOUNT_EXCEEDS_NEED);
  }

  @Test
  void anAmountThatExactlyFillsTheRemainingRemainderIsAccepted() {
    forCreate(
        need(MATERIAL_ID, null, null, false, 100.0, 20.0, true, null, NeedStatus.OPEN),
        activeOrg());
    when(settingsService.classesInOrder()).thenReturn(classes());
    anotherDealHoldsFiftyOfWhichTenAreDelivered();
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    savingAssignsIds();
    when(assembler.toDto(any(PurchasingDeal.class))).thenReturn(dto(draftDeal()));

    service.create(request(40.0, 1_000L, null, null));

    verify(dealRepository).saveAndFlush(dealCaptor.capture());
    assertThat(dealCaptor.getValue().getAmount()).isEqualTo(40.0);
  }

  @Test
  void updatesADraftWithTheEchoedVersionAndAuditsIt() {
    signedInDelegate();
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canTradeDeal(deal)).thenReturn(true);
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(openNeed()));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(materialRepository.getReferenceById(MATERIAL_ID)).thenReturn(new Material());
    savingAssignsIds();
    DealDto expected = dto(deal);
    when(assembler.toDto(deal)).thenReturn(expected);

    DealDto updated = service.update(DEAL_ID, updateRequest(20.0, 1_000L, null, 3L));

    assertThat(updated).isSameAs(expected);
    assertThat(deal.getAmount()).isEqualTo(20.0);
    assertThat(deal.getTotal()).isEqualTo(20_000L);
    assertThat(deal.getUnitPrice()).isEqualTo(1_000L);
    assertThat(deal.getPaymentModel()).isEqualTo(PaymentModel.ADVANCE);
    assertThat(deal.isEscortWish()).isTrue();
    assertThat(deal.getRiskClass()).isEqualTo(RiskClassCode.A);
    assertThat(deal.getNeedId()).isEqualTo(NEED_ID);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_UPDATED),
            eq(DEAL_ID),
            eq("Deal 42"),
            ArgumentMatchers.<UUID>isNull(),
            detailsCaptor.capture());
    assertThat(detailsCaptor.getValue().toString())
        .contains("total=20000")
        .contains("riskClass=A")
        .doesNotContain(PARTNER_NAME);
    assertThat(counted("updated")).isEqualTo(1.0);
  }

  @Test
  void aStaleVersionOnUpdateIsAConflictAndWritesNothing() {
    signedInDelegate();
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canTradeDeal(deal)).thenReturn(true);

    assertThatThrownBy(() -> service.update(DEAL_ID, updateRequest(20.0, 1_000L, null, 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(dealRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRecorder);
  }

  @Test
  void onlyTheDealsOwnDelegateMayEditIt() {
    signedInDelegate();
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canTradeDeal(deal)).thenReturn(false);

    assertThatThrownBy(() -> service.update(DEAL_ID, updateRequest(20.0, 1_000L, null, 3L)))
        .isInstanceOf(AccessDeniedException.class);

    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void aDealThatIsNoDraftAnyMoreCannotBeEdited() {
    signedInDelegate();
    PurchasingDeal deal = draftDeal();
    deal.setStatus(DealStatus.SUBMITTED);
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canTradeDeal(deal)).thenReturn(true);

    assertProblem(
        () -> service.update(DEAL_ID, updateRequest(20.0, 1_000L, null, 3L)),
        PurchasingProblemCode.PURCHASING_DEAL_STATE);

    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void editingAnUnknownDealIsNotFound() {
    signedInDelegate();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(DEAL_ID, updateRequest(20.0, 1_000L, null, 3L)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void cancelsADraftWithoutTouchingTheBank() {
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canCancelDeal(deal)).thenReturn(true);
    DealDto expected = dto(deal);
    when(assembler.toDto(deal)).thenReturn(expected);

    DealDto cancelled = service.cancel(DEAL_ID, 3L);

    assertThat(cancelled).isSameAs(expected);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.CANCELLED);
    verify(dealRepository).saveAndFlush(deal);
    verifyNoInteractions(purposeBoundRequests);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_CANCELLED),
            eq(DEAL_ID),
            eq("Deal 42"),
            ArgumentMatchers.<UUID>isNull(),
            detailsCaptor.capture());
    assertThat(detailsCaptor.getValue().toString())
        .contains("reason=MANUAL")
        .doesNotContain(PARTNER_NAME);
    assertThat(counted("cancelled")).isEqualTo(1.0);
  }

  @Test
  void cancellingASubmittedDealCancelsOnlyItsOpenTrancheRequestInTheBank() {
    PurchasingDeal deal = draftDeal();
    deal.setStatus(DealStatus.SUBMITTED);
    UUID openRequest = UUID.randomUUID();
    PurchasingDealTranche open = new PurchasingDealTranche();
    open.setKind(TrancheKind.ADVANCE);
    open.setState(TrancheState.REQUESTED);
    open.setBankRequestId(openRequest);
    PurchasingDealTranche refused = new PurchasingDealTranche();
    refused.setKind(TrancheKind.ADVANCE);
    refused.setState(TrancheState.REFUSED);
    refused.setBankRequestId(UUID.randomUUID());
    PurchasingDealTranche unsent = new PurchasingDealTranche();
    unsent.setKind(TrancheKind.FINAL);
    unsent.setState(TrancheState.REQUESTED);
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canCancelDeal(deal)).thenReturn(true);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID))
        .thenReturn(List.of(open, refused, unsent));
    when(assembler.toDto(deal)).thenReturn(dto(deal));

    service.cancel(DEAL_ID, 3L);

    verify(purposeBoundRequests).cancel(openRequest, BankPurposeKind.PURCHASING_DEAL);
    verifyNoMoreInteractions(purposeBoundRequests);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.CANCELLED);
  }

  @ParameterizedTest
  @EnumSource(
      value = DealStatus.class,
      names = {"FUNDED", "IN_DELIVERY", "DELIVERED", "SETTLED", "CANCELLED", "FAILED"})
  void aDealWithConfirmedMoneyCannotBeCancelled(DealStatus status) {
    PurchasingDeal deal = draftDeal();
    deal.setStatus(status);
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canCancelDeal(deal)).thenReturn(true);

    assertProblem(() -> service.cancel(DEAL_ID, 3L), PurchasingProblemCode.PURCHASING_DEAL_STATE);

    assertThat(deal.getStatus()).isEqualTo(status);
    verifyNoInteractions(purposeBoundRequests, auditRecorder);
    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void aCallerWhoMayNotCancelTheDealIsDeniedBeforeAnyVersionCheck() {
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canCancelDeal(deal)).thenReturn(false);

    assertThatThrownBy(() -> service.cancel(DEAL_ID, 99L))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.DRAFT);
  }

  @Test
  void aStaleVersionOnCancelIsAConflict() {
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findByIdForUpdate(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canCancelDeal(deal)).thenReturn(true);

    assertThatThrownBy(() -> service.cancel(DEAL_ID, 2L))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.DRAFT);
    verify(dealRepository, never()).saveAndFlush(any());
  }

  @Test
  void aWithdrawnNeedCancelsTheDealWithItsReasonInTheAudit() {
    PurchasingDeal deal = draftDeal();
    deal.setStatus(DealStatus.SUBMITTED);

    service.cancelWithinTransaction(deal, "NEED_WITHDRAWN");

    assertThat(deal.getStatus()).isEqualTo(DealStatus.CANCELLED);
    verify(dealRepository).saveAndFlush(deal);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_CANCELLED),
            eq(DEAL_ID),
            eq("Deal 42"),
            ArgumentMatchers.<UUID>isNull(),
            detailsCaptor.capture());
    assertThat(detailsCaptor.getValue().toString())
        .contains("reason=NEED_WITHDRAWN")
        .contains("status=CANCELLED");
  }

  @Test
  void aSubmittableDealPassesTheRecheckAndReturnsItsNeed() {
    PurchasingDeal deal = draftDeal();
    NeedRef need = openNeed();
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(need));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertThat(service.requireSubmittable(deal)).isSameAs(need);
  }

  @Test
  void theDealsOwnCommitmentIsNotCountedAgainstItselfOnTheRecheck() {
    PurchasingDeal deal = draftDeal();
    deal.setStatus(DealStatus.SUBMITTED);
    deal.setAmount(10.0);
    NeedRef need = need(MATERIAL_ID, null, null, false, 10.0, 0.0, true, null, NeedStatus.OPEN);
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(need));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(dealRepository.findByNeedIdAndStatusIn(
            NEED_ID, Set.of(DealStatus.SUBMITTED, DealStatus.FUNDED, DealStatus.IN_DELIVERY)))
        .thenReturn(List.of(deal));

    assertThat(service.requireSubmittable(deal)).isSameAs(need);
  }

  @Test
  void theRecheckRefusesAWithdrawnNeed() {
    PurchasingDeal deal = draftDeal();
    when(supplyNeeds.find(NEED_ID))
        .thenReturn(
            Optional.of(
                need(
                    MATERIAL_ID, null, null, false, 100.0, 0.0, true, null, NeedStatus.WITHDRAWN)));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));

    assertProblem(
        () -> service.requireSubmittable(deal), PurchasingProblemCode.PURCHASING_NEED_NOT_OPEN);
  }

  @Test
  void theRecheckRefusesATotalAboveTheStoredClassMaximum() {
    PurchasingDeal deal = draftDeal();
    deal.setTotal(200_000L);
    deal.setRiskClass(RiskClassCode.A);
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(openNeed()));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));
    when(settingsService.classesInOrder()).thenReturn(classes());

    assertProblem(
        () -> service.requireSubmittable(deal),
        PurchasingProblemCode.PURCHASING_TOTAL_ABOVE_CLASS_MAXIMUM);
  }

  @Test
  void theRecheckRefusesAKnownPartnerClassWhenThePartnerHasNoCleanSettledDeal() {
    PurchasingDeal deal = draftDeal();
    deal.setTotal(200_000L);
    deal.setRiskClass(RiskClassCode.B);
    when(supplyNeeds.find(NEED_ID)).thenReturn(Optional.of(openNeed()));
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(activeOrg()));
    when(settingsService.classesInOrder()).thenReturn(classes());
    when(dealRepository.countCleanSettled(PARTNER_ID)).thenReturn(0L);

    assertProblem(
        () -> service.requireSubmittable(deal), PurchasingProblemCode.PURCHASING_PARTNER_NOT_KNOWN);
  }

  @Test
  void theCounterpartyRecheckRefusesAPartnerWhoTurnedOutToBeAMember() {
    PurchasingDeal deal = draftDeal();
    when(partnerRepository.findById(PARTNER_ID))
        .thenReturn(Optional.of(partner(PartnerKind.INDIVIDUAL, PartnerStatus.ACTIVE)));
    when(userRepository.existsOtherAccountWithName("nine tails traders", NO_ACCOUNT))
        .thenReturn(true);

    assertProblem(
        () -> service.requireExternalCounterparty(deal),
        PurchasingProblemCode.PURCHASING_COUNTERPARTY_IS_MEMBER);
  }

  @Test
  void anErasedIndividualIsNotLookedUpAmongTheMembers() {
    PurchasingDeal deal = draftDeal();
    PurchasingPartner erased = partner(PartnerKind.INDIVIDUAL, PartnerStatus.ACTIVE);
    erased.setErased(true);
    erased.setName(PurchasingPartner.ERASED_NAME);
    when(partnerRepository.findById(PARTNER_ID)).thenReturn(Optional.of(erased));

    service.requireExternalCounterparty(deal);

    verifyNoInteractions(userRepository);
  }

  @Test
  void aReadableDealIsAssembledForTheCaller() {
    PurchasingDeal deal = draftDeal();
    DealDto expected = dto(deal);
    when(dealRepository.findById(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canReadDeal(deal)).thenReturn(true);
    when(assembler.toDto(deal)).thenReturn(expected);

    assertThat(service.get(DEAL_ID)).isSameAs(expected);
  }

  @Test
  void aDealTheCallerMayNotReadIsDenied() {
    PurchasingDeal deal = draftDeal();
    when(dealRepository.findById(DEAL_ID)).thenReturn(Optional.of(deal));
    when(accessPolicy.canReadDeal(deal)).thenReturn(false);

    assertThatThrownBy(() -> service.get(DEAL_ID)).isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(assembler);
  }

  @Test
  void anUnknownDealIsNotFoundOnRead() {
    when(dealRepository.findById(DEAL_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(DEAL_ID)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void listingKeepsThePageTotalAndAssemblesTheRowsOfThePage() {
    Pageable pageable = PageRequest.of(0, 20);
    PurchasingDeal deal = draftDeal();
    DealDto expected = dto(deal);
    when(accessPolicy.isGoverningLeadership()).thenReturn(true);
    when(dealRepository.findAll(
            ArgumentMatchers.<Specification<PurchasingDeal>>any(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(deal), pageable, 57));
    when(assembler.toDtos(List.of(deal))).thenReturn(List.of(expected));

    Page<DealDto> page = service.list(null, null, false, false, pageable);

    assertThat(page.getContent()).containsExactly(expected);
    assertThat(page.getTotalElements()).isEqualTo(57);
  }

  @Test
  void theListFiltersBuildOneSpecificationOverStatusNeedOwnerAndOverdue() {
    Pageable pageable = PageRequest.of(0, 20);
    when(accessPolicy.isGoverningLeadership()).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(dealRepository.findAll(
            ArgumentMatchers.<Specification<PurchasingDeal>>any(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    service.list(DealStatus.DRAFT, NEED_ID, true, true, pageable);

    verify(dealRepository).findAll(specCaptor.capture(), eq(pageable));
    specCaptor.getValue().toPredicate(root, criteriaQuery, criteriaBuilder);
    verify(criteriaBuilder).equal(ArgumentMatchers.<Expression<?>>any(), eq(DealStatus.DRAFT));
    verify(criteriaBuilder).equal(ArgumentMatchers.<Expression<?>>any(), eq(NEED_ID));
    verify(criteriaBuilder).equal(ArgumentMatchers.<Expression<?>>any(), eq(CALLER));
    verify(criteriaBuilder)
        .lessThan(ArgumentMatchers.<Expression<Instant>>any(), any(Instant.class));
  }

  @Test
  void theGoverningLeadershipSeesEveryDeal() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(true);

    service.visibleToCaller().toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(criteriaBuilder).conjunction();
  }

  @Test
  void bankStaffSeeEveryDeal() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.isBankStaff()).thenReturn(true);

    service.visibleToCaller().toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(criteriaBuilder).conjunction();
  }

  @Test
  void aUnitManagerSeesHisOwnDealsAndThoseOfHisUnits() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.isBankStaff()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of(UNIT_ID));

    service.visibleToCaller().toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(criteriaBuilder).equal(ArgumentMatchers.<Expression<?>>any(), eq(CALLER));
    assertThat(orArguments()).hasSize(2);
  }

  @Test
  void aDelegateWithoutManagedUnitsSeesOnlyHisOwnDeals() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.isBankStaff()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());

    service.visibleToCaller().toPredicate(root, criteriaQuery, criteriaBuilder);

    assertThat(orArguments()).hasSize(1);
  }

  @Test
  void aSignedOutCallerMatchesNoOwnDealInTheVisibilityFilter() {
    when(accessPolicy.isGoverningLeadership()).thenReturn(false);
    when(accessPolicy.isBankStaff()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());
    when(accessPolicy.managedUnitIds()).thenReturn(Set.of());

    service.visibleToCaller().toPredicate(root, criteriaQuery, criteriaBuilder);

    verify(criteriaBuilder).equal(ArgumentMatchers.<Expression<?>>any(), eq(NO_ACCOUNT));
  }

  private Object[] orArguments() {
    Invocation or =
        mockingDetails(criteriaBuilder).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("or"))
            .findFirst()
            .orElseThrow();
    return or.getArguments();
  }
}
