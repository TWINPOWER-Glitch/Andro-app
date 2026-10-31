
package com.twinpower.andro

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient

object AndroSupabase {
    const val REDIRECT_SCHEME = "andro"
    const val REDIRECT_HOST = "auth"

    private const val PROJECT_URL =
        "https://pgnphlssbrphbrnkwqya.supabase.co"

    // Public publishable key. Never put a service-role key in the app.
    private const val PUBLISHABLE_KEY =
        "sb_publishable_qztLqwaehwQk2H9k0Q8xbg_ABkoxyaa"

    val client = createSupabaseClient(
        supabaseUrl = PROJECT_URL,
        supabaseKey = PUBLISHABLE_KEY
    ) {
        install(Auth) {
            scheme = REDIRECT_SCHEME
            host = REDIRECT_HOST
            flowType = FlowType.PKCE
        }
    }
}
