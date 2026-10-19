package app.animix.data.watchflow

actual fun flowTimeLabel(millis: Long): String = java.time.Instant.ofEpochMilli(millis)
    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
