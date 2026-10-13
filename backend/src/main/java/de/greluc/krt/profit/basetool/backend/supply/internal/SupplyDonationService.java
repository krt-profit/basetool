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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.supply.api.DeclineReason;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemCode;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyProblemException;
import de.greluc.krt.profit.basetool.backend.supply.api.events.SupplyNotices;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.JoinType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pledges, receives, declines and records the members' material donations (REQ-SUP-007 …
 * REQ-SUP-012, REQ-SUP-015 … REQ-SUP-017). Every stock movement goes through {@link StockCommands}
 * under the lot-lock protocol, in the donation's transaction; the donation module writes no stock
 * row of its own.
 */
@Service
@RequiredArgsConstructor
public class SupplyDonationService {

  private final SupplyDonationRepository donationRepository;
  private final SupplyNeedRepository needRepository;
  private final SupplyUnitSettingRepository unitSettingRepository;
  private final SupplyNeedService needService;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final LocationRepository locationRepository;
  private final UserRepository userRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final StockCommands stockCommands;
  private final StockReads stockReads;
  private final SupplySettingsService settingsService;
  private final SupplyAccessPolicy accessPolicy;
  private final SupplyMapper mapper;
  private final AuditRecorder auditRecorder;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;
  private final MeterRegistry meterRegistry;

  /**
   * Pages over the donations a unit received or was pledged, for the unit's managers and the
   * governing leadership.
   *
   * @param orgUnitId the receiving unit
   * @param statuses only these states, or empty for all
   * @param pageable page, size and sort
   * @return one page of donations
   */
  @Transactional(readOnly = true)
  public @NotNull Page<DonationDto> listForUnit(
      @NotNull UUID orgUnitId, @NotNull Set<DonationStatus> statuses, @NotNull Pageable pageable) {
    settingsService.requireEnabled();
    if (!accessPolicy.canManageUnit(orgUnitId) && !accessPolicy.isGoverningLeadership()) {
      throw new AccessDeniedException("The caller does not manage this unit's donations");
    }
    Specification<SupplyDonation> spec =
        fetchCatalogue()
            .and((root, query, cb) -> cb.equal(root.get("receivingOrgUnitId"), orgUnitId));
    if (!statuses.isEmpty()) {
      spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
    }
    return mapPage(donationRepository.findAll(spec, pageable));
  }

  /**
   * Pages over the caller's own donations.
   *
   * @param statuses only these states, or empty for all
   * @param pageable page, size and sort
   * @return one page of the caller's donations
   */
  @Transactional(readOnly = true)
  public @NotNull Page<DonationDto> listMine(
      @NotNull Set<DonationStatus> statuses, @NotNull Pageable pageable) {
    settingsService.requireEnabled();
    UUID caller = requireCaller();
    Specification<SupplyDonation> spec =
        fetchCatalogue().and((root, query, cb) -> cb.equal(root.get("donorUserId"), caller));
    if (!statuses.isEmpty()) {
      spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
    }
    return mapPage(donationRepository.findAll(spec, pageable));
  }

