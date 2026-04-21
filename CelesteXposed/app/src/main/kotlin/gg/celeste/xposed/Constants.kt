package gg.celeste.xposed

class Constants {
    companion object {
        const val TARGET_PACKAGE = "com.discord"
        const val TARGET_ACTIVITY = "$TARGET_PACKAGE.react_activities.ReactActivity"

        const val FILES_DIR = "files/pyoncord"
        const val CACHE_DIR = "cache/pyoncord"
        const val MAIN_SCRIPT_FILE = "bundle.js"


        const val LOG_TAG = "Celeste"

        const val LOADER_NAME = "CelesteXposed"

        const val USER_AGENT = "CelesteXposed"
    }
}