package com.plainnotes.android.data

import com.plainnotes.android.model.NoteTextContent
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object NoteFileParser {
    private const val titlePrefix = "Title:"
    private const val createdPrefix = "Created:"
    private const val modifiedPrefix = "Modified:"
    private val storageFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val legacyFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun serialize(content: NoteTextContent): String = buildString {
        append(titlePrefix)
        append(' ')
        append(content.title)
        append('\n')
        append(createdPrefix)
        append(' ')
        append(content.createdAt.toLocalDateTime().format(storageFormatter))
        append('\n')
        append(modifiedPrefix)
        append(' ')
        append(content.modifiedAt.toLocalDateTime().format(storageFormatter))
        append('\n')
        append('\n')
        append(content.body)
    }

    fun parse(
        rawText: String,
        fallbackFileName: String?,
        fallbackLastModifiedMillis: Long,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): NoteTextContent {
        val normalized = rawText.replace("\r\n", "\n")
        val fallbackModified = fallbackFromMillis(fallbackLastModifiedMillis) ?: now
        val lines = normalized.split('\n')

        var title: String? = null
        var created: OffsetDateTime? = null
        var modified: OffsetDateTime? = null
        var titleHeaderSeen = false
        var metadataLineCount = 0

        while (metadataLineCount < lines.size) {
            val line = lines[metadataLineCount]
            if (line.isBlank()) {
                break
            }

            val matched = when {
                line.startsWith(titlePrefix, ignoreCase = true) -> {
                    titleHeaderSeen = true
                    title = line.substringAfter(':').trimStart()
                    true
                }

                line.startsWith(createdPrefix, ignoreCase = true) -> {
                    created = parseDate(line.substringAfter(':').trim())
                    true
                }

                line.startsWith(modifiedPrefix, ignoreCase = true) -> {
                    modified = parseDate(line.substringAfter(':').trim())
                    true
                }

                else -> false
            }

            if (!matched) {
                metadataLineCount = 0
                break
            }

            metadataLineCount += 1
        }

        val body = when {
            metadataLineCount == 0 -> normalized
            metadataLineCount < lines.size && lines[metadataLineCount].isBlank() ->
                lines.drop(metadataLineCount + 1).joinToString("\n")

            else -> lines.drop(metadataLineCount).joinToString("\n")
        }

        val inferredTitle = when {
            titleHeaderSeen -> title.orEmpty()
            else -> inferTitle(body, fallbackFileName)
        }

        return NoteTextContent(
            title = inferredTitle,
            createdAt = created ?: modified ?: fallbackModified,
            modifiedAt = modified ?: fallbackModified,
            body = body,
        )
    }

    private fun parseDate(value: String): OffsetDateTime? = runCatching {
        OffsetDateTime.parse(value, legacyFormatter)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value, storageFormatter)
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
    }.getOrNull()

    private fun fallbackFromMillis(value: Long): OffsetDateTime? {
        if (value <= 0L) {
            return null
        }

        return Instant.ofEpochMilli(value)
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
    }

    private fun inferTitle(body: String, fallbackFileName: String?): String {
        val firstBodyLine = body.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
        if (!firstBodyLine.isNullOrEmpty()) {
            return firstBodyLine
        }

        val fallbackName = fallbackFileName
            ?.removeSuffix(".txt")
            ?.replace('-', ' ')
            ?.trim()

        return fallbackName.orEmpty()
    }
}