  /**
   * Pledges a donation (REQ-SUP-007, REQ-SUP-008).
   *
   * @param request the pledge
   * @return the pledged donation
   */
  @Transactional
  public @NotNull DonationDto pledge(@NotNull PledgeRequest request) {
    settingsService.requireEnabled();
    UUID donor = requireCaller();
    SupplyNeed need = request.needId() == null ? null : loadOpenNeed(request.needId());
    UUID unitId = unitOf(need, request.receivingOrgUnitId());
    requireAccepts(unitId);

    StockRow row = null;
    Material material = need == null ? null : need.getMaterial();
    GameItem item = need == null ? null : need.getGameItem();
    Integer quality = request.quality();
    if (request.sourceInventoryItemId() != null) {
      row = ownedUnstolenRow(request.sourceInventoryItemId(), donor);
      material = row.getMaterial();
      item = row.getGameItem();
      quality = row.getQuality();
      stockCommands.lockLots(donor, List.of(lotOf(row).key()));
      row =
          Entities.require(
              stockCommands.lockRow(request.sourceInventoryItemId()), "Stock row not found");
    } else if (need == null) {
      material = request.materialId() == null ? null : requireMaterial(request.materialId());
      item = request.gameItemId() == null ? null : requireGameItem(request.gameItemId());
    }
    if ((material == null) == (item == null)) {
      throw new BadRequestException("Name exactly one of material and game item");
    }
    if (need != null) {
      requireFitsNeed(need, material, item, quality);
    }
    double amount = validAmount(material, item, request.amount());

    SupplyDonation donation = new SupplyDonation();
    donation.setDonorUserId(donor);
    donation.setAnonymous(request.anonymous());
    donation.setReceivingOrgUnitId(unitId);
    donation.setMaterial(material);
    donation.setGameItem(item);
    donation.setQuality(material == null ? null : quality == null ? 0 : quality);
    donation.setPledgedAmount(amount);
    donation.setNeedId(need == null ? null : need.getId());
    donation.setSourceInventoryItemId(row == null ? null : row.getId());
    donation.setStatus(DonationStatus.PLEDGED);
    SupplyDonation saved = donationRepository.save(donation);
    if (row != null) {
      stockCommands.earmarkForDonation(row.getId(), saved.getId(), amount);
    }
    auditRecorder.record(
        AuditEventType.DONATION_PLEDGED,
        saved.getId(),
        label(saved),
        saved.isAnonymous() ? null : donor,
        AuditDetails.of("unit", unitId)
            .with("amount", amount)
            .with("need", saved.getNeedId())
            .with("fromStock", row != null)
            .with("anonymous", saved.isAnonymous()));
    OrgUnit unit = requireUnit(unitId);
    eventPublisher.publishEvent(
        SupplyNotices.pledged(
            saved.getId(),
            unitId,
            unit.getKind(),
            unit.getName(),
            label(saved),
            plain(amount),
            saved.isAnonymous() ? null : requireUser(donor).getEffectiveName(),
            donor));
    count("PLEDGED");
    return one(saved);
  }

