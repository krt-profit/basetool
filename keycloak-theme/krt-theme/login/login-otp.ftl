<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>
    <#if section="header">
        ${msg("doLogIn")}
    <#elseif section="form">
        <div class="login-container">
            <h1>${msg("doLogIn")}</h1>
            <form id="kc-otp-login-form" action="${url.loginAction}" method="post">
                <#if otpLogin.userOtpCredentials?size gt 1>
                    <div class="form-group krt-info-text">
                        <#list otpLogin.userOtpCredentials as otpCredential>
                            <label for="kc-otp-credential-${otpCredential?index}" tabindex="${otpCredential?index}" class="krt-checkbox-label krt-mb-10">
                                <input id="kc-otp-credential-${otpCredential?index}" type="radio" name="selectedCredentialId" value="${otpCredential.id}" <#if otpCredential.id == otpLogin.selectedCredentialId>checked="checked"</#if>>
                                <span>${otpCredential.userLabel}</span>
                            </label>
                        </#list>
                    </div>
                </#if>

                <div class="form-group">
                    <label for="otp" class="krt-label">${msg("loginOtpOneTime")}</label>
                    <input id="otp" name="otp" autocomplete="one-time-code" type="text" class="krt-input" autofocus aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>" dir="ltr" />
                    <#if messagesPerField.existsError('totp')>
                        <span id="input-error-otp-code" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="form-group login-action">
                    <input class="krt-button" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}" />
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>