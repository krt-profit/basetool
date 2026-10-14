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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The partners of the trade deals (REQ-PUR-004, REQ-PUR-006). The delegates, the governing
 * leadership and admin maintain them; the notes are returned to the same circle only. Audit rows
 * and their details carry the kind and the id, never a name, a handle or a note (REQ-PUR-025).
 */
@Service
@RequiredArgsConstructor
public class PurchasingPartnerService {

  private final PurchasingPartnerRepository partnerRepository;
  private final PurchasingDealRepository dealRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;

  /**
   * Pages over the partners, filtered by a name search, the kind and the status.
   *
   * @param query a name fragment, or {@code null}
   * @param kind only this kind, or {@code null}
   * @param status only this status, or {@code null}
   * @param pageable page, size and sort
   * @return one page of partners with their history
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<PartnerDto> list(
      @Nullable String query,
      @Nullable PartnerKind kind,
      @Nullable PartnerStatus status,
      @NotNull Pageable pageable) {
    settingsService.requireEnabled();
    requireMaintainer();
    Specification<PurchasingPartner> spec = (root, q, cb) -> cb.conjunction();
    if (query != null && !query.isBlank()) {
      String pattern = LikePatterns.contains(query.strip().toLowerCase(Locale.ROOT));
      spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
    }
    if (kind != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("kind"), kind));
    }
    if (status != null) {
      spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
    }
    Page<PurchasingPartner> page = partnerRepository.findAll(spec, pageable);
    Map<UUID, Long> deals = counts(page.getContent(), false);
    Map<UUID, Long> clean = counts(page.getContent(), true);
    return page.map(partner -> toDto(partner, deals, clean, accessPolicy.canReadPartnerNotes()));
  }

  /**
   * Reads one partner.
   *
   * @param id the partner
   * @return the partner with its history
   */
  @NotNull
  @Transactional(readOnly = true)
  public PartnerDto get(@NotNull UUID id) {
    settingsService.requireEnabled();
    requireMaintainer();
    PurchasingPartner partner =
        Entities.require(partnerRepository.findById(id), "Partner not found");
    return toDto(partner, counts(List.of(partner), false), counts(List.of(partner), true), true);
  }

  /**
   * Creates a partner.
   *
   * @param request the partner
   * @return the created partner
   */
  @NotNull
  @Transactional
  public PartnerDto create(@NotNull CreatePartnerRequest request) {
    settingsService.requireEnabled();
    requireMaintainer();
    PurchasingPartner partner = new PurchasingPartner();
    partner.setKind(request.kind());
    apply(
        partner,
        request.name(),
        request.orgTag(),
        request.channel(),
        request.conditionsNote(),
        request.reputationNote(),
        request.contactVia());
    partner.setStatus(PartnerStatus.ACTIVE);
    partner.setCreatedBy(authHelperService.currentUserId().orElse(null));
    PurchasingPartner saved = partnerRepository.saveAndFlush(partner);
    auditRecorder.record(
        AuditEventType.PARTNER_CREATED,
        saved.getId(),
        label(saved),
        null,
        AuditDetails.of("kind", saved.getKind().name()));
    return toDto(saved, Map.of(), Map.of(), true);
  }

  /**
   * Edits a partner.
   *
   * @param id the partner
   * @param request the new values and the echoed version
   * @return the edited partner
   */
  @NotNull
  @Transactional
  public PartnerDto update(@NotNull UUID id, @NotNull UpdatePartnerRequest request) {
    settingsService.requireEnabled();
    requireMaintainer();
    PurchasingPartner partner = load(id, request.version());
    if (partner.isErased()) {
      throw new BadRequestException("An erased partner cannot be edited");
    }
    apply(
        partner,
        request.name(),
        request.orgTag(),
        request.channel(),
        request.conditionsNote(),
        request.reputationNote(),
        request.contactVia());
    PurchasingPartner saved = partnerRepository.saveAndFlush(partner);
    auditRecorder.record(
        AuditEventType.PARTNER_UPDATED,
        saved.getId(),
        label(saved),
        null,
        AuditDetails.of("kind", saved.getKind().name()));
    return toDto(saved, counts(List.of(saved), false), counts(List.of(saved), true), true);
  }

  /**
   * Blocks a partner so no new deal or tranche may use it (REQ-PUR-004).
   *
   * @param id the partner
   * @param version the echoed version
   * @return the blocked partner
   */
  @NotNull
  @Transactional
  public PartnerDto block(@NotNull UUID id, long version) {
    return changeStatus(id, version, PartnerStatus.BLOCKED, AuditEventType.PARTNER_BLOCKED);
  }

