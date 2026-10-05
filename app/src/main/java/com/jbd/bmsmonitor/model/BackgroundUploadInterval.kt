package com.jbd.bmsmonitor.model

enum class BackgroundUploadInterval(val seconds: Int, val label: String) {
    THIRTY_SECONDS(30, "30 seconds"),
    ONE_MINUTE(60, "1 minute"),
    FIVE_MINUTES(300, "5 minutes"),
    TEN_MINUTES(600, "10 minutes"),
    THIRTY_MINUTES(1_800, "30 minutes");

    companion object {
        val DEFAULT = TEN_MINUTES

        fun fromSeconds(seconds: Int): BackgroundUploadInterval =
            entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}
