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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderCreatedDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeJobOrderDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientCreateCapException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientCreateCaps;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateCommand;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateOptionsDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreateUnitOptionDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCreationOptions;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The create options and the create of aUEC orders for an exchange client (REQ-OXCH-004), through
 * the web's create command and options projection under {@link
 * JobOrderWriterRule#DIRECT_LOGISTICIAN} (ADR-0408): a member creates only with one of their direct
 * Logistician units as the requesting unit, for any eligible Profit unit.
 *
 * <p>Every create counts on {@code basetool_exchange_writes_total{resource="job_order"}}, a refused
 * pair on {@code basetool_exchange_job_order_refused_total{reason="units_not_offered"}} and a cap
 * refusal under {@code open_order_cap} or {@code daily_create_cap} (REQ-OXCH-009); every
 * create-options read on {@code basetool_exchange_job_order_reads_total} (REQ-OBS-025). The command
 * publishes the queue frame and the notices after the commit, as for a web create.
 */
@Service
@RequiredArgsConstructor
public class ExchangeJobOrderCreateService {

  private final JobOrderCreationOptions creationOptions;
  private final JobOrderCreateCommand createCommand;
  private final JobOrderClientCreateCaps caps;
  private final JobOrderExchangeReads reads;
  private final ExchangeSettingsRepository settingsRepository;
  private final ExchangeJobOrderMetrics metrics;

  /** Runs a create and the read of its answer in one transaction. */
  private final TransactionTemplate transaction;

  /**
   * Lists the pairs the member may create an aUEC order for, whether the writes switch is on and
   * the member's own figures against the caps on client creates.
   *
   * @param caller the acting member and client
   * @return the member's writer units, the eligible Profit units, the suggestion, the switch and
   *     the limits
   */
  @NotNull
  public ExchangeJobOrderCreateOptionsDto options(@NotNull ExchangeCaller caller) {
    JobOrderCreateOptionsDto options =
        creationOptions.current(JobOrderWriterRule.DIRECT_LOGISTICIAN, actor(caller));
    boolean writesEnabled =
        settingsRepository
            .findById(ExchangeSettings.SINGLETON_ID)
            .map(ExchangeSettings::isJobOrderWritesEnabled)
            .orElse(false);
    metrics.countRead(caller.clientId(), JobOrderExchangeReadRoute.CREATE_OPTIONS);
    return new ExchangeJobOrderCreateOptionsDto(
        units(options.requestingOptions()),
        units(options.responsibleOptions()),
        options.suggestedAuecResponsibleOrgUnitId(),
        writesEnabled,
        ExchangeJobOrderCreateOptionsDto.Limits.of(caps.limits(caller.member())));
  }

  /**
   * Creates an aUEC order for the member, or answers the order the client created for the member
   * under the same key with its current state and stored target; changed terms are never applied.
   *
   * @param caller the acting member and client
   * @param body the pair, contact, comment and target
   * @param idempotencyKey the client's {@code Idempotency-Key} the gateway relayed
   * @return the order's id, status, versions and target
   * @throws ExchangeProblemException {@code 400 JOB_ORDER_UNITS_NOT_OFFERED} for an unknown unit, a
   *     requesting unit the member does not write for or a responsible unit that is not an eligible
   *     Profit unit, alike; {@code 409 JOB_ORDER_OPEN_ORDER_CAP} or {@code 409
   *     JOB_ORDER_DAILY_CREATE_CAP} when the create would pass a cap on client creates
   */
  @NotNull
  public ExchangeJobOrderCreatedDto create(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeJobOrderCreateDto body,
      @NotNull String idempotencyKey) {
    JobOrderCreateCommand.AuecOrder order =
        new JobOrderCreateCommand.AuecOrder(
            body.responsibleUnitId(),
            body.requestingUnitId(),
            body.contact(),
            body.comment(),
            BigDecimal.valueOf(body.target()));
    try {
      ExchangeJobOrderCreatedDto created =
          Objects.requireNonNull(
              transaction.execute(_ ->
                  answer(
                      createCommand.createAuec(
                          order,
                          JobOrderWriterRule.DIRECT_LOGISTICIAN,
                          actor(caller),
                          idempotencyKey))));
      metrics.countWrite(caller.clientId(), ExchangeOps.APPLIED);
      return created;
    } catch (BadRequestException | AccessDeniedException e) {
      metrics.countRefused(JobOrderExchangeRefusal.UNITS_NOT_OFFERED);
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw ExchangeProblemException.jobOrderUnitsNotOffered(e);
    } catch (JobOrderClientCreateCapException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw capRefusal(e);
    } catch (RuntimeException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    }
  }

  /**
   * Counts a cap refusal under its reason and turns it into the cap's exchange code.
   *
   * @param refusal the create command's cap refusal
   * @return the {@code 409} refusal with the cap's code
   */
  private @NotNull ExchangeProblemException capRefusal(
      @NotNull JobOrderClientCreateCapException refusal) {
    return switch (refusal.getCap()) {
      case OPEN_ORDERS -> {
        metrics.countRefused(JobOrderExchangeRefusal.OPEN_ORDER_CAP);
        yield ExchangeProblemException.jobOrderOpenOrderCap(refusal);
      }
      case DAILY_CREATES -> {
        metrics.countRefused(JobOrderExchangeRefusal.DAILY_CREATE_CAP);
        yield ExchangeProblemException.jobOrderDailyCreateCap(refusal);
      }
    };
  }

  /**
   * Reads the created order's terse answer in the create's transaction.
   *
   * @param orderId the created or earlier order
   * @return its id, status, versions and stored target
   */
  private @NotNull ExchangeJobOrderCreatedDto answer(@NotNull UUID orderId) {
    JobOrderExchangeRead read =
        Objects.requireNonNull(reads.project(List.of(orderId)).get(orderId), "created order");
    return new ExchangeJobOrderCreatedDto(
        read.id(),
        read.status(),
        ExchangeJobOrderDto.Versions.of(read.versions()),
        ExchangeAmounts.auecTarget(read));
  }

  private static @NotNull JobOrderActor.Client actor(@NotNull ExchangeCaller caller) {
    return new JobOrderActor.Client(caller.member(), caller.clientId());
  }

  private static @NotNull List<ExchangeOrgUnitDto> units(
      @NotNull List<JobOrderCreateUnitOptionDto> options) {
    return options.stream()
        .map(option ->
            new ExchangeOrgUnitDto(
                option.orgUnitId(),
                option.orgUnitName(),
                Objects.requireNonNull(option.shorthand(), "an org unit's shorthand is required"),
                option.kind()))
        .toList();
  }
}
