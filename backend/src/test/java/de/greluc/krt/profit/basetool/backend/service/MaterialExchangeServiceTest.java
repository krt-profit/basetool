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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.MaterialExchangeInterestRegisteredEvent;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.SquadronMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeInterest;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeOfferDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialExchangeRemarkUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeInterestRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit coverage for {@link MaterialExchangeService}'s security-critical behaviour: the
 * interessenten anonymity redaction (names only for the owner), the owner-only write gates, the
 * item-ownership check on release, the self-interest block, and the optimistic-lock guard on a
 * remark edit.
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
  @Mock private SquadronMapper squadronMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<MaterialExchangeService> selfProvider;

  @InjectMocks private MaterialExchangeService service;

  private final UUID ownerId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final UUID offerId = UUID.randomUUID();
  private User owner;
  private MaterialExchangeOffer offer;

  /** Builds a fresh owner + active offer fixture before each test. */
  @BeforeEach
  void setUp() {
    owner = user(ownerId, "Anbieter");
    Material material = material("Agricium");
    InventoryItem item = item(owner, material, 796, 340.0);
    offer = offer(offerId, item, owner);
    lenient().when(authHelperService.currentSquadronId()).thenReturn(Optional.empty());
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

    MaterialExchangeOfferDto dto = service.detail(offerId);

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

    MaterialExchangeOfferDto dto = service.detail(offerId);

    assertThat(dto.mine()).isTrue();
    assertThat(dto.interestCount()).isEqualTo(2);
    assertThat(dto.interestedHandles()).containsExactly("Mara", "Hex");
  }

  /** Releasing an item the caller does not own is forbidden. */
  @Test
  void release_notItemOwner_forbidden() {
    UUID itemId = offer.getInventoryItem().getId();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(inventoryItemRepository.findById(itemId))
        .thenReturn(Optional.of(offer.getInventoryItem()));

    assertThatThrownBy(
            () -> service.release(new MaterialExchangeReleaseRequest(itemId, "tausche gegen X")))
        .isInstanceOf(AccessDeniedException.class);
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

  /** A stale version on a remark edit raises an optimistic-lock conflict (→ 409). */
  @Test
  void updateRemark_staleVersion_conflict() {
    offer.setVersion(5L);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.findById(offerId)).thenReturn(Optional.of(offer));

    assertThatThrownBy(
            () -> service.updateRemark(offerId, new MaterialExchangeRemarkUpdateRequest("neu", 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(offerRepository, never()).saveAndFlush(any());
  }

  /** The tab counts return the board total and the caller's own active-offer count. */
  @Test
  void counts_returnsAllAndMine() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ownerId));
    when(offerRepository.countByStatus(MaterialExchangeOfferStatus.ACTIVE)).thenReturn(7L);
    when(offerRepository.countByStatusAndOwnerId(MaterialExchangeOfferStatus.ACTIVE, ownerId))
        .thenReturn(3L);

    var counts = service.counts();

    assertThat(counts.all()).isEqualTo(7);
    assertThat(counts.mine()).isEqualTo(3);
  }

  /** A missing offer on detail raises a not-found. */
  @Test
  void detail_missingOffer_notFound() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(otherId));
    when(offerRepository.findWithDetailById(offerId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(offerId)).isInstanceOf(NotFoundException.class);
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
    offer.setInventoryItem(item);
    offer.setOwner(owner);
    offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
    offer.setReleasedAt(Instant.now());
    offer.setRemark("tausche gegen **Titanium**");
    offer.setVersion(0L);
    return offer;
  }

  private static MaterialExchangeInterest interest(User user) {
    MaterialExchangeInterest interest = new MaterialExchangeInterest();
    interest.setId(UUID.randomUUID());
    interest.setInterestedUser(user);
    return interest;
  }
}
