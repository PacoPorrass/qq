package com.empresa.vaultdrive.core.session

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.empresa.vaultdrive.R
import com.empresa.vaultdrive.core.security.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class AuthResult {
    data class Success(val token: String) : AuthResult()
    data class Error(val msg: String) : AuthResult()
    object Cancelled : AuthResult()
}

object TokenManager {
    val SCOPES = arrayOf("Files.ReadWrite", "Files.ReadWrite.All", "User.Read", "Sites.Read.All")
    private var msalApp: ISingleAccountPublicClientApplication? = null

    fun init(context: Context, onReady: () -> Unit) {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context, R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(app: ISingleAccountPublicClientApplication) { msalApp = app; onReady() }
                override fun onError(e: MsalException) { onReady() }
            })
    }

    suspend fun signIn(activity: Activity): AuthResult = suspendCancellableCoroutine { cont ->
        val app = msalApp ?: run { cont.resume(AuthResult.Error("MSAL no inicializado")); return@suspendCancellableCoroutine }
        app.signIn(activity, null, SCOPES, object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                Prefs.token = result.accessToken
                Prefs.tokenExpiry = result.expiresOn.time
                Prefs.userName = result.account.username ?: ""
                cont.resume(AuthResult.Success(result.accessToken))
            }
            override fun onError(e: MsalException) { cont.resume(AuthResult.Error(e.message ?: "Error de autenticación")) }
            override fun onCancel() { cont.resume(AuthResult.Cancelled) }
        })
    }

    suspend fun refreshSilently(): String? = suspendCancellableCoroutine { cont ->
        val app = msalApp ?: run { cont.resume(null); return@suspendCancellableCoroutine }
        app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(account: IAccount?) {
                if (account == null) { cont.resume(null); return }
                app.acquireTokenSilentAsync(AcquireTokenSilentParameters.Builder()
                    .forAccount(account).fromAuthority(account.authority).withScopes(SCOPES.toList())
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(result: IAuthenticationResult) {
                            Prefs.token = result.accessToken; Prefs.tokenExpiry = result.expiresOn.time
                            cont.resume(result.accessToken)
                        }
                        override fun onError(e: MsalException) { cont.resume(null) }
                    }).build())
            }
            override fun onAccountChanged(p: IAccount?, c: IAccount?) { cont.resume(null) }
            override fun onError(e: MsalException) { cont.resume(null) }
        })
    }

    fun signOut(onDone: () -> Unit) {
        msalApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() { Prefs.clear(); onDone() }
            override fun onError(e: MsalException) { Prefs.clear(); onDone() }
        }) ?: run { Prefs.clear(); onDone() }
    }
}
