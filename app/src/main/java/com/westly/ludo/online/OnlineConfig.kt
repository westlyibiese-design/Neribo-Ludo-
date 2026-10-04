package com.westly.ludo.online

/**
 * The three values Online play needs. All three are PUBLIC: the publishable key is protected by
 * Row Level Security on the server, and a Google Web client ID is just a name, not a password.
 *
 * Never put a secret here: no Supabase secret / service_role key, no database password and no
 * Google client secret.
 */
object OnlineConfig {
    const val SUPABASE_URL = "https://hhugguwoddwdwljuazyt.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_z06-KQj9lle8hfebBFcLlg_iXEcZgD2"
    const val GOOGLE_WEB_CLIENT_ID =
        "381791843965-auoqugf8m7mprrvlmek34upr1nge91bt.apps.googleusercontent.com"
}
