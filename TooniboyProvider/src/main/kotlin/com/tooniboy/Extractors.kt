        val finalUrl = m3u8 ?: return

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Key 7: VIDMOLY → vidmoly.net  (jwplayer file regex)
// ─────────────────────────────────────────────────────────────
