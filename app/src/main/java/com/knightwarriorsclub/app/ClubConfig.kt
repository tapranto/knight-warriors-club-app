package com.knightwarriorsclub.app

/**
 * Club-wide constants.
 *
 * The Supabase URL and PUBLISHABLE key are public-safe by design (the same pair
 * the website ships to every browser). Row Level Security in the database is
 * what actually protects member data. NEVER put a secret/service-role key here.
 */
object ClubConfig {
    const val SUPABASE_URL = "https://jpurbipmekomqjdjvquo.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_htkGQfItE4eW5WLngiShdw_30wHl4xF"
    const val WEBSITE_URL = "https://knightwarriorsclub.com"
    // Phase 3: TURN credentials for calls across networks (own Metered account).
    // Phase 2: auth + data layer binds to SUPABASE_URL above.
}
