<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "form">
        <div class="login-container">
            <h1 class="krt-title-lato">${msg("updatePasswordTitle")}</h1>
            <form id="kc-passwd-update-form" action="${url.loginAction}" method="post">
                <div class="form-group">
                    <label for="password-new" class="krt-label">${msg("passwordNew")}</label>
                    <input type="password" id="password-new" name="password-new" class="krt-input" autofocus autocomplete="new-password" aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>" />
                    <#if messagesPerField.existsError('password')>
                        <span id="input-error-password" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="form-group">
                    <label for="password-confirm" class="krt-label">${msg("passwordConfirm")}</label>
                    <input type="password" id="password-confirm" name="password-confirm" class="krt-input" autocomplete="new-password" aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>" />
                    <#if messagesPerField.existsError('password-confirm')>
                        <span id="input-error-password-confirm" class="kc-error-message krt-error-message" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
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
                        <input name="login" class="krt-button" type="submit" value="${msg("doSubmit")}" />
                        <button id="kc-cancel" class="krt-button-secondary" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
                    <#else>
                        <input name="login" class="krt-button" type="submit" value="${msg("doSubmit")}" />
                    </#if>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>