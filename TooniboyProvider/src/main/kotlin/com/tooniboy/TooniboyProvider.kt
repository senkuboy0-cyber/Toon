    /**
     * Full title cleaner (parity with AnimeDekho cleanTitleText + extractRawTitle):
     * strips "Watch Online", episode patterns (1x08 / Episode 5),
     * Season suffixes, dub/audio language suffixes (Hindi Dub / in Hindi etc.),
     * fandub markers, and anything inside (...)/[...].
     */
    private fun cleanForTmdb(title: String): String {
        var t = title.replace(Regex("Watch Online", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+\\d+[x×]\\d+.*"), "")                       // " 1x8 ..." suffixes
        t = t.replace(Regex("\\s+Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+Season\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        // trailing dub/language markers: "... Hindi Dub", "... in Hindi", "... Dubbed"
        t = t.replace(Regex("\\s+(?:in\\s+)?(?:hindi|tamil|telugu|english|japanese)\\s*(?:dub(?:bed)?)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+dub(?:bed)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fan\\s*dub.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fandub.*", RegexOption.IGNORE_CASE), "")
        t = t.substringBefore("(").substringBefore("[")
        t = t.trim()
        return t.ifBlank { title }
    }
