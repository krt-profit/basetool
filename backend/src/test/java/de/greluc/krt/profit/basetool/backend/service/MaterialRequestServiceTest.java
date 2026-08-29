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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.MaterialRequestFulfillmentSignalledEvent;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit coverage for the Materialbörse Gesuche domain's security-critical behaviour across the
 * read/write split (mirroring the offer suite, ADR-0116): the supplier-anonymity redaction (names
 * only for the owner) lives in {@link MaterialRequestBoardService} and is exercised via that
 * co-wired subject, while the owner-only write gates, the self-signal block, the optimistic-lock
 * guard, the kind-aware quantity validation and the fulfilment-signal notification live in {@link
 * MaterialRequestService}. The write service is co-wired to the real board service so a mutation's
 * redacted response goes through the identical projection.
 */
@ExtendWith(MockitoExtension.class)
class MaterialRequestServiceTest {

  @Mock private MaterialExchangeRequestRepository requestRepository;
  @Mock private MaterialExchangeRequestInterestRepository interestRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditService auditService;
  @Mock private UserMapper userMapper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<MaterialRequestService> selfProvider;

  @InjectMocks private MaterialRequestService service;

  // Read/write split (ADR-0116): the board/detail/counts reads plus the supplier-anonymity
  // redaction
  // live in MaterialRequestBoardService, built from the same mocks and co-wired into the write
  // service below so both the read paths and the write→read projection keep exercising the real
  // logic.
  @InjectMocks private MaterialRequestBoardService boardService;

  private final UUID ownerId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final UUID requestId = UUID.randomUUID();
  private User owner;
  private Material material;
  private MaterialExchangeRequest request;

