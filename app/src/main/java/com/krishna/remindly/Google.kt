package com.krishna.remindly

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/**
 * Google sign-in for Remindly.
 *
 * v1.36: the Drive app-data backup is retired — cross-device sync is now live via Firestore
 * (see Sync.kt / "Cloud sync"). Sign-in remains for two reasons: it establishes the Firebase
 * Auth session that Cloud sync attaches to (via the ID token), and it powers Forgot-PIN
 * recovery. No Drive scope is requested anymore, so nothing can hit the Drive API.
 * A manual offline backup is still available via Backup & Restore (file export/import).
 */
object GoogleSync {

    // Firebase Web client ID (oauth_client type 3 in google-services.json) — lets Google sign-in
    // yield an ID token we exchange for a Firebase Auth session, so two devices on one account
    // share the same Firestore root /users/{uid}.
    const val WEB_CLIENT_ID = "130068095997-917kasd7egn1ieqn8iu69v02rt9ojqs2.apps.googleusercontent.com"

    fun client(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // v1.56 Plan B: read-only calendar access for the direct cloud read.
            .requestScopes(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/calendar.readonly"))
            .requestIdToken(WEB_CLIENT_ID)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun email(context: Context): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    fun signInIntent(context: Context): Intent = client(context).signInIntent

    fun signOut(context: Context) {
        runCatching { client(context).signOut() }
    }
}
