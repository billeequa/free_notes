package com.plainnotes.android.data

import com.plainnotes.android.model.NoteTextContent
import com.plainnotes.android.model.NoteType
import com.plainnotes.android.model.NoteCategory
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object NoteFileParser {
    private const val titlePrefix = "Title:"
    private const val createdPrefix = "Created:"
    private const val modifiedPrefix = "Modified:"
    private const val typePrefix = "Note-Type:"
    private const val doubleXDatePrefix = "Double-X-Date:"
    private const val categoryPrefix = "Category:"
    private const val journalDatePrefix = "Journal-Date:"
    private const val doubleXTemplatePrefix = "Double-X-Template:"
    private val storageFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val legacyFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    internal fun isMetadataLine(line: String): Boolean = listOf(titlePrefix, createdPrefix, modifiedPrefix,
        typePrefix, doubleXDatePrefix, categoryPrefix, journalDatePrefix, doubleXTemplatePrefix)
        .any { line.startsWith(it, ignoreCase = true) }

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
        if (content.noteType == NoteType.DOUBLE_X_DAY && content.doubleXDate != null) {
            appendLine("$typePrefix DOUBLE_X_DAY")
            appendLine("$doubleXDatePrefix ${content.doubleXDate}")
            if (content.doubleXTemplateVersion > 0) {
                appendLine("$doubleXTemplatePrefix ${content.doubleXTemplateVersion}")
            }
        }
        appendLine("$categoryPrefix ${content.category.name}")
        content.journalDate?.let { appendLine("$journalDatePrefix $it") }
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
        var lineStart = 0
        var bodyStart = 0

        var title: String? = null
        var created: OffsetDateTime? = null
        var modified: OffsetDateTime? = null
        var noteType = NoteType.NORMAL
        var doubleXDate: LocalDate? = null
        var category: NoteCategory? = null
        var journalDate: LocalDate? = null
        var doubleXTemplateVersion = 0
        var titleHeaderSeen = false
        var metadataLineCount = 0

        while (lineStart <= normalized.length) {
            val lineEnd = normalized.indexOf('\n', lineStart).let { if (it < 0) normalized.length else it }
            val line = normalized.substring(lineStart, lineEnd)
            if (line.isBlank()) {
                bodyStart = if (metadataLineCount == 0) 0 else (lineEnd + 1).coerceAtMost(normalized.length)
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

                line.startsWith(typePrefix, ignoreCase = true) -> {
                    noteType = if (line.substringAfter(':').trim() == "DOUBLE_X_DAY") NoteType.DOUBLE_X_DAY else NoteType.NORMAL
                    true
                }

                line.startsWith(categoryPrefix, ignoreCase = true) -> {
                    category = runCatching { NoteCategory.valueOf(line.substringAfter(':').trim()) }.getOrNull()
                    true
                }

                line.startsWith(journalDatePrefix, ignoreCase = true) -> {
                    journalDate = runCatching { LocalDate.parse(line.substringAfter(':').trim()) }.getOrNull()
                    true
                }

                line.startsWith(doubleXDatePrefix, ignoreCase = true) -> {
                    doubleXDate = runCatching { LocalDate.parse(line.substringAfter(':').trim()) }.getOrNull()
                    true
                }

                line.startsWith(doubleXTemplatePrefix, ignoreCase = true) -> {
                    doubleXTemplateVersion = line.substringAfter(':').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    true
                }

                else -> false
            }

            if (!matched) {
                metadataLineCount = 0
                bodyStart = 0
                break
            }

            metadataLineCount += 1
            bodyStart = (lineEnd + 1).coerceAtMost(normalized.length)
            if (lineEnd == normalized.length) break
            lineStart = lineEnd + 1
        }

        val body = if (bodyStart == 0) normalized else normalized.substring(bodyStart)

        val inferredTitle = when {
            titleHeaderSeen -> title.orEmpty()
            else -> inferTitle(body, fallbackFileName)
        }

        val resolvedType = if (doubleXDate == null) NoteType.NORMAL else noteType
        val resolvedCategory = category ?: if (resolvedType == NoteType.DOUBLE_X_DAY) NoteCategory.JOURNAL else NoteCategory.NOTES
        val resolvedCreated = created ?: modified ?: fallbackModified
        // Legacy Double X files appear in Journal without rewriting their body.
        val resolvedTitle = if (category == null && resolvedType == NoteType.DOUBLE_X_DAY)
            "$doubleXDate Double X Day" else inferredTitle
        return NoteTextContent(
            title = resolvedTitle,
            createdAt = resolvedCreated,
            modifiedAt = modified ?: fallbackModified,
            body = body,
            noteType = resolvedType,
            doubleXDate = doubleXDate,
            category = resolvedCategory,
            journalDate = journalDate ?: doubleXDate ?: if (resolvedCategory == NoteCategory.JOURNAL) resolvedCreated.toLocalDate() else null,
            doubleXTemplateVersion = doubleXTemplateVersion,
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
