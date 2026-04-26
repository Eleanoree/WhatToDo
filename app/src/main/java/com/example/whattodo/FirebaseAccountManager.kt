package com.example.whattodo

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class FirebaseBindingState(
    val isConfigured: Boolean,
    val isSignedIn: Boolean,
    val displayName: String? = null,
    val email: String? = null,
    val applicationId: String = "",
    val projectId: String = "",
    val webClientId: String = "",
)

class FirebaseAccountManager(context: Context) {
    private val appContext = context.applicationContext

    fun bindingState(): FirebaseBindingState {
        val configured = ensureInitialized()
        val auth = firebaseAuthOrNull()
        val user = auth?.currentUser
        return FirebaseBindingState(
            isConfigured = configured,
            isSignedIn = user != null,
            displayName = user?.displayName,
            email = user?.email,
            applicationId = generatedValue(R.string.google_app_id),
            projectId = generatedValue(R.string.project_id),
            webClientId = generatedValue(R.string.default_web_client_id),
        )
    }

    fun ensureInitialized(): Boolean {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) return hasRealFirebaseConfig()
        FirebaseApp.initializeApp(appContext) ?: return false
        return hasRealFirebaseConfig()
    }

    suspend fun signInWithGoogle(activity: Activity): FirebaseUser {
        check(ensureInitialized()) {
            "Firebase 尚未設定完成，無法進行 Google 帳號綁定。"
        }

        val credentialManager = CredentialManager.create(activity)
        val webClientId = generatedValue(R.string.default_web_client_id)
        check(webClientId.isNotBlank() && !webClientId.startsWith("REPLACE_ME")) {
            "Google Web Client ID 尚未設定，請先更新 google-services.json。"
        }
        val googleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val response = credentialManager.getCredential(activity, request)
        val credential = response.credential
        val googleIdTokenCredential = when (credential) {
            is CustomCredential ->
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    GoogleIdTokenCredential.createFrom(credential.data)
                } else {
                    throw IllegalStateException("不支援的 Google credential 類型：${credential.type}")
                }
            else -> throw IllegalStateException("無法辨識的 Google credential")
        }

        val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
        val authResult = firebaseAuth().signInWithCredential(authCredential).awaitResult()
        return authResult.user ?: throw IllegalStateException("登入成功，但沒有取得 Firebase 使用者資料")
    }

    suspend fun signOut() {
        firebaseAuthOrNull()?.signOut()
        runCatching {
            CredentialManager.create(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        }
    }

    fun currentUser(): FirebaseUser? = firebaseAuthOrNull()?.currentUser

    fun isConfigured(): Boolean = ensureInitialized()

    private fun firebaseAuth(): FirebaseAuth {
        ensureInitialized()
        val apps = FirebaseApp.getApps(appContext)
        val app = apps.firstOrNull() ?: throw IllegalStateException("Firebase 尚未初始化")
        return FirebaseAuth.getInstance(app)
    }

    private fun firebaseAuthOrNull(): FirebaseAuth? {
        if (!ensureInitialized()) return null
        val app = FirebaseApp.getApps(appContext).firstOrNull() ?: return null
        return FirebaseAuth.getInstance(app)
    }

    private fun generatedValue(resId: Int): String {
        val value = runCatching { appContext.getString(resId).trim() }.getOrDefault("")
        return if (value.startsWith("REPLACE_ME")) "" else value
    }

    private fun hasRealFirebaseConfig(): Boolean {
        val googleAppId = generatedValue(R.string.google_app_id)
        val webClientId = generatedValue(R.string.default_web_client_id)
        val projectId = generatedValue(R.string.project_id)
        return googleAppId.isNotBlank() && webClientId.isNotBlank() && projectId.isNotBlank()
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCoroutine { continuation ->
        addOnCompleteListener { task ->
            when {
                task.isSuccessful -> continuation.resume(task.result)
                task.isCanceled -> continuation.resumeWithException(IllegalStateException("登入已取消"))
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase 登入失敗"))
            }
        }
    }
}
