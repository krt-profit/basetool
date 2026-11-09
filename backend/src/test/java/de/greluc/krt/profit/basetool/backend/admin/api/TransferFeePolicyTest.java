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

package de.greluc.krt.profit.basetool.backend.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.greluc.krt.profit.basetool.backend.admin.internal.SystemSettingRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Verifies the shared configured fee contract through the admin module API. */
@SpringBootTest
@Transactional
class TransferFeePolicyTest {

  @Autowired private TransferFeePolicy transferFeePolicy;
  @Autowired private SystemSettingRepository settings;

  @Test
  void twoMillionNetAtHalfPercentCostsTenThousandWholeAuec() {
    setRate("0.005");

    var basis = transferFeePolicy.snapshot();

    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), basis))
        .isEqualByComparingTo("10000");
  }

  @Test
  void restoredBasisKeepsTheSubmittedFeeAfterTheConfiguredRateChanges() {
    setRate("0.005");
    String storedRate = transferFeePolicy.snapshot().rate().toPlainString();

    setRate("0.01");
    var restoredBasis = new TransferFeePolicy.RateSnapshot(new BigDecimal(storedRate));

    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), restoredBasis))
        .isEqualByComparingTo("10000");
    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), transferFeePolicy.snapshot()))
        .isEqualByComparingTo("20000");
  }

  @ParameterizedTest
  @CsvSource({"99, 0", "100, 1", "300, 2", "0, 0", "-50, 0", "9007199254740993, 45035996273705"})
  void feesUseExactWholeAuecHalfUpRounding(String amount, String expectedFee) {
    setRate("0.005");

    assertThat(transferFeePolicy.feeOn(new BigDecimal(amount), transferFeePolicy.snapshot()))
        .isEqualByComparingTo(expectedFee)
        .hasScaleOf(0);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "invalid", "-0.01", "1", "1.5"})
  void invalidConfiguredRatesUseTheExistingHalfPercentDefault(String configuredRate) {
    setRate(configuredRate);

    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), transferFeePolicy.snapshot()))
        .isEqualByComparingTo("10000");
  }

  @Test
  void missingConfiguredRateUsesTheExistingHalfPercentDefault() {
    settings.deleteById("operation.transfer_fee_rate");
    settings.flush();

    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), transferFeePolicy.snapshot()))
        .isEqualByComparingTo("10000");
  }

  @Test
  void zeroConfiguredRateDisablesTheFee() {
    setRate("0");

    assertThat(transferFeePolicy.feeOn(new BigDecimal("2000000"), transferFeePolicy.snapshot()))
        .isZero();
  }

  @Test
  void equivalentConfiguredRatesHaveTheSameSnapshotIdentity() {
    setRate(" 0.00500 ");
    var first = transferFeePolicy.snapshot();
    setRate("0.005");

    assertThat(transferFeePolicy.snapshot()).isEqualTo(first);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.01", "1", "1.5"})
  void restoredSnapshotsRejectInvalidRates(String storedRate) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TransferFeePolicy.RateSnapshot(new BigDecimal(storedRate)));
  }

  private void setRate(String value) {
    var setting = settings.findById("operation.transfer_fee_rate").orElseThrow();
    setting.setValue(value);
    settings.saveAndFlush(setting);
  }
}