  /** Builds a fresh owner + active material-request fixture before each test. */
  @BeforeEach
  void setUp() {
    // Mockito passes null for the board-service constructor arg (no @Mock of that type); wire the
    // real co-built board service so the write service's write→read projection runs the real
    // redaction/DTO mapping.
    ReflectionTestUtils.setField(service, "boardService", boardService);
    owner = user(ownerId, "Suchende");
    material = material("Agricium");
    request = materialRequest(requestId, material, owner);

    lenient()
        .when(userMapper.toReferenceDto(any()))
        .thenReturn(new UserReferenceDto(ownerId, "Suchende", "Suchende", "Suchende", null));
    lenient().when(requestRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(interestRepository.countByRequestId(any())).thenReturn(0L);
    lenient()
        .when(interestRepository.existsByRequestIdAndInterestedUserId(any(), any()))
        .thenReturn(false);
    lenient()
        .when(interestRepository.findByRequestIdOrderByCreatedAtDesc(any()))
        .thenReturn(List.of());
    lenient()
        .when(orgUnitMembershipRepository.findAllByIdUserIdInAndKindIn(any(), any()))
        .thenReturn(List.of());
  }

  /** A non-owner viewer sees only the supplier count — never the names (REQ-MARKET-019). */
  @Test
  void detail_nonOwner_getsCountButNoNames() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));
    when(interestRepository.countByRequestId(requestId)).thenReturn(3L);
    when(interestRepository.existsByRequestIdAndInterestedUserId(requestId, otherId))
        .thenReturn(true);

    MaterialRequestDto dto = boardService.detail(requestId);

    assertThat(dto.mine()).isFalse();
    assertThat(dto.interestCount()).isEqualTo(3);
    assertThat(dto.viewerInterested()).isTrue();
    assertThat(dto.interestedHandles()).as("names hidden from non-owner").isNull();
    verify(interestRepository, never()).findByRequestIdOrderByCreatedAtDesc(any());
  }

  /** The owner sees the supplier names (REQ-MARKET-019). */
  @Test
  void detail_owner_getsNames() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));
    when(interestRepository.countByRequestId(requestId)).thenReturn(2L);
    when(interestRepository.findByRequestIdOrderByCreatedAtDesc(requestId))
        .thenReturn(
            List.of(
                interest(user(UUID.randomUUID(), "Mara")),
                interest(user(UUID.randomUUID(), "Hex"))));

    MaterialRequestDto dto = boardService.detail(requestId);

    assertThat(dto.mine()).isTrue();
    assertThat(dto.interestCount()).isEqualTo(2);
    assertThat(dto.interestedHandles()).containsExactly("Mara", "Hex");
  }

  /** The requester's Staffel affiliation renders as a board badge (REQ-MARKET-015). */
  @Test
  void detail_ownerOrgUnits_surfacesStaffelBadge() {
    UUID squadronId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));
    when(orgUnitMembershipRepository.findAllByIdUserIdInAndKindIn(any(), any()))
        .thenReturn(List.of(membership(ownerId, squadronId, OrgUnitKind.SQUADRON)));
    when(orgUnitRepository.findAllById(any()))
        .thenReturn(List.of(squadron(squadronId, "Iridium Alpha", "IA")));

    MaterialRequestDto dto = boardService.detail(requestId);

    assertThat(dto.ownerOrgUnits())
        .extracting(OrgUnitReferenceDto::shorthand)
        .containsExactly("IA");
  }

  /**
   * Posting a material request stamps the acting member as the owner and records a PII-free {@code
   * MARKET_REQUEST_CREATED} audit event (no description body).
   */
  @Test
  void createMaterialRequest_stampsOwner_recordsPiiFreeAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    when(ownerScopeService.currentOrgUnit()).thenReturn(Optional.empty());

    MaterialRequestDto dto =
        service.createMaterialRequest(
            new MaterialRequestCreateRequest(material.getId(), 600, 120.0, "suche gegen X"));

    assertThat(dto.kind()).isEqualTo(MaterialExchangeRequestKind.MATERIAL);
    assertThat(dto.material().name()).isEqualTo("Agricium");
    assertThat(dto.requestedAmount()).isEqualTo(120.0);
    assertThat(dto.minQuality()).isEqualTo(600);
    assertThat(dto.mine()).isTrue();

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.captor();
    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_REQUEST_CREATED),
            any(),
            eq("Agricium"),
            eq(ownerId),
            details.capture());
    String payload = details.getValue().toString();
    assertThat(payload).contains("kind=MATERIAL").contains("amt=120.0").contains("minQuality=600");
    assertThat(payload).contains("remarkLen=").doesNotContain("suche gegen X");
  }

  /**
   * Posting an item request resolves the blueprint product, snapshots its display name and records
   * a PII-free {@code MARKET_REQUEST_CREATED} audit event (no display name or description body in
   * the details).
   */
  @Test
  void createItemRequest_recordsPiiFreeAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(blueprintProductService.resolveByProductKey("venture helmet"))
        .thenReturn(
            Optional.of(
                new ResolvedProduct("venture_helmet", "Venture Helmet", UUID.randomUUID())));
    when(ownerScopeService.currentOrgUnit()).thenReturn(Optional.empty());

    MaterialRequestDto dto =
        service.createItemRequest(
            new MaterialItemRequestCreateRequest("venture helmet", 700, 5, "gegen aUEC"));

    assertThat(dto.kind()).isEqualTo(MaterialExchangeRequestKind.ITEM);
    assertThat(dto.itemName()).isEqualTo("Venture Helmet");
    assertThat(dto.itemQuantity()).isEqualTo(5);
    assertThat(dto.minQuality()).isEqualTo(700);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.captor();
    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_REQUEST_CREATED),
            any(),
            eq("Venture Helmet"),
            eq(ownerId),
            details.capture());
    String payload = details.getValue().toString();
    assertThat(payload)
        .contains("kind=ITEM")
        .contains("product=venture_helmet")
        .contains("qty=5")
        .contains("minQuality=700");
    assertThat(payload).doesNotContain("Venture Helmet").doesNotContain("gegen aUEC");
  }

  /**
   * Posting an item request whose product key resolves to no active blueprint is rejected (404).
   */
  @Test
  void createItemRequest_unknownProduct_notFound() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(blueprintProductService.resolveByProductKey("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createItemRequest(
                    new MaterialItemRequestCreateRequest("nope", null, 1, "")))
        .isInstanceOf(NotFoundException.class);
    verify(requestRepository, never()).saveAndFlush(any());
  }

  /** A non-owner cannot edit a request (403). */
  @Test
  void updateRequest_nonOwner_forbidden() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    assertThatThrownBy(
            () ->
                service.updateRequest(
                    requestId, new MaterialRequestUpdateRequest(50.0, 500, "x", 0L)))
        .isInstanceOf(AccessDeniedException.class);
    verify(requestRepository, never()).saveAndFlush(any());
  }

  /** A stale version on a request edit raises an optimistic-lock conflict (→ 409). */
  @Test
  void updateRequest_staleVersion_conflict() {
    request.setVersion(5L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    assertThatThrownBy(
            () ->
                service.updateRequest(
                    requestId, new MaterialRequestUpdateRequest(50.0, 500, "neu", 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(requestRepository, never()).saveAndFlush(any());
  }

  /** Editing a material request updates the amount, minimum quality and description. */
  @Test
  void updateRequest_material_changesAmountMinQualityRemark() {
    request.setVersion(3L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    MaterialRequestDto dto =
        service.updateRequest(requestId, new MaterialRequestUpdateRequest(200.0, 400, "neu", 3L));

    assertThat(request.getRequestedAmount()).isEqualTo(200.0);
    assertThat(request.getMinQuality()).isEqualTo(400);
    assertThat(request.getRemark()).isEqualTo("neu");
    assertThat(dto.requestedAmount()).isEqualTo(200.0);
  }

  /** Editing an item request to a non-whole quantity is rejected (400). */
  @Test
  void updateRequest_item_nonWholeQuantity_badRequest() {
    MaterialExchangeRequest itemReq = itemRequest(requestId, owner);
    itemReq.setVersion(1L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(itemReq));

    assertThatThrownBy(
            () ->
                service.updateRequest(
                    requestId, new MaterialRequestUpdateRequest(2.5, null, "x", 1L)))
        .isInstanceOf(BadRequestException.class);
    verify(requestRepository, never()).saveAndFlush(any());
  }

  /** A non-owner cannot deactivate a request (403). */
  @Test
  void deactivate_nonOwner_forbidden() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    assertThatThrownBy(() -> service.deactivate(requestId))
        .isInstanceOf(AccessDeniedException.class);
    verify(requestRepository, never()).saveAndFlush(any());
  }

  /** Deactivating an active request flips its status and records the audit event. */
  @Test
  void deactivate_active_recordsAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    service.deactivate(requestId);

    assertThat(request.getStatus()).isEqualTo(MaterialExchangeRequestStatus.DEACTIVATED);
    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_REQUEST_DEACTIVATED),
            eq(requestId),
            eq("Agricium"),
            eq(ownerId),
            any());
  }

  /** Deactivating an already-deactivated request is a no-op — no second audit event. */
  @Test
  void deactivate_alreadyDeactivated_noSecondAudit() {
    request.setStatus(MaterialExchangeRequestStatus.DEACTIVATED);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    service.deactivate(requestId);

    verify(requestRepository, never()).saveAndFlush(any());
    verify(auditService, never())
        .record(eq(AuditEventType.MARKET_REQUEST_DEACTIVATED), any(), any(), any(), any());
  }

  /** A member cannot signal fulfilment on their own request (403). */
  @Test
  void signalFulfillment_ownRequest_forbidden() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(selfProvider.getObject()).thenReturn(service);
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));

    assertThatThrownBy(() -> service.signalFulfillment(requestId))
        .isInstanceOf(AccessDeniedException.class);
    verify(interestRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * A genuinely new fulfilment signal publishes a {@link MaterialRequestFulfillmentSignalledEvent}
   * directed at the request owner, carrying the supplier and material render params
   * (REQ-MARKET-020).
   */
  @Test
  void signalFulfillment_newSignal_publishesOwnerNotification() {
    User supplier = user(otherId, "Lieferant");
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(selfProvider.getObject()).thenReturn(service);
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
    when(userRepository.findById(otherId)).thenReturn(Optional.of(supplier));
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));

    service.signalFulfillment(requestId);

    ArgumentCaptor<MaterialRequestFulfillmentSignalledEvent> captor = ArgumentCaptor.captor();
    verify(eventPublisher).publishEvent(captor.capture());
    MaterialRequestFulfillmentSignalledEvent event = captor.getValue();
    assertThat(event.contextRecipientUserId()).as("recipient is the owner").isEqualTo(ownerId);
    assertThat(event.actorSub()).as("actor is the supplier").isEqualTo(otherId);
    assertThat(event.entityId()).isEqualTo(requestId);
    assertThat(event.entityType()).isEqualTo("MATERIAL_EXCHANGE_REQUEST");
    assertThat(event.renderParams())
        .containsEntry("lieferant", "Lieferant")
        .containsEntry("material", "Agricium");
  }

  /** A duplicate (idempotent) fulfilment signal saves nothing and emits no notification. */
  @Test
  void signalFulfillment_alreadySignalled_noEventNoSave() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(selfProvider.getObject()).thenReturn(service);
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
    when(interestRepository.existsByRequestIdAndInterestedUserId(requestId, otherId))
        .thenReturn(true);
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));

    service.signalFulfillment(requestId);

    verify(interestRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * A concurrent duplicate signal (the inner transaction throws a unique-constraint violation) is
   * swallowed by the orchestrator as an idempotent success — never a 500 (CLAUDE.md find-or-create
   * rule).
   */
  @Test
  void signalFulfillment_concurrentDuplicate_isIdempotent() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    MaterialRequestService spy = spy(service);
    when(selfProvider.getObject()).thenReturn(spy);
    doThrow(new DataIntegrityViolationException("uq"))
        .when(spy)
        .signalFulfillmentInNewTransaction(requestId, otherId);
    when(requestRepository.findWithDetailById(requestId)).thenReturn(Optional.of(request));

    MaterialRequestDto dto = service.signalFulfillment(requestId);

    assertThat(dto.id()).isEqualTo(requestId);
  }

  /** Withdrawing an existing fulfilment signal records the withdrawal audit event. */
  @Test
  void withdrawFulfillment_removed_recordsAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
    when(interestRepository.deleteByRequestIdAndInterestedUserId(requestId, otherId))
        .thenReturn(1L);

    service.withdrawFulfillment(requestId);

    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_REQUEST_INTEREST_WITHDRAWN),
            eq(requestId),
            eq("Agricium"),
            eq(ownerId),
            any());
  }

  /** Withdrawing a non-existent signal removes nothing and records no audit event. */
  @Test
  void withdrawFulfillment_notRegistered_noAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
    when(interestRepository.deleteByRequestIdAndInterestedUserId(requestId, otherId))
        .thenReturn(0L);

    service.withdrawFulfillment(requestId);

    verify(auditService, never())
        .record(eq(AuditEventType.MARKET_REQUEST_INTEREST_WITHDRAWN), any(), any(), any(), any());
  }

  /** The tab counts return the board total and the caller's own active-request count. */
  @Test
  void counts_returnsAllAndMine() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(requestRepository.countByStatus(MaterialExchangeRequestStatus.ACTIVE)).thenReturn(7L);
    when(requestRepository.countByStatusAndOwnerId(MaterialExchangeRequestStatus.ACTIVE, ownerId))
        .thenReturn(3L);

    var counts = boardService.counts();

    assertThat(counts.all()).isEqualTo(7);
    assertThat(counts.mine()).isEqualTo(3);
  }

  private static User user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setDisplayName(name);
    return user;
  }

  private static Material material(String name) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName(name);
    material.setQuantityType(QuantityType.SCU);
    return material;
  }

  private static MaterialExchangeRequest materialRequest(UUID id, Material material, User owner) {
    MaterialExchangeRequest request = new MaterialExchangeRequest();
    request.setId(id);
    request.setKind(MaterialExchangeRequestKind.MATERIAL);
    request.setRequestedMaterial(material);
    request.setRequestedAmount(120.0);
    request.setMinQuality(600);
    request.setOwner(owner);
    request.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    request.setPostedAt(Instant.now());
    request.setRemark("suche gegen **Titanium**");
    request.setVersion(0L);
    return request;
  }

  private static MaterialExchangeRequest itemRequest(UUID id, User owner) {
    MaterialExchangeRequest request = new MaterialExchangeRequest();
    request.setId(id);
    request.setKind(MaterialExchangeRequestKind.ITEM);
    request.setItemProductKey("venture_helmet");
    request.setItemName("Venture Helmet");
    request.setItemQuantity(5);
    request.setMinQuality(700);
    request.setOwner(owner);
    request.setStatus(MaterialExchangeRequestStatus.ACTIVE);
    request.setPostedAt(Instant.now());
    request.setVersion(0L);
    return request;
  }

  private static MaterialExchangeRequestInterest interest(User user) {
    MaterialExchangeRequestInterest interest = new MaterialExchangeRequestInterest();
    interest.setId(UUID.randomUUID());
    interest.setInterestedUser(user);
    return interest;
  }

  private static OrgUnitMembership membership(UUID userId, UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, orgUnitId));
    membership.setKind(kind);
    return membership;
  }

  private static Squadron squadron(UUID id, String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    return squadron;
  }
}
