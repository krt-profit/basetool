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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOpenRequestCapException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestWrites;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCancelledDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCorrectionDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestCreateDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeBankRequestWrittenDto;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Raises, corrects and cancels the member's own booking requests for an exchange client
 * (REQ-BXCH-004) through {@link BankOwnRequestWrites}, for the member's actor without Admin ({@link
 * BankActors#forExchange}). It decides nothing itself: every rule is the bank's.
 *
 * <p>Every write counts on {@code basetool_exchange_writes_total{resource="bank_request"}}, a
 * shared {@code 404} and a cap refusal also on {@code basetool_exchange_bank_refused_total}
 * (REQ-OBS-024). There is no exchange journal and no undo: a request books nothing.
 */
@Service
@RequiredArgsConstructor
public class ExchangeBankRequestWriteService {

  private final ExchangeBankActors bankActors;
  private final BankOwnRequestWrites requestWrites;
  private final ExchangeBankMetrics metrics;

  /**
   * Raises a deposit, withdrawal or transfer request for the member with the web's validation and
   * the policy's approval routing, and records the calling client on it (REQ-BANK-067).
   *
   * @param caller the acting member and the client the request is recorded for
   * @param body the type, accounts, amount, texts and the split of a deposit
   * @param idempotencyKey the client's key of the raise the gateway relayed, or {@code null}; a
   *     retry under the same key answers the request raised first
   * @return the new request's id, {@code PENDING} status, version and amount
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException for an unknown, closed
   *     or hidden account
   * @throws BankOpenRequestCapException when the member already holds the cap of pending requests
   */
  @NotNull
  public ExchangeBankRequestWrittenDto raise(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeBankRequestCreateDto body,
      @Nullable String idempotencyKey) {
    return counted(
        caller,
        () ->
            written(
                requestWrites.raise(
                    bankActors.of(caller),
                    new BankOwnRequestWrites.NewRequest(
                        body.type(),
                        body.sourceAccountId(),
                        body.targetAccountId(),
                        body.amount(),
                        body.note(),
                        body.justification(),
                        body.splitEnabled(),
                        body.splitPercent()),
                    caller.clientId(),
                    idempotencyKey)));
  }

  /**
   * Replaces amount, texts and destination of one of the member's pending, not yet approved
   * requests and re-routes its approval; the counterparty the request names stays.
   *
   * @param caller the acting member and client
   * @param requestId the member's request
   * @param body the corrected values and the version the client last read
   * @return the request's id, status, new version and corrected amount
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException for another member's or
   *     an unknown request, or a closed or unknown destination
   */
  @NotNull
  public ExchangeBankRequestWrittenDto correct(
      @NotNull ExchangeCaller caller,
      @NotNull UUID requestId,
      @NotNull ExchangeBankRequestCorrectionDto body) {
    return counted(
        caller,
        () ->
            written(
                requestWrites.correct(
                    bankActors.of(caller),
                    requestId,
                    new BankOwnRequestWrites.Correction(
                        body.amount(),
                        body.note(),
                        body.justification(),
                        body.targetAccountId(),
                        body.version()))));
  }

  /**
   * Cancels one of the member's pending requests; nothing was booked, so nothing is undone.
   *
   * @param caller the acting member and client
   * @param requestId the member's request
   * @param version the version the client last read
   * @return the request's id, {@code CANCELLED} status and new version
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException for another member's or
   *     an unknown request
   */
  @NotNull
  public ExchangeBankRequestCancelledDto cancel(
      @NotNull ExchangeCaller caller, @NotNull UUID requestId, long version) {
    return counted(
        caller,
        () -> {
          BankOwnRequestWrites.Written cancelled =
              requestWrites.cancel(bankActors.of(caller), requestId, version);
          return new ExchangeBankRequestCancelledDto(
              cancelled.id(), cancelled.status(), cancelled.version());
        });
  }

  /**
   * Runs one write and counts its outcome, and the refusal reason of a shared {@code 404} or a cap
   * refusal.
   *
   * @param caller the acting member and the client the outcome is counted for
   * @param write the bank write to run
   * @param <T> the answer's type
   * @return the write's answer, after counting it as applied
   */
  @NotNull
  private <T> T counted(@NotNull ExchangeCaller caller, @NotNull Supplier<T> write) {
    try {
      T answer = write.get();
      metrics.countWrite(caller.clientId(), ExchangeOps.APPLIED);
      return answer;
    } catch (NotFoundException e) {
      metrics.countRefused(BankExchangeRefusal.NOT_FOUND);
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    } catch (BankOpenRequestCapException e) {
      metrics.countRefused(BankExchangeRefusal.OPEN_REQUEST_CAP);
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    } catch (RuntimeException e) {
      metrics.countWrite(caller.clientId(), ExchangeOps.REJECTED);
      throw e;
    }
  }

  /**
   * Shapes the terse answer of a raise or correction; the amount is a JSON integer of whole aUEC.
   *
   * @param written the request as the bank stored it
   * @return its id, status, version and amount
   */
  @NotNull
  private static ExchangeBankRequestWrittenDto written(
      @NotNull BankOwnRequestWrites.Written written) {
    return new ExchangeBankRequestWrittenDto(
        written.id(), written.status(), written.version(), ExchangeAmounts.whole(written.amount()));
  }
}
