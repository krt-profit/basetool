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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.MaterialExchangeInterestRegisteredEvent;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeItemReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleasableItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Unit coverage for the material-exchange domain's security-critical behaviour across the
 * read/write split (#14): the interessenten anonymity redaction (names only for the owner) lives in
 * {@link MaterialExchangeBoardService} and is exercised via that co-wired subject, while the
 * owner-only write gates, the item-ownership check on release, the self-interest block and the
 * optimistic-lock guard on a remark edit live in {@link MaterialExchangeService}. The write service
 * is co-wired to the real board service so a mutation's redacted response goes through the
 * identical projection.
 */
@ExtendWith(MockitoExtension.class)
class MaterialExchangeServiceTest {

  @Mock private MaterialExchangeOfferRepository offerRepository;
  @Mock private MaterialExchangeInterestRepository interestRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditService auditService;
  @Mock private UserMapper userMapper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<MaterialExchangeService> selfProvider;

  @InjectMocks private MaterialExchangeService service;

  // Read/write split (#14): the board/detail/counts/picker reads plus the interessenten-anonymity
  // redaction moved to MaterialExchangeBoardService, built from the same mocks and co-wired into
  // the write service below so both the moved read paths and the write→read projection keep
  // exercising the real logic from this fixture.
  @InjectMocks private MaterialExchangeBoardService boardService;

  private final UUID ownerId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final UUID offerId = UUID.randomUUID();
  private User owner;
  private MaterialExchangeOffer offer;

  /** Builds a fresh owner + active offer fixture before each test. */
  @BeforeEach
  void setUp() {
    // Mockito passes null for the board-service constructor arg (no @Mock of that type); wire the
    // real co-built board service so the write service's write→read projection runs the real
    // redaction/DTO mapping.
    ReflectionTestUtils.setField(service, "boardService", boardService);
    owner = user(ownerId, "Anbieter");
    Material material = material("Agricium");
    InventoryItem item = item(owner, material, 796, 340.0);
    offer = offer(offerId, item, owner);
    lenient()
        .when(userMapper.toReferenceDto(any()))
        .thenReturn(new UserReferenceDto(ownerId, "Anbieter", "Anbieter", "Anbieter", null));
  }

  /** A non-owner viewer sees only the interessenten count — never the names (REQ-MARKET-006). */
  @Test
  void detail_nonOwner_getsCountButNoNames() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(3L);
    when(interestRepository.existsByOfferIdAndInterestedUserId(offerId, otherId)).thenReturn(true);

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.mine()).isFalse();
    assertThat(dto.interestCount()).isEqualTo(3);
    assertThat(dto.viewerInterested()).isTrue();
    assertThat(dto.interestedHandles()).as("names hidden from non-owner").isNull();
    verify(interestRepository, never()).findByOfferIdOrderByCreatedAtDesc(any());
  }

  /** The owner sees the interessenten names (REQ-MARKET-006). */
  @Test
  void detail_owner_getsNames() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(2L);
    when(interestRepository.existsByOfferIdAndInterestedUserId(offerId, ownerId)).thenReturn(false);
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(offerId))
        .thenReturn(
            List.of(
                interest(user(UUID.randomUUID(), "Mara")),
                interest(user(UUID.randomUUID(), "Hex"))));

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.mine()).isTrue();
    assertThat(dto.interestCount()).isEqualTo(2);
    assertThat(dto.interestedHandles()).containsExactly("Mara", "Hex");
  }

  /**
   * The offer carries <b>every</b> badge-kind org unit the Anbieter belongs to (no "primary"
   * Staffel): Staffel(n) first, then Spezialkommando(s), then Bereich(e), each name-sorted
   * (REQ-MARKET-001). A member of two Staffeln, one SK and one Bereich surfaces all four
   * affiliation badges — in that kind order — to any viewer.
   */
  @Test
  void detail_ownerOrgUnits_listsStaffelnThenSksThenBereicheNameSorted() {
    UUID staffelBravoId = UUID.randomUUID();
    UUID staffelAlphaId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(0L);
    // Rows returned in a deliberately unsorted order to prove the service imposes the ordering.
    when(orgUnitMembershipRepository.findAllByIdUserIdInAndKindIn(any(), any()))
        .thenReturn(
            List.of(
                membership(ownerId, bereichId, OrgUnitKind.BEREICH),
                membership(ownerId, staffelBravoId, OrgUnitKind.SQUADRON),
                membership(ownerId, skId, OrgUnitKind.SPECIAL_COMMAND),
                membership(ownerId, staffelAlphaId, OrgUnitKind.SQUADRON)));
    when(orgUnitRepository.findAllById(any()))
        .thenReturn(
            List.of(
                bereich(bereichId, "Profit", "PROFIT"),
                squadron(staffelBravoId, "Bravo", "BRV"),
                specialCommand(skId, "Zulu Kommando", "ZK"),
                squadron(staffelAlphaId, "Alpha", "ALP")));

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.ownerOrgUnits())
        .extracting(OrgUnitReferenceDto::shorthand)
        .containsExactly("ALP", "BRV", "ZK", "PROFIT");
    assertThat(dto.ownerOrgUnits())
        .extracting(OrgUnitReferenceDto::kind)
        .containsExactly(
            OrgUnitKind.SQUADRON,
            OrgUnitKind.SQUADRON,
            OrgUnitKind.SPECIAL_COMMAND,
            OrgUnitKind.BEREICH);
  }

  /** An Anbieter with no org-unit membership surfaces no affiliation badges (empty, never null). */
  @Test
  void detail_ownerWithoutMembership_hasEmptyOrgUnits() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(0L);
    when(orgUnitMembershipRepository.findAllByIdUserIdInAndKindIn(any(), any()))
        .thenReturn(List.of());

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.ownerOrgUnits()).isEmpty();
  }

  /** Releasing an item the caller does not own is forbidden. */
  @Test
  void release_notItemOwner_forbidden() {
    UUID itemId = offer.getInventoryItem().getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(inventoryItemRepository.findById(itemId))
        .thenReturn(Optional.of(offer.getInventoryItem()));

    assertThatThrownBy(
            () ->
                service.release(
                    new MaterialExchangeReleaseRequest(itemId, 120.0, "tausche gegen X")))
        .isInstanceOf(AccessDeniedException.class);
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /**
   * Releasing only a part of a Lager row stores the chosen offered amount on the offer and exposes
   * the item's total stock as {@code availableAmount} to the owner (partial offers,
   * REQ-MARKET-002).
   */
  @Test
  void release_partialAmount_storesOfferedAmountAndExposesAvailableToOwner() {
    UUID itemId = offer.getInventoryItem().getId(); // item stock = 340 SCU
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId))
        .thenReturn(Optional.of(offer.getInventoryItem()));
    when(offerRepository.findByInventoryItemIdAndStatus(itemId, MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.release(new MaterialExchangeReleaseRequest(itemId, 120.0, "tausche gegen X"));

    assertThat(dto.amount()).as("board shows the offered part").isEqualTo(120.0);
    assertThat(dto.availableAmount()).as("owner sees the item's total stock").isEqualTo(340.0);
    ArgumentCaptor<MaterialExchangeOffer> captor = ArgumentCaptor.captor();
    verify(offerRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getOfferedAmount()).isEqualTo(120.0);
  }

  /** Offering more than the item's current stock is rejected (400) and persists nothing. */
  @Test
  void release_amountExceedsStock_badRequest() {
    UUID itemId = offer.getInventoryItem().getId(); // item stock = 340 SCU
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId))
        .thenReturn(Optional.of(offer.getInventoryItem()));

    assertThatThrownBy(
            () -> service.release(new MaterialExchangeReleaseRequest(itemId, 999.0, "zu viel")))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /** A member cannot register interest in their own offer — and no notification is emitted. */
  @Test
  void registerInterest_ownOffer_forbidden() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(selfProvider.getObject()).thenReturn(service);
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

    assertThatThrownBy(() -> service.registerInterest(offerId))
        .isInstanceOf(AccessDeniedException.class);
    verify(interestRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * A genuinely new interest registration publishes a {@link
   * MaterialExchangeInterestRegisteredEvent} directed at the offer owner, carrying the interessent
   * and material render params (#1187, REQ-MARKET-011).
   */
  @Test
  void registerInterest_newRegistration_publishesOwnerNotification() {
    User interested = user(otherId, "Mara");
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(selfProvider.getObject()).thenReturn(service);
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.existsByOfferIdAndInterestedUserId(offerId, otherId)).thenReturn(false);
    when(userRepository.findById(otherId)).thenReturn(Optional.of(interested));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(1L);

    service.registerInterest(offerId);

    ArgumentCaptor<MaterialExchangeInterestRegisteredEvent> captor = ArgumentCaptor.captor();
    verify(eventPublisher).publishEvent(captor.capture());
    MaterialExchangeInterestRegisteredEvent event = captor.getValue();
    assertThat(event.contextRecipientSub()).as("recipient is the owner").isEqualTo(ownerId);
    assertThat(event.actorSub()).as("actor is the interessent").isEqualTo(otherId);
    assertThat(event.entityId()).isEqualTo(offerId);
    assertThat(event.entityType()).isEqualTo("MATERIAL_EXCHANGE_OFFER");
    assertThat(event.renderParams())
        .containsEntry("interessent", "Mara")
        .containsEntry("material", "Agricium");
  }

  /** A duplicate (idempotent) interest registration saves nothing and emits no notification. */
  @Test
  void registerInterest_alreadyRegistered_noEventNoSave() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(selfProvider.getObject()).thenReturn(service);
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.existsByOfferIdAndInterestedUserId(offerId, otherId)).thenReturn(true);
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.countByOfferId(offerId)).thenReturn(1L);

    service.registerInterest(offerId);

    verify(interestRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /** A stale version on an offer edit raises an optimistic-lock conflict (→ 409). */
  @Test
  void updateOffer_staleVersion_conflict() {
    offer.setVersion(5L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

    assertThatThrownBy(
            () ->
                service.updateOffer(
                    offerId, new MaterialExchangeOfferUpdateRequest(50.0, "neu", 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(offerRepository, never()).saveAndFlush(any());
  }

  /** Editing an offer updates both the offered amount and the remark (REQ-MARKET-007). */
  @Test
  void updateOffer_changesOfferedAmountAndRemark() {
    offer.setVersion(3L);
    offer.getInventoryItem().setAmount(340.0);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.updateOffer(offerId, new MaterialExchangeOfferUpdateRequest(200.0, "neu", 3L));

    assertThat(dto.amount()).isEqualTo(200.0);
    assertThat(offer.getOfferedAmount()).isEqualTo(200.0);
    assertThat(offer.getRemark()).isEqualTo("neu");
  }

  /** Editing an offer to more than the item's current stock is rejected (400). */
  @Test
  void updateOffer_amountExceedsStock_badRequest() {
    offer.setVersion(1L);
    offer.getInventoryItem().setAmount(50.0);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

    assertThatThrownBy(
            () ->
                service.updateOffer(offerId, new MaterialExchangeOfferUpdateRequest(80.0, "x", 1L)))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
  }

  /** The tab counts return the board total and the caller's own active-offer count. */
  @Test
  void counts_returnsAllAndMine() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE)).thenReturn(7L);
    when(offerRepository.countByStatusAndOwnerId(MaterialExchangeOfferStatus.ACTIVE, ownerId))
        .thenReturn(3L);

    var counts = boardService.counts();

    assertThat(counts.all()).isEqualTo(7);
    assertThat(counts.mine()).isEqualTo(3);
  }

  /**
   * The board/detail effective amount is clamped to the item's current stock (ADR-0086): an offer
   * that stated 340 SCU but whose row has since been booked out to 60 SCU shows only 60 — the board
   * never advertises more than is in stock.
   */
  @Test
  void detail_offeredAmountClampedToCurrentStock() {
    offer.setOfferedAmount(340.0);
    offer.getInventoryItem().setAmount(60.0); // booked out since release
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.amount()).as("shown amount is clamped to remaining stock").isEqualTo(60.0);
  }

  /** A missing offer on detail raises a not-found. */
  @Test
  void detail_missingOffer_notFound() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> boardService.detail(offerId)).isInstanceOf(NotFoundException.class);
  }

  /**
   * The "Material anbieten" picker carries each item's material quantity type, so the release
   * dialog renders the amount in the material's own unit (Stück for PIECE) instead of always SCU
   * (#1182).
   */
  @Test
  void myReleasableItems_carriesMaterialQuantityType() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    Material piece = material("Ballistic Gatling");
    piece.setQuantityType(QuantityType.PIECE);
    InventoryItem item = item(owner, piece, 500, 12.0);
    when(inventoryItemRepository.findReleasableForUser(
            any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of(item));
    when(offerRepository.findInventoryItemIdsWithStatus(any(), any())).thenReturn(Set.of());

    List<MaterialExchangeReleasableItemDto> items = boardService.myReleasableItems(null, null);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).quantityType()).isEqualTo(QuantityType.PIECE);
  }

  /**
   * The release picker's Material/Item radio narrows the query by kind (REQ-MARKET-002): {@code
   * MATERIAL} asks the repository for material rows only ({@code includeMaterial=true,
   * includeItem=false}), {@code ITEM} for game-item rows only, and {@code null} for both. The gate
   * is pushed into the repository call — before its row cap — so a row of the wanted kind past the
   * cap is never hidden by a post-query filter.
   */
  @Test
  void myReleasableItems_kindFilterMapsToRepositoryFlags() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findReleasableForUser(
            any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of());
    // No offerRepository stub: an empty picker result short-circuits releasedInventoryItemIds
    // before it queries the offer repo, so stubbing it would be flagged as unnecessary.

    boardService.myReleasableItems(null, MaterialExchangeOfferKind.MATERIAL);
    boardService.myReleasableItems(null, MaterialExchangeOfferKind.ITEM);
    boardService.myReleasableItems(null, null);

    verify(inventoryItemRepository).findReleasableForUser(any(), any(), eq(true), eq(false), any());
    verify(inventoryItemRepository).findReleasableForUser(any(), any(), eq(false), eq(true), any());
    verify(inventoryItemRepository).findReleasableForUser(any(), any(), eq(true), eq(true), any());
  }

  /**
   * Listing a craftable item (#1185, REQ-MARKET-012) persists an ITEM offer carrying the resolved
   * product key/name and the user-stated quantity, with no Lager row and no quality — and the
   * returned DTO reflects the same (null material/quality/amount).
   */
  @Test
  void releaseItem_validProduct_persistsItemOfferWithQuantityAndNullQuality() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(blueprintProductService.resolveByProductKey("venture helmet"))
        .thenReturn(Optional.of(new ResolvedProduct("venture helmet", "Venture Helmet", null)));
    when(ownerScopeService.currentOrgUnit()).thenReturn(Optional.empty());
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.countByOfferId(any())).thenReturn(0L);
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.releaseItem(
            new MaterialExchangeItemReleaseRequest("venture helmet", 5, "gegen aUEC"));

    ArgumentCaptor<MaterialExchangeOffer> captor = ArgumentCaptor.captor();
    verify(offerRepository).saveAndFlush(captor.capture());
    MaterialExchangeOffer saved = captor.getValue();
    assertThat(saved.getKind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(saved.getInventoryItem()).as("item offer has no Lager row").isNull();
    assertThat(saved.getItemProductKey()).isEqualTo("venture helmet");
    assertThat(saved.getItemName()).isEqualTo("Venture Helmet");
    assertThat(saved.getItemQuantity()).isEqualTo(5);
    assertThat(saved.getOwner()).isEqualTo(owner);

    assertThat(dto.kind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(dto.itemName()).isEqualTo("Venture Helmet");
    assertThat(dto.itemQuantity()).isEqualTo(5);
    assertThat(dto.quality()).as("item offers carry no quality").isNull();
    assertThat(dto.amount()).isNull();
    assertThat(dto.material()).isNull();
  }

  /** Listing an item whose key no active blueprint produces is rejected — nothing is persisted. */
  @Test
  void releaseItem_noBlueprintProduct_notFound() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(blueprintProductService.resolveByProductKey("unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.releaseItem(new MaterialExchangeItemReleaseRequest("unknown", 3, "x")))
        .isInstanceOf(NotFoundException.class);
    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  /**
   * An item release records a {@code MARKET_OFFER_RELEASED} audit event whose details carry only
   * the PII-free kind / product key / quantity — never the interessent or a free-text value
   * (REQ-MARKET-008).
   */
  @Test
  void releaseItem_recordsPiiFreeItemAuditDetails() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    when(blueprintProductService.resolveByProductKey("venture helmet"))
        .thenReturn(Optional.of(new ResolvedProduct("venture helmet", "Venture Helmet", null)));
    when(ownerScopeService.currentOrgUnit()).thenReturn(Optional.empty());
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.countByOfferId(any())).thenReturn(0L);
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    service.releaseItem(new MaterialExchangeItemReleaseRequest("venture helmet", 5, "gegen aUEC"));

    ArgumentCaptor<AuditDetails> detailsCaptor = ArgumentCaptor.captor();
    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_OFFER_RELEASED),
            any(),
            eq("Venture Helmet"),
            eq(ownerId),
            detailsCaptor.capture());
    String details = detailsCaptor.getValue().toString();
    assertThat(details).contains("kind=ITEM").contains("product=venture helmet").contains("qty=5");
    assertThat(details)
        .as("no display name or remark body leaks into the audit details")
        .doesNotContain("Venture Helmet")
        .doesNotContain("gegen aUEC");
  }

  /**
   * Re-releasing a Lager row that already carries an ACTIVE offer updates that same offer in place
   * (Gap 1): the existing offer is kept (same id/kind), its offered amount and release instant are
   * refreshed, no second offer is inserted, and the audit detail flags {@code reRelease=true} — so
   * a member re-releasing never double-advertises one item on the board.
   */
  @Test
  void release_existingActiveOffer_updatesInPlaceAndFlagsReRelease() {
    InventoryItem item = offer.getInventoryItem(); // stock = 340 SCU, owned by ownerId
    UUID itemId = item.getId();
    Instant past = Instant.now().minusSeconds(3600);
    offer.setReleasedAt(past);
    offer.setOfferedAmount(340.0);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    when(offerRepository.findByInventoryItemIdAndStatus(itemId, MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.of(offer));
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    service.release(new MaterialExchangeReleaseRequest(itemId, 120.0, "x"));

    ArgumentCaptor<MaterialExchangeOffer> captor = ArgumentCaptor.captor();
    verify(offerRepository).saveAndFlush(captor.capture());
    MaterialExchangeOffer saved = captor.getValue();
    assertThat(saved).as("re-release updates the existing offer in place").isSameAs(offer);
    assertThat(saved.getId()).isEqualTo(offerId);
    assertThat(saved.getKind()).isEqualTo(MaterialExchangeOfferKind.MATERIAL);
    assertThat(saved.getOfferedAmount()).isEqualTo(120.0);
    assertThat(saved.getReleasedAt()).as("release instant refreshed").isAfter(past);

    ArgumentCaptor<AuditDetails> detailsCaptor = ArgumentCaptor.captor();
    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_OFFER_RELEASED),
            eq(offerId),
            any(),
            eq(ownerId),
            detailsCaptor.capture());
    assertThat(detailsCaptor.getValue().toString()).contains("reRelease=true");
  }

  /**
   * A concurrent duplicate interest registration is swallowed as an idempotent success (Gap 2):
   * when the {@code REQUIRES_NEW} inner insert throws a {@link DataIntegrityViolationException}
   * (the unique {@code (offer, user)} constraint losing a race), the orchestrator catches it and
   * still returns the re-read offer detail instead of propagating a 500 (CLAUDE.md find-or-create
   * rule).
   */
  @Test
  void registerInterest_concurrentDuplicate_isIdempotent() {
    MaterialExchangeService spy = spy(service);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(selfProvider.getObject()).thenReturn(spy);
    doThrow(new DataIntegrityViolationException("uq"))
        .when(spy)
        .registerInterestInNewTransaction(offerId, otherId);
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(offer));

    MaterialExchangeOfferDto dto = service.registerInterest(offerId);

    assertThat(dto).as("duplicate race returns the offer detail, not a 500").isNotNull();
    assertThat(dto.id()).isEqualTo(offerId);
  }

  /**
   * A real withdrawal (a registration was removed) records exactly one {@code
   * MARKET_INTEREST_WITHDRAWN} audit event (Gap 3).
   */
  @Test
  void withdrawInterest_removed_recordsAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.deleteByOfferIdAndInterestedUserId(offerId, otherId)).thenReturn(1L);

    service.withdrawInterest(offerId);

    verify(auditService)
        .record(
            eq(AuditEventType.MARKET_INTEREST_WITHDRAWN), eq(offerId), any(), eq(ownerId), any());
  }

  /**
   * An idempotent no-op withdrawal (no registration existed, zero rows removed) records nothing —
   * keeping phantom withdrawals out of the append-only MARKET audit trail (Gap 3, REQ-AUDIT-001).
   */
  @Test
  void withdrawInterest_noRegistration_recordsNoAudit() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.deleteByOfferIdAndInterestedUserId(offerId, otherId)).thenReturn(0L);

    service.withdrawInterest(offerId);

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /** Deactivating another member's offer is forbidden and persists nothing (Gap 4). */
  @Test
  void deactivate_nonOwner_forbidden() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

    assertThatThrownBy(() -> service.deactivate(offerId)).isInstanceOf(AccessDeniedException.class);
    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /**
   * Deactivating an already-DEACTIVATED offer is idempotent (Gap 4): the status flip and the audit
   * event fire only while the offer is still ACTIVE, so a re-deactivation writes nothing and emits
   * no second {@code MARKET_OFFER_DEACTIVATED} event.
   */
  @Test
  void deactivate_alreadyDeactivated_noSecondAudit() {
    offer.setStatus(MaterialExchangeOfferStatus.DEACTIVATED);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    service.deactivate(offerId);

    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /**
   * Un-releasing a Lager row that carries no active offer raises a not-found (Gap 4): the
   * item-keyed deactivation entry point has nothing to take off the board.
   */
  @Test
  void deactivateForItem_noActiveOffer_notFound() {
    UUID itemId = offer.getInventoryItem().getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findByInventoryItemIdAndStatus(itemId, MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deactivateForItem(itemId))
        .isInstanceOf(NotFoundException.class);
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  // ---- stock-backed item offers (design §8, REQ-MARKET-014, ADR-0108) ----

  /**
   * Releasing a game-item Lager row creates a <b>stock-backed</b> ITEM offer (REQ-MARKET-014): the
   * offer carries the Lager row (bound to physical stock), stores the whole-unit quantity, derives
   * its product key / display name from the row's game item via {@code resolveByGameItem}, and
   * carries no {@code offeredAmount} — while the owner still sees the row's stock as {@code
   * availableAmount}.
   */
  @Test
  void release_gameItemRow_createsStockBackedItemOffer() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Quantum Drive");
    InventoryItem row = itemStockRow(owner, gameItem, 8.0);
    UUID itemId = row.getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(row));
    when(blueprintProductService.resolveByGameItem(gameItem.getId()))
        .thenReturn(
            Optional.of(new ResolvedProduct("quantum drive", "Quantum Drive", gameItem.getId())));
    when(offerRepository.findByInventoryItemIdAndStatus(itemId, MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.release(new MaterialExchangeReleaseRequest(itemId, 5.0, "gegen aUEC"));

    ArgumentCaptor<MaterialExchangeOffer> captor = ArgumentCaptor.captor();
    verify(offerRepository).saveAndFlush(captor.capture());
    MaterialExchangeOffer saved = captor.getValue();
    assertThat(saved.getKind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(saved.getInventoryItem()).as("stock-backed: bound to the Lager row").isSameAs(row);
    assertThat(saved.getItemProductKey()).isEqualTo("quantum drive");
    assertThat(saved.getItemName()).isEqualTo("Quantum Drive");
    assertThat(saved.getItemQuantity()).isEqualTo(5);
    assertThat(saved.getOfferedAmount()).as("item offers carry no offered amount").isNull();

    assertThat(dto.kind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(dto.itemName()).isEqualTo("Quantum Drive");
    assertThat(dto.itemQuantity()).isEqualTo(5);
    assertThat(dto.quality()).as("item offers carry no quality").isNull();
    assertThat(dto.availableAmount()).as("owner sees the row's stock").isEqualTo(8.0);
  }

  /** Offering more whole units than a game-item row holds is rejected (400) — nothing persisted. */
  @Test
  void release_gameItemRow_quantityExceedsStock_badRequest() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Cooler");
    InventoryItem row = itemStockRow(owner, gameItem, 3.0);
    UUID itemId = row.getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(row));

    assertThatThrownBy(
            () -> service.release(new MaterialExchangeReleaseRequest(itemId, 5.0, "zu viel")))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /** A non-whole item quantity is rejected (400) — item stock is integral. */
  @Test
  void release_gameItemRow_fractionalQuantity_badRequest() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Shield");
    InventoryItem row = itemStockRow(owner, gameItem, 6.0);
    UUID itemId = row.getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(row));

    assertThatThrownBy(
            () -> service.release(new MaterialExchangeReleaseRequest(itemId, 2.5, "krumm")))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
  }

  /** A game-item row not produced by any active blueprint cannot be offered (400). */
  @Test
  void release_gameItemRow_noBlueprintProduct_badRequest() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Loot Only");
    InventoryItem row = itemStockRow(owner, gameItem, 4.0);
    UUID itemId = row.getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(row));
    when(blueprintProductService.resolveByGameItem(gameItem.getId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.release(new MaterialExchangeReleaseRequest(itemId, 2.0, "x")))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  /**
   * Re-releasing a game-item row that already carries an ACTIVE offer updates that same offer in
   * place (one active offer per row via V210, REQ-MARKET-014) rather than inserting a duplicate.
   */
  @Test
  void release_gameItemRow_existingActiveOffer_reReleasesInPlace() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Power Plant");
    InventoryItem row = itemStockRow(owner, gameItem, 10.0);
    UUID itemId = row.getId();
    MaterialExchangeOffer existing = stockBackedItemOffer(offerId, row, owner, 4);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(inventoryItemRepository.findById(itemId)).thenReturn(Optional.of(row));
    when(blueprintProductService.resolveByGameItem(gameItem.getId()))
        .thenReturn(Optional.of(new ResolvedProduct("prod-key", "Power Plant", gameItem.getId())));
    when(offerRepository.findByInventoryItemIdAndStatus(itemId, MaterialExchangeOfferStatus.ACTIVE))
        .thenReturn(Optional.of(existing));
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    service.release(new MaterialExchangeReleaseRequest(itemId, 7.0, "mehr"));

    ArgumentCaptor<MaterialExchangeOffer> captor = ArgumentCaptor.captor();
    verify(offerRepository).saveAndFlush(captor.capture());
    MaterialExchangeOffer saved = captor.getValue();
    assertThat(saved).as("re-release updates the same offer").isSameAs(existing);
    assertThat(saved.getId()).isEqualTo(offerId);
    assertThat(saved.getItemQuantity()).isEqualTo(7);
  }

  /** The release picker returns a game-item row as an ITEM entry (PIECE unit, no quality). */
  @Test
  void myReleasableItems_returnsGameItemRowAsItemKind() {
    // covers REQ-MARKET-014
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    GameItem gameItem = gameItem("Power Plant");
    InventoryItem row = itemStockRow(owner, gameItem, 4.0);
    when(inventoryItemRepository.findReleasableForUser(
            any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of(row));
    when(offerRepository.findInventoryItemIdsWithStatus(any(), any())).thenReturn(Set.of());

    List<MaterialExchangeReleasableItemDto> items = boardService.myReleasableItems(null, null);

    assertThat(items).hasSize(1);
    MaterialExchangeReleasableItemDto dto = items.get(0);
    assertThat(dto.kind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(dto.materialName()).as("item name in the display slot").isEqualTo("Power Plant");
    assertThat(dto.quantityType()).isEqualTo(QuantityType.PIECE);
    assertThat(dto.quality()).as("item rows have no quality").isNull();
    assertThat(dto.amount()).isEqualTo(4.0);
  }

  /**
   * Editing a <b>stock-backed</b> item offer changes its whole-unit quantity — this was impossible
   * before REQ-MARKET-014: the pre-fix {@code updateOffer} unconditionally validated {@code
   * offeredAmount} against {@code offer.getInventoryItem()}, but read the wrong field on an item
   * offer. The new quantity is re-validated against the backing row's current stock.
   */
  @Test
  void updateOffer_stockBackedItemOffer_editsQuantity() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Cooler");
    InventoryItem row = itemStockRow(owner, gameItem, 10.0);
    MaterialExchangeOffer itemOffer = stockBackedItemOffer(offerId, row, owner, 6);
    itemOffer.setVersion(2L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(itemOffer));
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.updateOffer(offerId, new MaterialExchangeOfferUpdateRequest(9.0, "mehr", 2L));

    assertThat(itemOffer.getItemQuantity()).isEqualTo(9);
    assertThat(itemOffer.getRemark()).isEqualTo("mehr");
    assertThat(dto.kind()).isEqualTo(MaterialExchangeOfferKind.ITEM);
    assertThat(dto.itemQuantity()).isEqualTo(9);
  }

  /** Editing a stock-backed item offer above the backing row's current stock is rejected (400). */
  @Test
  void updateOffer_stockBackedItemOffer_quantityExceedsStock_badRequest() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Cooler");
    InventoryItem row = itemStockRow(owner, gameItem, 3.0);
    MaterialExchangeOffer itemOffer = stockBackedItemOffer(offerId, row, owner, 3);
    itemOffer.setVersion(1L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(itemOffer));

    assertThatThrownBy(
            () ->
                service.updateOffer(offerId, new MaterialExchangeOfferUpdateRequest(5.0, "x", 1L)))
        .isInstanceOf(BadRequestException.class);
    verify(offerRepository, never()).saveAndFlush(any());
  }

  /**
   * Editing a <b>free-stated</b> item offer (no backing Lager row) changes its quantity with no
   * stock cap — and, crucially, no longer NPEs on the null {@code inventoryItem} (the kind-aware
   * {@code updateOffer} fix, REQ-MARKET-014).
   */
  @Test
  void updateOffer_freeStatedItemOffer_editsQuantityWithoutNpe() {
    // covers REQ-MARKET-014
    MaterialExchangeOffer freeItem = new MaterialExchangeOffer();
    freeItem.setId(offerId);
    freeItem.setKind(MaterialExchangeOfferKind.ITEM);
    freeItem.setOwner(owner);
    freeItem.setItemProductKey("k");
    freeItem.setItemName("Venture Helmet");
    freeItem.setItemQuantity(3);
    freeItem.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    freeItem.setReleasedAt(Instant.now());
    freeItem.setVersion(1L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(freeItem));
    when(offerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    when(interestRepository.findByOfferIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

    MaterialExchangeOfferDto dto =
        service.updateOffer(offerId, new MaterialExchangeOfferUpdateRequest(7.0, "mehr", 1L));

    assertThat(freeItem.getItemQuantity()).isEqualTo(7);
    assertThat(dto.itemQuantity()).isEqualTo(7);
  }

  /**
   * The board clamps a stock-backed item offer's quantity to the backing row's current stock on
   * read (the item sibling of the material clamp-on-read, ADR-0086/0108): an offer that stated 6 on
   * a row since booked down to 2 shows only 2, and a non-owner never sees the row's available
   * stock.
   */
  @Test
  void detail_stockBackedItemOffer_clampsQuantityToCurrentStock() {
    // covers REQ-MARKET-014
    GameItem gameItem = gameItem("Shield");
    InventoryItem row = itemStockRow(owner, gameItem, 2.0); // booked down since release
    MaterialExchangeOffer itemOffer = stockBackedItemOffer(offerId, row, owner, 6);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.of(itemOffer));

    MaterialExchangeOfferDto dto = boardService.detail(offerId);

    assertThat(dto.itemQuantity()).as("clamped to current stock").isEqualTo(2);
    assertThat(dto.availableAmount()).as("non-owner never sees available stock").isNull();
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
    return material;
  }

  private static InventoryItem item(User owner, Material material, int quality, double amount) {
    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setUser(owner);
    item.setMaterial(material);
    item.setQuality(quality);
    item.setAmount(amount);
    return item;
  }

  private static MaterialExchangeOffer offer(UUID id, InventoryItem item, User owner) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setId(id);
    offer.setKind(MaterialExchangeOfferKind.MATERIAL);
    offer.setInventoryItem(item);
    offer.setOwner(owner);
    offer.setOfferedAmount(item.getAmount());
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    offer.setRemark("tausche gegen **Titanium**");
    offer.setVersion(0L);
    return offer;
  }

  private static GameItem gameItem(String name) {
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName(name);
    return gameItem;
  }

  private static InventoryItem itemStockRow(User owner, GameItem gameItem, double amount) {
    InventoryItem item = new InventoryItem();
    item.setId(UUID.randomUUID());
    item.setUser(owner);
    item.setGameItem(gameItem);
    item.setAmount(amount);
    return item;
  }

  private static MaterialExchangeOffer stockBackedItemOffer(
      UUID id, InventoryItem row, User owner, int quantity) {
    MaterialExchangeOffer offer = new MaterialExchangeOffer();
    offer.setId(id);
    offer.setKind(MaterialExchangeOfferKind.ITEM);
    offer.setInventoryItem(row);
    offer.setOwner(owner);
    offer.setItemProductKey("prod-key");
    offer.setItemName(row.getGameItem().getName());
    offer.setItemQuantity(quantity);
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    offer.setVersion(0L);
    return offer;
  }

  private static MaterialExchangeInterest interest(User user) {
    MaterialExchangeInterest interest = new MaterialExchangeInterest();
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

  private static SpecialCommand specialCommand(UUID id, String name, String shorthand) {
    SpecialCommand specialCommand = new SpecialCommand();
    specialCommand.setId(id);
    specialCommand.setName(name);
    specialCommand.setShorthand(shorthand);
    return specialCommand;
  }

  private static Bereich bereich(UUID id, String name, String shorthand) {
    Bereich bereich = new Bereich();
    bereich.setId(id);
    bereich.setName(name);
    bereich.setShorthand(shorthand);
    return bereich;
  }
}