  /**
   * Lifts the block of a partner.
   *
   * @param id the partner
   * @param version the echoed version
   * @return the unblocked partner
   */
  @NotNull
  @Transactional
  public PartnerDto unblock(@NotNull UUID id, long version) {
    return changeStatus(id, version, PartnerStatus.ACTIVE, AuditEventType.PARTNER_UNBLOCKED);
  }

  /**
   * Erases an individual's handle and notes on request; the deals stay with a placeholder
   * (REQ-PUR-006). Only an admin erases.
   *
   * @param id the partner
   * @param version the echoed version
   * @return the erased partner
   * @throws AccessDeniedException when the caller is no admin
   * @throws BadRequestException when the partner is an organisation
   */
  @NotNull
  @Transactional
  public PartnerDto erase(@NotNull UUID id, long version) {
    settingsService.requireEnabled();
    if (!accessPolicy.isAdmin()) {
      throw new AccessDeniedException("Only an admin erases a partner");
    }
    PurchasingPartner partner = load(id, version);
    if (partner.getKind() != PartnerKind.INDIVIDUAL) {
      throw new BadRequestException("Only an individual is erased");
    }
    partner.setName(PurchasingPartner.ERASED_NAME);
    partner.setConditionsNote(null);
    partner.setReputationNote(null);
    partner.setOrgTag(null);
    partner.setErased(true);
    partner.setStatus(PartnerStatus.BLOCKED);
    PurchasingPartner saved = partnerRepository.saveAndFlush(partner);
    auditRecorder.record(AuditEventType.PARTNER_ERASED, saved.getId(), label(saved), null, null);
    return toDto(saved, counts(List.of(saved), false), counts(List.of(saved), true), true);
  }

  private @NotNull PartnerDto changeStatus(
      @NotNull UUID id,
      long version,
      @NotNull PartnerStatus status,
      @NotNull AuditEventType eventType) {
    settingsService.requireEnabled();
    requireMaintainer();
    PurchasingPartner partner = load(id, version);
    if (partner.isErased()) {
      throw new BadRequestException("An erased partner stays blocked");
    }
    partner.setStatus(status);
    PurchasingPartner saved = partnerRepository.saveAndFlush(partner);
    auditRecorder.record(eventType, saved.getId(), label(saved), null, null);
    return toDto(saved, counts(List.of(saved), false), counts(List.of(saved), true), true);
  }

  private void apply(
      @NotNull PurchasingPartner partner,
      @NotNull String name,
      @Nullable String orgTag,
      @NotNull PartnerChannel channel,
      @Nullable String conditionsNote,
      @Nullable String reputationNote,
      @NotNull PartnerContact contactVia) {
    partner.setName(name.strip());
    partner.setOrgTag(partner.getKind() == PartnerKind.ORG ? blankToNull(orgTag) : null);
    partner.setChannel(channel);
    partner.setConditionsNote(blankToNull(conditionsNote));
    partner.setReputationNote(blankToNull(reputationNote));
    partner.setContactVia(contactVia);
  }

  private @NotNull PurchasingPartner load(@NotNull UUID id, long version) {
    PurchasingPartner partner =
        Entities.require(partnerRepository.findById(id), "Partner not found");
    OptimisticLock.checkRequired(partner.getVersion(), version, PurchasingPartner.class, id);
    return partner;
  }

  private void requireMaintainer() {
    if (!accessPolicy.canMaintainPartners()) {
      throw new AccessDeniedException(
          "Only delegates and the governing leadership maintain partners");
    }
  }

  private @NotNull Map<UUID, Long> counts(
      @NotNull List<PurchasingPartner> partners, boolean cleanSettledOnly) {
    if (partners.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = new ArrayList<>();
    for (PurchasingPartner partner : partners) {
      ids.add(partner.getId());
    }
    List<PartnerCountRow> rows =
        cleanSettledOnly
            ? dealRepository.countCleanSettledByPartners(ids)
            : dealRepository.countByPartners(ids);
    Map<UUID, Long> byId = new HashMap<>();
    for (PartnerCountRow row : rows) {
      byId.put(row.partnerId(), row.count());
    }
    return byId;
  }

  private static @NotNull PartnerDto toDto(
      @NotNull PurchasingPartner partner,
      @NotNull Map<UUID, Long> deals,
      @NotNull Map<UUID, Long> clean,
      boolean notes) {
    return new PartnerDto(
        partner.getId(),
        partner.getKind(),
        partner.getName(),
        partner.getOrgTag(),
        partner.getChannel(),
        notes ? partner.getConditionsNote() : null,
        notes ? partner.getReputationNote() : null,
        partner.getContactVia(),
        partner.getStatus(),
        partner.isErased(),
        deals.getOrDefault(partner.getId(), 0L),
        clean.getOrDefault(partner.getId(), 0L),
        partner.getVersion());
  }

  private static @NotNull String label(@NotNull PurchasingPartner partner) {
    return "Partner " + partner.getKind().name();
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
