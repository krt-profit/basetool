<#import "template.ftl" as layout>
<@layout.registrationLayout displayRequiredFields=false displayMessage=!messagesPerField.existsError('totp','userLabel'); section>
    <#if section = "header">
        ${msg("loginTotpTitle")}
    <#elseif section = "form">
        <div class="login-container">
            <h1>${msg("loginTotpTitle")}</h1>
            <div class="krt-info-text">
                <ol id="kc-totp-settings" class="krt-ol">
                    <li>
                        <p>${msg("loginTotpStep1")}</p>
                        <ul id="kc-totp-supported-apps" class="krt-ul">
                            <#list totp.supportedApplications as app>
                                <li>${msg(app)}</li>
                            </#list>
                        </ul>
                    </li>

                    <#if mode?? && mode = "manual">
                        <li>
                            <p>${msg("loginTotpManualStep2")}</p>
                            <p><span id="kc-totp-secret-key" class="krt-monospace-text">${totp.totpSecretEncoded}</span></p>
                            <p><a href="${totp.qrUrl}" id="mode-barcode" class="krt-link">${msg("loginTotpScanBarcode")}</a></p>
                        </li>
                        <li>
                            <p>${msg("loginTotpManualStep3")}</p>
                            <ul class="krt-ul">
                                <li id="kc-totp-type">${msg("loginTotpType")}: ${msg("loginTotp." + totp.policy.type)}</li>
                                <li id="kc-totp-algorithm">${msg("loginTotpAlgorithm")}: ${totp.policy.getAlgorithmKey()}</li>
                                <li id="kc-totp-digits">${msg("loginTotpDigits")}: ${totp.policy.digits}</li>
                                <#if totp.policy.type = "totp">
                                    <li id="kc-totp-period">${msg("loginTotpInterval")}: ${totp.policy.period}</li>
                                <#elseif totp.policy.type = "hotp">
                                    <li id="kc-totp-counter">${msg("loginTotpCounter")}: ${totp.policy.initialCounter}</li>
                                </#if>
                            </ul>
                        </li>
                    <#else>
                        <li>
                            <p>${msg("loginTotpStep2")}</p>
                            <div class="krt-qr-wrapper">
                                <img id="kc-totp-secret-qr-code" class="krt-qr-code-img" src="data:image/png;base64, ${totp.totpSecretQrCode}" alt="${msg("totpQrCodeAlt")}">
                            </div>
                            <br/>
                            <p><a href="${totp.manualUrl}" id="mode-manual" class="krt-link">${msg("loginTotpUnableToScan")}</a></p>
                        </li>
                    </#if>
                    <li>
                        <p>${msg("loginTotpStep3")}</p>
                        <p>${msg("loginTotpStep3DeviceName")}</p>
                    </li>
                </ol>
            </div>

            <form action="${url.loginAction}" id="kc-totp-settings-form" method="post">
                <div class="form-group">
                    <label for="totp" class="krt-label">${msg("authenticatorCode")} *</label>
                    <input type="text" id="totp" name="totp" autocomplete="one-time-code" class="krt-input" aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>" inputmode="numeric" dir="ltr" />
                    <#if messagesPerField.existsError('totp')>
                        <span id="input-error-otp-code" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                        </span>
                    </#if>
                    <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}" />
                    <#if mode??><input type="hidden" id="mode" name="mode" value="${mode}"/></#if>
                </div>

                <div class="form-group">
                    <label for="userLabel" class="krt-label">${msg("loginTotpDeviceName")} <#if totp.otpCredentials?size gte 1>*</#if></label>
                    <input type="text" class="krt-input" id="userLabel" name="userLabel" autocomplete="off" aria-invalid="<#if messagesPerField.existsError('userLabel')>true</#if>" dir="ltr" />
                    <#if messagesPerField.existsError('userLabel')>
                        <span id="input-error-otp-label" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('userLabel'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="form-group krt-mb-20">
                    <label class="krt-checkbox-label">
                        <input type="checkbox" id="logout-sessions" name="logout-sessions" class="krt-checkbox" value="on" checked>
                        ${msg("logoutOtherSessions")}
                    </label>
                </div>

                <div class="form-group login-action">
                    <#if isAppInitiatedAction??>
                        <input type="submit" class="krt-button" id="saveTOTPBtn" value="${msg("doSubmit")}" />
                        <button type="submit" class="krt-button-secondary" id="cancelTOTPBtn" name="cancel-aia" value="true">${msg("doCancel")}</button>
                    <#else>
                        <input type="submit" class="krt-button" id="saveTOTPBtn" value="${msg("doSubmit")}" />
                    </#if>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>