  /**
   * Records a donation the unit already received and books it into the pool (REQ-SUP-011).
   *
   * @param request the received donation
   * @return the recorded donation
   */
  @Transactional
  public @NotNull DonationDto record(@NotNull RecordDonationRequest request) {
    settingsService.requireEnabled();
    final UUID holder = requireCaller();
    UUID unitId = request.receivingOrgUnitId();
    requireManager(unitId);
    requireAccepts(unitId);
    SupplyNeed need = request.needId() == null ? null : loadOpenNeed(request.needId());
    if (need != null && !need.getOrgUnitId().equals(unitId)) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_NEED_UNIT_MISMATCH, "The need belongs to another unit");
    }
    Material material =
        need != null
            ? need.getMaterial()
            : request.materialId() == null ? null : requireMaterial(request.materialId());
    GameItem item =
        need != null
            ? need.getGameItem()
            : request.gameItemId() == null ? null : requireGameItem(request.gameItemId());
    if ((material == null) == (item == null)) {
      throw new BadRequestException("Name exactly one of material and game item");
    }
    Integer quality = material == null ? null : request.quality() == null ? 0 : request.quality();
    if (need != null) {
      requireFitsNeed(need, material, item, quality);
    }
    double amount = validAmount(material, item, request.amount());
    if (request.donorUserId() != null) {
      requireUser(request.donorUserId());
    }
    Location location =
        Entities.require(locationRepository.findById(request.locationId()), "Location not found");
    StockLot lot = new StockLot(material, item, location, quality, false);
    stockCommands.lockLots(holder, List.of(lot.key()));
    OrgUnit unit = requireUnit(unitId);
    stockCommands.bookIntoPool(holder, unit, lot, amount, StockBookingReason.DONATION);

    SupplyDonation donation = new SupplyDonation();
    donation.setDonorUserId(request.donorUserId());
    donation.setAnonymous(request.anonymous());
    donation.setReceivingOrgUnitId(unitId);
    donation.setMaterial(material);
    donation.setGameItem(item);
    donation.setQuality(quality);
    donation.setPledgedAmount(amount);
    donation.setReceivedAmount(amount);
    donation.setNeedId(need == null ? null : need.getId());
    donation.setStatus(DonationStatus.RECEIVED);
    donation.setReceivedBy(holder);
    donation.setReceivedAt(Instant.now());
    SupplyDonation saved = donationRepository.save(donation);
    if (need != null) {
      needService.credit(need.getId(), amount);
    }
    auditRecorder.record(
        AuditEventType.DONATION_RECORDED,
        saved.getId(),
        label(saved),
        saved.isAnonymous() ? null : saved.getDonorUserId(),
        AuditDetails.of("unit", unitId).with("amount", amount).with("need", saved.getNeedId()));
    if (saved.getDonorUserId() != null) {
      eventPublisher.publishEvent(
          SupplyNotices.received(
              saved.getId(),
              saved.getDonorUserId(),
              unit.getName(),
              label(saved),
              plain(amount),
              holder));
    }
    count("RECORDED");
    return one(saved);
  }

  /**
   * Confirms the receipt of a pledged donation, in full or in part; the stock moves into the unit's
   * pool in the same transaction (REQ-SUP-009).
   *
   * @param id the donation
   * @param request the received amount, the location and the echoed version
   * @return the received donation
   */
  @Transactional
  public @NotNull DonationDto receive(@NotNull UUID id, @NotNull ReceiveDonationRequest request) {
    settingsService.requireEnabled();
    final UUID holder = requireCaller();
    SupplyDonation donation = lockPledged(id, request.version());
    UUID unitId = donation.getReceivingOrgUnitId();
    requireManager(unitId);
    double received = ScuAmounts.roundToScuScale(request.receivedAmount());
    if (received <= 0.0 || received > donation.getPledgedAmount() + 5e-4) {
      throw new BadRequestException("The received amount must be between zero and the pledge");
    }
    validAmount(donation.getMaterial(), donation.getGameItem(), received);
    Location location =
        Entities.require(locationRepository.findById(request.locationId()), "Location not found");
    OrgUnit unit = requireUnit(unitId);

    StockRow source =
        donation.getSourceInventoryItemId() == null
            ? null
            : stockReads.findRow(donation.getSourceInventoryItemId()).orElse(null);
    StockLot targetLot =
        new StockLot(
            donation.getMaterial(), donation.getGameItem(), location, donation.getQuality(), false);
    if (source != null) {
      lockBoth(
          donation.getDonorUserId() == null ? source.getUser().getId() : donation.getDonorUserId(),
          lotOf(source),
          holder,
          targetLot);
      stockCommands.lockRow(source.getId());
      stockCommands.releaseDonationEarmark(donation.getId());
      stockCommands.transferToPool(
          source.getId(), received, unit, holder, location, StockBookingReason.DONATION);
    } else {
      stockCommands.lockLots(holder, List.of(targetLot.key()));
      stockCommands.bookIntoPool(holder, unit, targetLot, received, StockBookingReason.DONATION);
    }
    boolean full = received >= donation.getPledgedAmount() - 5e-4;
    donation.setReceivedAmount(received);
    donation.setStatus(full ? DonationStatus.RECEIVED : DonationStatus.PARTIALLY_RECEIVED);
    donation.setReceivedBy(holder);
    donation.setReceivedAt(Instant.now());
    SupplyDonation saved = donationRepository.saveAndFlush(donation);
    if (saved.getNeedId() != null) {
      needService.credit(saved.getNeedId(), received);
    }
    auditRecorder.record(
        AuditEventType.DONATION_RECEIVED,
        saved.getId(),
        label(saved),
        saved.isAnonymous() ? null : saved.getDonorUserId(),
        AuditDetails.of("unit", unitId)
            .with("amount", received)
            .with("pledged", saved.getPledgedAmount())
            .with("need", saved.getNeedId()));
    eventPublisher.publishEvent(
        SupplyNotices.received(
            saved.getId(),
            saved.getDonorUserId(),
            unit.getName(),
            label(saved),
            plain(received),
            holder));
    count(saved.getStatus().name());
    return one(saved);
  }

  /**
   * Declines a pledged donation with a reason code and releases its earmark (REQ-SUP-010).
   *
   * @param id the donation
   * @param request the reason and the echoed version
   * @return the declined donation
   */
  @Transactional
  public @NotNull DonationDto decline(@NotNull UUID id, @NotNull DeclineDonationRequest request) {
    settingsService.requireEnabled();
    final UUID actor = requireCaller();
    SupplyDonation donation = lockPledged(id, request.version());
    requireManager(donation.getReceivingOrgUnitId());
    release(donation);
    donation.setStatus(DonationStatus.DECLINED);
    donation.setDeclineReason(request.reason());
    SupplyDonation saved = donationRepository.saveAndFlush(donation);
    auditRecorder.record(
        AuditEventType.DONATION_DECLINED,
        saved.getId(),
        label(saved),
        saved.isAnonymous() ? null : saved.getDonorUserId(),
        AuditDetails.of("unit", saved.getReceivingOrgUnitId())
            .with("reason", request.reason().name()));
    eventPublisher.publishEvent(
        SupplyNotices.declined(
            saved.getId(),
            saved.getDonorUserId(),
            requireUnit(saved.getReceivingOrgUnitId()).getName(),
            label(saved),
            request.reason() == null ? DeclineReason.OTHER : request.reason(),
            actor));
    count("DECLINED");
    return one(saved);
  }

  /**
   * Withdraws an unreceived pledge and releases its earmark (REQ-SUP-010).
   *
   * @param id the donation
   * @param version the echoed version
   * @return the withdrawn donation
   */
  @Transactional
  public @NotNull DonationDto withdraw(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    final UUID actor = requireCaller();
    SupplyDonation donation = lockPledged(id, version);
    if (!accessPolicy.canWithdrawPledge(donation.getDonorUserId(), actor)) {
      throw new AccessDeniedException("Only the donor may withdraw a pledge");
    }
    release(donation);
    donation.setStatus(DonationStatus.WITHDRAWN);
    SupplyDonation saved = donationRepository.saveAndFlush(donation);
    auditRecorder.record(
        AuditEventType.DONATION_WITHDRAWN,
        saved.getId(),
        label(saved),
        saved.isAnonymous() ? null : saved.getDonorUserId(),
        AuditDetails.of("unit", saved.getReceivingOrgUnitId()));
    eventPublisher.publishEvent(SupplyNotices.withdrawn(saved.getId(), actor));
    count("WITHDRAWN");
    return one(saved);
  }

  private void release(@NotNull SupplyDonation donation) {
    if (donation.getSourceInventoryItemId() != null) {
      stockReads
          .findRow(donation.getSourceInventoryItemId())
          .ifPresent(
              row -> stockCommands.lockLots(row.getUser().getId(), List.of(lotOf(row).key())));
      stockCommands.releaseDonationEarmark(donation.getId());
    }
  }

  private record Locked(UUID member, StockLot lot) {}

  private void lockBoth(
      @NotNull UUID firstMember,
      @NotNull StockLot firstLot,
      @NotNull UUID secondMember,
      @NotNull StockLot secondLot) {
    if (firstMember.equals(secondMember)) {
      stockCommands.lockLots(firstMember, List.of(firstLot.key(), secondLot.key()));
      return;
    }
    List<Locked> ordered =
        List.of(new Locked(firstMember, firstLot), new Locked(secondMember, secondLot)).stream()
            .sorted(Comparator.comparing(Locked::member))
            .toList();
    for (Locked locked : ordered) {
      stockCommands.lockLots(locked.member(), List.of(locked.lot().key()));
    }
  }

  private @NotNull SupplyDonation lockPledged(@NotNull UUID id, long version) {
    SupplyDonation donation =
        Entities.require(donationRepository.findByIdForUpdate(id), "Donation not found");
    OptimisticLock.checkRequired(donation.getVersion(), version, SupplyDonation.class, id);
    if (donation.getStatus() != DonationStatus.PLEDGED) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_DONATION_NOT_PLEDGED, "The donation is no longer pledged");
    }
    return donation;
  }

  private @NotNull SupplyNeed loadOpenNeed(@NotNull UUID needId) {
    SupplyNeed need = Entities.require(needRepository.findById(needId), "Need not found");
    if (!SupplyMapper.acceptsPledges(need.getStatus())) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_NEED_NOT_OPEN, "The need is not open");
    }
    return need;
  }

  private static UUID unitOf(@Nullable SupplyNeed need, @Nullable UUID requested) {
    if (need != null) {
      if (requested != null && !requested.equals(need.getOrgUnitId())) {
        throw new SupplyProblemException(
            SupplyProblemCode.SUPPLY_NEED_UNIT_MISMATCH, "The need belongs to another unit");
      }
      return need.getOrgUnitId();
    }
    if (requested == null) {
      throw new BadRequestException("Name the receiving unit or a need");
    }
    return requested;
  }

  private void requireAccepts(@NotNull UUID unitId) {
    boolean accepts =
        unitSettingRepository
            .findById(unitId)
            .map(SupplyUnitSetting::isAcceptsDonations)
            .orElse(false);
    if (!accepts) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_UNIT_CANNOT_RECEIVE, "The unit does not accept donations");
    }
  }

  private void requireManager(@NotNull UUID unitId) {
    if (!accessPolicy.canManageUnit(unitId)) {
      throw new AccessDeniedException("The caller does not manage this unit's donations");
    }
  }

  private static void requireFitsNeed(
      @NotNull SupplyNeed need,
      @Nullable Material material,
      @Nullable GameItem item,
      @Nullable Integer quality) {
    boolean sameEntry =
        material != null
            ? need.getMaterial() != null && need.getMaterial().getId().equals(material.getId())
            : item != null
                && need.getGameItem() != null
                && need.getGameItem().getId().equals(item.getId());
    boolean tierOk =
        material == null
            || need.getQualityTier() == null
            || need.getQualityTier().isSatisfiedBy(quality);
    if (!sameEntry || !tierOk) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_DONATION_DOES_NOT_FIT_NEED,
          "The donation does not fit the need");
    }
  }

  private @NotNull StockRow ownedUnstolenRow(@NotNull UUID rowId, @NotNull UUID donor) {
    StockRow row = Entities.require(stockReads.findRow(rowId), "Stock row not found");
    if (!donor.equals(row.getUser().getId())) {
      throw new SupplyProblemException(
          SupplyProblemCode.SUPPLY_ROW_NOT_OWNED, "The stock row does not belong to the donor");
    }
    if (Boolean.TRUE.equals(row.getStolen())) {
      throw new BadRequestException("Stolen stock cannot be donated");
    }
    return row;
  }

  private static @NotNull StockLot lotOf(@NotNull StockRow row) {
    return new StockLot(
        row.getMaterial(),
        row.getGameItem(),
        row.getLocation(),
        row.getQuality(),
        Boolean.TRUE.equals(row.getStolen()));
  }

  private static double validAmount(
      @Nullable Material material, @Nullable GameItem item, double amount) {
    double rounded = ScuAmounts.roundToScuScale(amount);
    if (rounded <= 0.0) {
      throw new BadRequestException("The amount must be positive");
    }
    boolean whole =
        item != null || (material != null && material.getQuantityType() == QuantityType.PIECE);
    if (whole && rounded % 1 != 0) {
      throw new BadRequestException("This catalogue entry is counted in whole units");
    }
    return rounded;
  }

  private @NotNull Material requireMaterial(@NotNull UUID id) {
    return Entities.require(materialRepository.findById(id), "Material not found");
  }

  private @NotNull GameItem requireGameItem(@NotNull UUID id) {
    return Entities.require(gameItemRepository.findById(id), "Game item not found");
  }

  private @NotNull OrgUnit requireUnit(@NotNull UUID id) {
    return Entities.require(orgUnitRepository.findById(id), "Org unit not found");
  }

  private @NotNull User requireUser(@NotNull UUID id) {
    return Entities.require(userRepository.findById(id), "User not found");
  }

  private @NotNull UUID requireCaller() {
    return authHelperService
        .currentUserId()
        .orElseThrow(() -> new AccessDeniedException("No authenticated member"));
  }

  private @NotNull DonationDto one(@NotNull SupplyDonation donation) {
    return mapper.toDto(donation, mapper.donationContext(List.of(donation)));
  }

  private @NotNull Page<DonationDto> mapPage(@NotNull Page<SupplyDonation> page) {
    SupplyMapper.DonationContext context = mapper.donationContext(page.getContent());
    return page.map(donation -> mapper.toDto(donation, context));
  }

  private static @NotNull String label(@NotNull SupplyDonation donation) {
    if (donation.getMaterial() != null) {
      return donation.getMaterial().getName();
    }
    return donation.getGameItem() == null ? "—" : donation.getGameItem().getName();
  }

  private static @NotNull String plain(double amount) {
    return amount % 1 == 0 ? Long.toString((long) amount) : Double.toString(amount);
  }

  private void count(@NotNull String status) {
    meterRegistry.counter(MetricNames.SUPPLY_DONATIONS, MetricNames.TAG_STATUS, status).increment();
  }

  private static @NotNull Specification<SupplyDonation> fetchCatalogue() {
    return (root, query, cb) -> {
      if (query != null
          && query.getResultType() != Long.class
          && query.getResultType() != long.class) {
        root.fetch("material", JoinType.LEFT);
        root.fetch("gameItem", JoinType.LEFT);
      }
      return cb.conjunction();
    };
  }
}
