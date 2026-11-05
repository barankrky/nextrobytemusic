package com.music.bitchord.desktop

import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.nio.file.Path

/**
 * The listener's own Music and Downloads folders.
 *
 * `%USERPROFILE%\Music` is only the default: Windows lets both known folders be moved, and the
 * registry then still calls the old location "My Music" for anything that hard-codes it. Asking
 * the shell for the known folder follows the move, and the XDG variables do the same on Linux.
 */
internal object DesktopUserFolders {

    val music: Path by lazy { resolve("Music", "XDG_MUSIC_DIR", KnownFolders.FOLDERID_Music) }

    val downloads: Path by lazy { resolve("Downloads", "XDG_DOWNLOAD_DIR", KnownFolders.FOLDERID_Downloads) }

    private fun resolve(name: String, xdgVariable: String, windowsFolder: Guid.GUID): Path {
        if (DesktopPlatform.isWindows) {
            // The shell's answer, which is the relocated folder rather than the profile one.
            runCatching { Path.of(Shell32Util.getKnownFolderPath(windowsFolder)) }
                .getOrNull()
                ?.takeIf { it.toString().isNotBlank() }
                ?.let { return it }
        }
        if (DesktopPlatform.isLinux) {
            System.getenv(xdgVariable)
                ?.takeIf(String::isNotBlank)
                ?.let { value -> runCatching { Path.of(expandHome(value)) }.getOrNull() }
                ?.let { return it }
        }
        return Path.of(System.getProperty("user.home"), name)
    }

    /** XDG variables are normally absolute, but `$HOME/...` is legal and easy to produce. */
    private fun expandHome(value: String): String {
        val home = System.getProperty("user.home")
        return when {
            value == "~" -> home
            value.startsWith("~/") -> home + value.drop(1)
            value.startsWith("\$HOME/") -> home + value.drop(5)
            else -> value
        }
    }
}
