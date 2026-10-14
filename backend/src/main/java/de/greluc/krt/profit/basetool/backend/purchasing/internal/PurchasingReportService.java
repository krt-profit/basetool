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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The purchasing report (REQ-PUR-023): the deals that reached the bank, summed per month, per
 * material or item and per need, as figures, as a CSV file and as a forum-safe text block. Nothing
 * in it names a member or a partner. A caller sees the deals they may read.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchasingReportService {

  private static final int MAX_MONTHS = 36;
  private static final Set<DealStatus> REACHED_THE_BANK =
      Set.of(
          DealStatus.SUBMITTED,
          DealStatus.FUNDED,
          DealStatus.IN_DELIVERY,
          DealStatus.DELIVERED,
          DealStatus.SETTLED,
          DealStatus.FAILED);

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDealDeliveryRepository deliveryRepository;
  private final PurchasingDealSettlementRepository settlementRepository;
  private final PurchasingDealService dealService;
  private final PurchasingAccessPolicy accessPolicy;
  private final PurchasingSettingsService settingsService;
  private final SupplyNeeds supplyNeeds;
  private final OrgUnitRepository orgUnitRepository;
  private final MessageSource messageSource;

  /**
   * Builds the report of a period.
   *
   * @param from the first month, or {@code null} for eleven months before {@code to}
   * @param to the last month, or {@code null} for the current month
   * @return the report
   */
  @NotNull
  public PurchasingReportDto report(@Nullable YearMonth from, @Nullable YearMonth to) {
    return build(period(from, to)).report();
  }

  /**
   * Builds the CSV file of a period: one line per deal, no names.
   *
   * @param from the first month, or {@code null}
   * @param to the last month, or {@code null}
   * @return the CSV text, semicolon separated
   */
  @NotNull
  public String csv(@Nullable YearMonth from, @Nullable YearMonth to) {
    Built built = build(period(from, to));
    StringBuilder out = new StringBuilder(label("purchasing.report.csv.header")).append('\n');
    for (Figures figures : built.figures()) {
      PurchasingDeal deal = figures.deal();
      out.append(deal.getDealNo())
          .append(';')
          .append(month(deal))
          .append(';')
          .append(csvCell(entryName(deal)))
          .append(';')
          .append(PurchasingTrancheService.amountText(deal.getAmount()))
          .append(';')
          .append(PurchasingTrancheService.amountText(figures.accepted()))
          .append(';')
          .append(deal.getUnitPrice())
          .append(';')
          .append(deal.getTotal())
          .append(';')
          .append(deal.getStatus().name())
          .append(';')
          .append(figures.funded())
          .append(';')
          .append(figures.returned())
          .append(';')
          .append(figures.loss())
          .append(';')
          .append(
              label(figures.overdue() ? "purchasing.report.csv.yes" : "purchasing.report.csv.no"))
          .append('\n');
    }
    return out.toString();
  }

  /**
   * Builds the forum-safe text block of a period.
   *
   * @param from the first month, or {@code null}
   * @param to the last month, or {@code null}
   * @return the text, one fact per line
   */
  @NotNull
  public String forumText(@Nullable YearMonth from, @Nullable YearMonth to) {
    PurchasingReportDto report = build(period(from, to)).report();
    ReportTotalsDto totals = report.totals();
    StringBuilder out = new StringBuilder();
    out.append(label("purchasing.report.forum.title", report.from(), report.to())).append("\n\n");
    out.append(
            label(
                "purchasing.report.forum.deals",
                totals.dealCount(),
                totals.settledCount(),
                totals.failedCount(),
                totals.overdueCount()))
        .append('\n');
    out.append(
            label(
                "purchasing.report.forum.money",
                totals.funded(),
                totals.returned(),
                totals.spent(),
                totals.loss()))
        .append("\n\n");
    if (!report.materials().isEmpty()) {
      out.append(label("purchasing.report.forum.materials")).append('\n');
      for (ReportMaterialDto material : report.materials()) {
        out.append(
                label(
                    "purchasing.report.forum.material",
                    material.name(),
                    material.dealCount(),
                    PurchasingTrancheService.amountText(material.boughtAmount()),
                    Math.round(material.averageUnitPrice())))
            .append('\n');
      }
    }
    return out.toString();
  }

  private @NotNull Built build(@NotNull Period period) {
    settingsService.requireEnabled();
    if (!accessPolicy.canReadReport()) {
      throw new AccessDeniedException("The caller may not read the purchasing report");
    }
    Specification<PurchasingDeal> spec =
        dealService
            .visibleToCaller()
            .and((root, q, cb) -> root.get("status").in(REACHED_THE_BANK))
            .and(
                (root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), period.start()))
            .and((root, q, cb) -> cb.lessThan(root.<Instant>get("createdAt"), period.end()));
    List<PurchasingDeal> deals =
        dealRepository.findAll(spec).stream()
            .sorted(Comparator.comparing(PurchasingDeal::getCreatedAt))
            .toList();
    Set<UUID> ids = deals.stream().map(PurchasingDeal::getId).collect(Collectors.toSet());
    Map<UUID, Long> funded = new HashMap<>();
    for (PurchasingDealTranche tranche : trancheRepository.findByDealIdIn(ids)) {
      if (tranche.getState() == TrancheState.CONFIRMED) {
        funded.merge(tranche.getDealId(), tranche.getAmount(), Long::sum);
      }
    }
    Map<UUID, Double> accepted = new HashMap<>();
    for (PurchasingDealDelivery delivery : deliveryRepository.findByDealIdIn(ids)) {
      if (delivery.getAcceptedAmount() != null) {
        accepted.merge(delivery.getDealId(), delivery.getAcceptedAmount(), Double::sum);
      }
    }
    Map<UUID, PurchasingDealSettlement> settlements = new HashMap<>();
    for (PurchasingDealSettlement settlement : settlementRepository.findByDealIdIn(ids)) {
      settlements.put(settlement.getDealId(), settlement);
    }
    Instant now = Instant.now();
    List<Figures> figures = new ArrayList<>();
    for (PurchasingDeal deal : deals) {
      PurchasingDealSettlement settlement = settlements.get(deal.getId());
      boolean settled = settlement != null && settlement.getSettledAt() != null;
      boolean overdue =
          (deal.getStatus() == DealStatus.DELIVERED || deal.getStatus() == DealStatus.FAILED)
              && deal.getSettlementDueAt() != null
              && deal.getSettlementDueAt().isBefore(now);
      figures.add(
          new Figures(
              deal,
              accepted.getOrDefault(deal.getId(), 0.0),
              funded.getOrDefault(deal.getId(), 0L),
              settled ? settlement.getRemainder() : 0L,
              settlement == null ? 0L : settlement.getLossAmount(),
              overdue));
    }
    return new Built(figures, assemble(period, figures));
  }

  private @NotNull PurchasingReportDto assemble(
      @NotNull Period period, @NotNull List<Figures> figures) {
    Map<String, long[]> months = new LinkedHashMap<>();
    Map<String, Double> monthBought = new HashMap<>();
    for (YearMonth m = period.from(); !m.isAfter(period.to()); m = m.plusMonths(1)) {
      months.put(m.toString(), new long[5]);
    }
    Map<UUID, MaterialAcc> materials = new LinkedHashMap<>();
    Map<UUID, NeedAcc> needs = new LinkedHashMap<>();
    int settledCount = 0;
    int failedCount = 0;
    int overdueCount = 0;
    double bought = 0.0;
    long funded = 0;
    long returned = 0;
    long loss = 0;
    for (Figures f : figures) {
      PurchasingDeal deal = f.deal();
      long[] row = months.get(month(deal));
      row[0]++;
      row[1] += f.funded();
      row[2] += f.returned();
      row[3] += f.loss();
      monthBought.merge(month(deal), f.accepted(), Double::sum);
      bought += f.accepted();
      funded += f.funded();
      returned += f.returned();
      loss += f.loss();
      settledCount += deal.getStatus() == DealStatus.SETTLED ? 1 : 0;
      failedCount += deal.getStatus() == DealStatus.FAILED ? 1 : 0;
      overdueCount += f.overdue() ? 1 : 0;
      UUID catalogueId =
          deal.getMaterial() != null ? deal.getMaterial().getId() : deal.getGameItem().getId();
      materials
          .computeIfAbsent(
              catalogueId,
              id ->
                  new MaterialAcc(
                      id,
                      entryName(deal),
                      deal.getMaterial() == null ? null : deal.getMaterial().getPriceBuy()))
          .add(deal, f.accepted());
      needs.computeIfAbsent(deal.getNeedId(), NeedAcc::new).add(f.accepted());
    }
    List<ReportMonthDto> monthRows =
        months.entrySet().stream()
            .map(
                e ->
                    new ReportMonthDto(
                        e.getKey(),
                        (int) e.getValue()[0],
                        monthBought.getOrDefault(e.getKey(), 0.0),
                        e.getValue()[1],
                        e.getValue()[2],
                        e.getValue()[1] - e.getValue()[2],
                        e.getValue()[3]))
            .toList();
    Map<UUID, NeedRef> refs = supplyNeeds.findAll(needs.keySet());
    Map<UUID, String> unitNames = new HashMap<>();
    for (OrgUnit unit :
        orgUnitRepository.findAllById(
            refs.values().stream().map(NeedRef::orgUnitId).collect(Collectors.toSet()))) {
      unitNames.put(unit.getId(), unit.getName());
    }
    List<ReportNeedDto> needRows =
        needs.values().stream()
            .map(
                acc -> {
                  NeedRef ref = refs.get(acc.needId());
                  return new ReportNeedDto(
                      acc.needId(),
                      ref == null ? "—" : unitNames.getOrDefault(ref.orgUnitId(), "—"),
                      ref == null ? "—" : ref.label(),
                      acc.dealCount(),
                      acc.bought());
                })
            .sorted(Comparator.comparingDouble(ReportNeedDto::boughtAmount).reversed())
            .toList();
    List<ReportMaterialDto> materialRows =
        materials.values().stream()
            .map(MaterialAcc::toDto)
            .sorted(Comparator.comparingDouble(ReportMaterialDto::boughtAmount).reversed())
            .toList();
    return new PurchasingReportDto(
        period.from().toString(),
        period.to().toString(),
        new ReportTotalsDto(
            figures.size(),
            settledCount,
            failedCount,
            overdueCount,
            bought,
            funded,
            returned,
            funded - returned,
            loss),
        monthRows,
        materialRows,
        needRows);
  }

  private static @NotNull Period period(@Nullable YearMonth from, @Nullable YearMonth to) {
    YearMonth last = to == null ? YearMonth.now(ZoneOffset.UTC) : to;
    YearMonth first = from == null ? last.minusMonths(11) : from;
    if (first.isAfter(last)) {
      throw new BadRequestException("The report starts after it ends");
    }
    if (first.until(last, java.time.temporal.ChronoUnit.MONTHS) >= MAX_MONTHS) {
      throw new BadRequestException("The report covers at most 36 months");
    }
    return new Period(
        first,
        last,
        first.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
        last.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
  }

  private static @NotNull String month(@NotNull PurchasingDeal deal) {
    return YearMonth.from(deal.getCreatedAt().atOffset(ZoneOffset.UTC)).toString();
  }

  private static @NotNull String entryName(@NotNull PurchasingDeal deal) {
    return deal.getMaterial() != null ? deal.getMaterial().getName() : deal.getGameItem().getName();
  }

  private static @NotNull String csvCell(@NotNull String value) {
    return value.replace(';', ',').replace('\n', ' ');
  }

  private @NotNull String label(@NotNull String key, @NotNull Object... args) {
    Object[] texts = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      texts[i] = String.valueOf(args[i]);
    }
    return messageSource.getMessage(key, texts, Locale.GERMAN);
  }

  private record Period(YearMonth from, YearMonth to, Instant start, Instant end) {}

  private record Figures(
      PurchasingDeal deal,
      double accepted,
      long funded,
      long returned,
      long loss,
      boolean overdue) {}

  private record Built(List<Figures> figures, PurchasingReportDto report) {}

  private static final class MaterialAcc {
    private final UUID id;
    private final String name;
    private final Double uex;
    private int deals;
    private double bought;
    private double weightedPrice;
    private double weight;

    MaterialAcc(UUID id, String name, Double uex) {
      this.id = id;
      this.name = name;
      this.uex = uex;
    }

    void add(PurchasingDeal deal, double accepted) {
      deals++;
      bought += accepted;
      weightedPrice += deal.getUnitPrice() * deal.getAmount();
      weight += deal.getAmount();
    }

    ReportMaterialDto toDto() {
      return new ReportMaterialDto(
          id, name, deals, bought, weight == 0 ? 0 : weightedPrice / weight, uex);
    }
  }

  private static final class NeedAcc {
    private final UUID needId;
    private int deals;
    private double bought;

    NeedAcc(UUID needId) {
      this.needId = needId;
    }

    void add(double accepted) {
      deals++;
      bought += accepted;
    }

    UUID needId() {
      return needId;
    }

    int dealCount() {
      return deals;
    }

    double bought() {
      return bought;
    }
  }
}
