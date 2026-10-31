package com.assetboard.data

/**
 * The self-hosted server before the October 2026 move from the US VPS to the Tokyo one. Profiles
 * and preferences still holding it are pointed at the current default, so an upgrade keeps the
 * existing login instead of silently calling a server that no longer exists.
 */
internal const val LEGACY_SYNC_SERVER_URL = "https://khw-asset.216-158-79-137.sslip.io"
