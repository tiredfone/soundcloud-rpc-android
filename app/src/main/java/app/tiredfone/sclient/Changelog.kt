package app.tiredfone.sclient

data class ChangelogEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

object Changelog {
    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "1.11",
            date = "June 2026",
            changes = listOf(
                "Apple Music-inspired UI: pure black background, flat track rows, rounded artwork",
                "Now Playing: shuffle, skip back, repeat buttons + volume slider",
                "Now Playing: dynamic gradient background based on album art colors",
                "Like/unlike tracks — uses your SoundCloud web session (experimental)",
                "Like button in Now Playing screen"
            )
        ),
        ChangelogEntry(
            version = "1.10",
            date = "June 2026",
            changes = listOf(
                "Fixed playlist tracks not loading (fallback to batch-fetch when /tracks endpoint returns 404)",
                "Fixed like/unlike buttons — added logging and correct Content-Type header",
                "Playlist tracks now load even for liked/shared playlists from other users"
            )
        ),
        ChangelogEntry(
            version = "1.09",
            date = "June 2026",
            changes = listOf(
                "Fixed Playlists tab not loading (switched to /users/{id}/playlists endpoint)",
                "Material Design 3 theme with dark music-app styling",
                "Bottom navigation bar replaces top tabs",
                "Track cards redesigned with rounded corners and artist name in orange",
                "Three-dot menu on each track: Add to playlist, View artist profile",
                "Artist profile screen with their tracks list",
                "Improved Now Playing screen with larger artwork and rounded corners"
            )
        ),
        ChangelogEntry(
            version = "1.08",
            date = "June 2026",
            changes = listOf(
                "Fixed Likes tab not loading (switched to /users/{id}/likes endpoint)",
                "Notification now shows song album art as large icon",
                "Silenced harmless Discord presence-array log errors"
            )
        ),
        ChangelogEntry(
            version = "1.07",
            date = "June 2026",
            changes = listOf(
                "Renamed app to SClient (SoundCloud Client)",
                "New package ID: app.tiredfone.sclient",
                "Added this changelog screen",
                "\"What's New\" popup after each update"
            )
        ),
        ChangelogEntry(
            version = "1.06",
            date = "June 2026",
            changes = listOf(
                "Consistent APK signing — install updates without uninstalling",
                "SoundCloud token auto-refresh on expiry",
                "Login persists after reinstall via Android Auto Backup"
            )
        ),
        ChangelogEntry(
            version = "1.05",
            date = "June 2026",
            changes = listOf(
                "Fixed Likes tab not loading (switched to /me/likes endpoint)",
                "Fixed Playlists tab not loading (correct /me/playlists/liked_and_owned)",
                "Fixed Discord RPC not starting after saving settings"
            )
        ),
        ChangelogEntry(
            version = "1.04",
            date = "June 2026",
            changes = listOf(
                "Added in-app log viewer (⋮ → View Logs)",
                "Detailed API and gateway logging for debugging"
            )
        ),
        ChangelogEntry(
            version = "1.03",
            date = "June 2026",
            changes = listOf(
                "Attempted fix for likes API format detection",
                "Removed invalid representation=compact from playlists request"
            )
        ),
        ChangelogEntry(
            version = "1.02",
            date = "June 2026",
            changes = listOf(
                "Fixed build error (JVM signature clash in TrackAdapter)",
                "Heart button for liking tracks from any list"
            )
        ),
        ChangelogEntry(
            version = "1.01",
            date = "June 2026",
            changes = listOf(
                "Stream, search, likes, and playlists tabs",
                "Create playlists and add tracks to playlists",
                "Discord Rich Presence via Gateway WebSocket",
                "US region support for stream URL resolution",
                "Infinite scroll pagination",
                "Mini player in all screens"
            )
        )
    )
}
