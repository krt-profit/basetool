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

package de.greluc.krt.profit.basetool.frontend.settings.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient.SystemSetting;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingDto;
import de.greluc.krt.profit.basetool.frontend.settings.model.SystemSettingUpdateDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for the admin system-settings page ({@code /admin/settings}): job-order age
 * thresholds, refinery rounding mode and the in-game transfer-fee rate, each with its own
 * optimistic-lock version.
 *
 * <p>The transfer fee is stored as a fraction ({@code 0.005}) and shown as a percentage ({@code
 * 0.5}).
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminSettingsPageController {

  /** Decimal scale used when converting between DB fraction and form percentage. */
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

  /**
   * Display default for the transfer-fee rate (percent) used when the backend lookup fails. Kept in
   * sync with {@code OperationService.DEFAULT_TRANSFER_FEE_RATE} (0.005 = 0.5%) so the form never
   * renders blank.
   */
  private static final BigDecimal DEFAULT_TRANSFER_FEE_PERCENT = new BigDecimal("0.5");

  /** Reads and writes the settings. */
  private final SettingsBackendClient settingsClient;

  /** Clears the catalogue caches after a settings write. */
  private final CatalogueCacheEviction cacheEviction;

  private final MessageSource messageSource;

  /**
   * Loads the system settings as value and version pairs for the form. A missing setting falls back
   * to its default (30/90 days, rounding {@code UP}, 0.5% transfer fee).
   *
   * @param model Thymeleaf model populated with the value and version pairs
   * @return the {@code admin-settings} view name
   */
  @NotNull
  @GetMapping
  public String viewSettings(Model model) {
    LoadedSetting<Integer> yellow =
        SectionFallback.load(
            log,
            "age-yellow-days",
            () -> readSetting(SystemSetting.JOB_ORDER_AGE_YELLOW_DAYS, Integer::parseInt),
            new LoadedSetting<>(30, 0L));
    LoadedSetting<Integer> red =
        SectionFallback.load(
            log,
            "age-red-days",
            () -> readSetting(SystemSetting.JOB_ORDER_AGE_RED_DAYS, Integer::parseInt),
            new LoadedSetting<>(90, 0L));
    LoadedSetting<String> rounding =
        SectionFallback.load(
            log,
            "refinery-rounding-mode",
            () -> readSetting(SystemSetting.REFINERY_ROUNDING_MODE, Function.identity()),
            new LoadedSetting<>("UP", 0L));
    LoadedSetting<BigDecimal> transferFee =
        SectionFallback.load(
            log,
            "transfer-fee-rate",
            () ->
                readSetting(
                    SystemSetting.OPERATION_TRANSFER_FEE_RATE,
                    AdminSettingsPageController::toPercent),
            new LoadedSetting<>(DEFAULT_TRANSFER_FEE_PERCENT, 0L));

    model.addAttribute("ageYellowDays", yellow.value());
    model.addAttribute("ageYellowVersion", yellow.version());
    model.addAttribute("ageRedDays", red.value());
    model.addAttribute("ageRedVersion", red.version());
    model.addAttribute("refineryRoundingMode", rounding.value());
    model.addAttribute("refineryRoundingVersion", rounding.version());
    model.addAttribute("transferFeePercent", transferFee.value().toPlainString());
    model.addAttribute("transferFeeVersion", transferFee.version());

    return "settings/admin-settings";
  }

  /**
   * Reads one system setting and converts its stored value for the form.
   *
   * @param setting the setting to read
   * @param convert turns the stored string into the form value; it may throw on a malformed value
   * @param <T> the form value type
   * @return the converted value with the setting's optimistic-lock version
   */
  @NotNull
  private <T> LoadedSetting<T> readSetting(
      @NotNull SystemSetting setting, @NotNull Function<String, T> convert) {
    SystemSettingDto dto = settingsClient.read(setting);
    return new LoadedSetting<>(convert.apply(dto.value()), dto.version());
  }

  /**
   * Converts the stored transfer-fee fraction into the percentage the form shows.
   *
   * @param fraction the stored fraction, e.g. {@code 0.005}
   * @return the percentage without trailing zeros and with a non-negative scale, e.g. {@code 0.5}
   */
  @NotNull
  private static BigDecimal toPercent(@NotNull String fraction) {
    BigDecimal percent = new BigDecimal(fraction).multiply(ONE_HUNDRED).stripTrailingZeros();
    return percent.scale() < 0 ? percent.setScale(0, RoundingMode.UNNECESSARY) : percent;
  }

  /**
   * Persists the four settings from one form submit, each through its own versioned PUT.
   *
   * <p>Cross-field rules ({@code yellow < red}, both non-negative, transfer fee in {@code [0, 100)}
   * percent) are checked before any PUT; a violation or failure surfaces as a toast.
   *
   * @param ageYellowDaysStr yellow-aging threshold in days
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDaysStr red-aging threshold in days
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode ({@code UP}, {@code DOWN}, {@code HALF_UP}, ...)
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercentStr in-game transfer fee in percent (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/settings}
   */
  @NotNull
  @PostMapping
  public String updateSettings(
      @RequestParam("ageYellowDays") String ageYellowDaysStr,
      @RequestParam("ageYellowVersion") Long ageYellowVersion,
      @RequestParam("ageRedDays") String ageRedDaysStr,
      @RequestParam("ageRedVersion") Long ageRedVersion,
      @RequestParam("refineryRoundingMode") String refineryRoundingMode,
      @RequestParam("refineryRoundingVersion") Long refineryRoundingVersion,
      @RequestParam("transferFeePercent") String transferFeePercentStr,
      @RequestParam("transferFeeVersion") Long transferFeeVersion,
      RedirectAttributes redirectAttributes) {
    try {
      int yellowDays = Integer.parseInt(ageYellowDaysStr);
      int redDays = Integer.parseInt(ageRedDaysStr);

      if (yellowDays < 0 || redDays < 0 || yellowDays >= redDays) {
        redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.values");
        return "redirect:/admin/settings";
      }

      BigDecimal transferFeePercent = new BigDecimal(transferFeePercentStr.trim());
      if (transferFeePercent.signum() < 0 || transferFeePercent.compareTo(ONE_HUNDRED) >= 0) {
        redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.values");
        return "redirect:/admin/settings";
      }
      BigDecimal transferFeeRate = transferFeePercent.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);

      try {
        settingsClient.write(
            SystemSetting.JOB_ORDER_AGE_YELLOW_DAYS,
            new SystemSettingUpdateDto(String.valueOf(yellowDays), ageYellowVersion));
        settingsClient.write(
            SystemSetting.JOB_ORDER_AGE_RED_DAYS,
            new SystemSettingUpdateDto(String.valueOf(redDays), ageRedVersion));
        settingsClient.write(
            SystemSetting.REFINERY_ROUNDING_MODE,
            new SystemSettingUpdateDto(refineryRoundingMode, refineryRoundingVersion));
        settingsClient.write(
            SystemSetting.OPERATION_TRANSFER_FEE_RATE,
            new SystemSettingUpdateDto(
                transferFeeRate.stripTrailingZeros().toPlainString(), transferFeeVersion));
      } finally {
        cacheEviction.clearStaticDataCache();
      }

      redirectAttributes.addFlashAttribute("successToast", "success.settings.update");
    } catch (NumberFormatException _) {
      redirectAttributes.addFlashAttribute("errorToast", "error.settings.invalid.format");
    } catch (Exception e) {
      log.error("Failed to update settings", e);
      redirectAttributes.addFlashAttribute("errorToast", "error.settings.update.failed");
    }
    return "redirect:/admin/settings";
  }

  /**
   * AJAX twin of {@link #updateSettings}: applies the same rules and PUTs and returns the new
   * versions as JSON. Validation failures answer {@code 422 problem+json} with a localized {@code
   * detail}; a backend conflict is relayed with its {@code OPTIMISTIC_LOCK} code.
   *
   * @param request the settings values and per-setting versions
   * @param locale the request locale used to resolve validation messages
   * @return {@code 200} with the new versions, {@code 422} on a validation failure, the relayed
   *     backend status on a conflict or failure, {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateSettingsAjax(
      @RequestBody SettingsAjaxRequest request, Locale locale) {
    try {
      int yellowDays = Integer.parseInt(request.ageYellowDays());
      int redDays = Integer.parseInt(request.ageRedDays());
      if (yellowDays < 0 || redDays < 0 || yellowDays >= redDays) {
        return validationProblem("error.settings.invalid.values", locale);
      }

      BigDecimal transferFeePercent = new BigDecimal(request.transferFeePercent().trim());
      if (transferFeePercent.signum() < 0 || transferFeePercent.compareTo(ONE_HUNDRED) >= 0) {
        return validationProblem("error.settings.invalid.values", locale);
      }
      BigDecimal transferFeeRate = transferFeePercent.divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);

      try {
        final SystemSettingDto yellow =
            settingsClient.write(
                SystemSetting.JOB_ORDER_AGE_YELLOW_DAYS,
                new SystemSettingUpdateDto(String.valueOf(yellowDays), request.ageYellowVersion()));
        final SystemSettingDto red =
            settingsClient.write(
                SystemSetting.JOB_ORDER_AGE_RED_DAYS,
                new SystemSettingUpdateDto(String.valueOf(redDays), request.ageRedVersion()));
        final SystemSettingDto rounding =
            settingsClient.write(
                SystemSetting.REFINERY_ROUNDING_MODE,
                new SystemSettingUpdateDto(
                    request.refineryRoundingMode(), request.refineryRoundingVersion()));
        final SystemSettingDto fee =
            settingsClient.write(
                SystemSetting.OPERATION_TRANSFER_FEE_RATE,
                new SystemSettingUpdateDto(
                    transferFeeRate.stripTrailingZeros().toPlainString(),
                    request.transferFeeVersion()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ageYellowVersion", yellow.version());
        result.put("ageRedVersion", red.version());
        result.put("refineryRoundingVersion", rounding.version());
        result.put("transferFeeVersion", fee.version());
        result.put("transferFeePercent", transferFeePercent.stripTrailingZeros().toPlainString());
        return ResponseEntity.ok(result);
      } finally {
        cacheEviction.clearStaticDataCache();
      }
    } catch (NumberFormatException _) {
      return validationProblem("error.settings.invalid.format", locale);
    } catch (BackendServiceException e) {
      log.debug("Failed to update settings (ajax)", e);
      return propagateBackendError(e);
    }
  }

  /**
   * Builds a {@code 422 application/problem+json} response whose {@code detail} is the localized
   * message for {@code messageKey}, so the client toasts the exact validation reason without
   * client-side key mapping.
   *
   * @param messageKey the message bundle key to resolve
   * @param locale the request locale
   * @return a 422 problem+json {@link ResponseEntity}
   */
  private ResponseEntity<Object> validationProblem(String messageKey, Locale locale) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 422);
    body.put("code", "VALIDATION_FAILED");
    body.put("detail", messageSource.getMessage(messageKey, null, messageKey, locale));
    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  /**
   * One setting's form value with the optimistic-lock version the form echoes back.
   *
   * @param value the form value, or {@code null} when the backend stored none
   * @param version the setting's version
   * @param <T> the form value type
   */
  private record LoadedSetting<T>(T value, Long version) {}

  /**
   * JSON payload of {@link #updateSettingsAjax}; the numeric fields stay strings so they are parsed
   * and validated server-side like the form parameters.
   *
   * @param ageYellowDays yellow-aging threshold in days
   * @param ageYellowVersion optimistic-lock version for the yellow setting
   * @param ageRedDays red-aging threshold in days
   * @param ageRedVersion optimistic-lock version for the red setting
   * @param refineryRoundingMode rounding mode ({@code UP} / {@code DOWN})
   * @param refineryRoundingVersion optimistic-lock version for the rounding setting
   * @param transferFeePercent in-game transfer fee in percent (e.g. {@code 0.5})
   * @param transferFeeVersion optimistic-lock version for the transfer-fee setting
   */
  public record SettingsAjaxRequest(
      String ageYellowDays,
      Long ageYellowVersion,
      String ageRedDays,
      Long ageRedVersion,
      String refineryRoundingMode,
      Long refineryRoundingVersion,
      String transferFeePercent,
      Long transferFeeVersion) {}
}
