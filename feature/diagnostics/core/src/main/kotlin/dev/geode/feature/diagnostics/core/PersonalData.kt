package dev.geode.feature.diagnostics.core

/**
 * Takes personal data out of free text before it goes into the diagnostics file (#62): uris, file
 * paths, the names of media and other files, e-mail addresses and anything in quotes. It errs on the
 * side of removing too much: a log line that loses a word is still useful, a song title in a shared
 * file is not acceptable.
 */
object PersonalData {
    const val URI = "<uri>"
    const val PATH = "<path>"
    const val FILE = "<file>"
    const val EMAIL = "<email>"
    const val QUOTED = "<text>"
    const val HIDDEN = "<hidden>"

    /** File extensions whose names are hidden: music, video, playlists, lyrics, presets and projects. */
    private const val EXTENSIONS =
        "mp3|flac|ogg|oga|opus|m4a|aac|wav|wma|alac|aiff?|ape|wv|mka|mid|midi|mp4|m4v|webm|mkv|mov|3gp|" +
            "m3u8?|pls|cue|lrc|json|milk|zip|txt|jpe?g|png|webp|gif"

    /**
     * Words in a setting's name that mean its value points at the user's things (a folder, a file, an
     * account), so the value is hidden whatever it looks like.
     */
    private val personalSettingHints = listOf("path", "uri", "folder", "dir", "file", "account", "mail", "user", "name")

    private val uris = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
    private val emails = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    // A file name may hold spaces: take the run of words that ends in a known extension.
    private val fileNames = Regex("""(?:[^\s/\\:()\[\]"']+ )*[^\s/\\:()\[\]"']+\.(?:$EXTENSIONS)\b""", RegexOption.IGNORE_CASE)
    private val paths = Regex("""(?<![\w/])/(?:[^\s/]+/)+[^\s/]*""")
    private val quoted = Regex(""""[^"\n]*"|'[^'\n]*'""")

    /** [text] with every uri, path, file name, e-mail address and quotation replaced by a marker. */
    fun redact(text: String): String =
        text
            .replace(uris, URI)
            .replace(emails, EMAIL)
            .replace(fileNames, FILE)
            .replace(paths, PATH)
            .replace(quoted, QUOTED)

    /** The value of [setting] as the report may show it: hidden when the setting names the user's things. */
    fun redactSetting(setting: Setting): String {
        val name = setting.name.lowercase()
        return if (personalSettingHints.any { it in name }) HIDDEN else redact(setting.value)
    }
}
