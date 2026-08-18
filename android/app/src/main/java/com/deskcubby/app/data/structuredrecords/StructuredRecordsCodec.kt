package com.deskcubby.app.data.structuredrecords

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codecs for the four `.deskcubby` workspace files. Every decoder is forward-compatible: it
 * ignores unknown properties so a newer schema never breaks an older app, and it safely falls back
 * to defaults for any missing or malformed value.
 */
object StructuredRecordsCodec {

    const val FILE_SETTINGS = "settings.json"
    const val FILE_FIELDS = "fields.json"
    const val FILE_RECORDS = "records.json"
    const val FILE_STATISTICS = "statistics.json"

    const val MAX_FIELDS = 400
    const val MAX_TEMPLATES = 400
    const val MAX_METRICS = 200
    const val MAX_OPTIONS = 200
    const val MAX_HISTORY = 200
    const val MAX_NAME_CHARS = 80
    const val MAX_TEXT_CHARS = 500

    // ---------------------------------------------------------------- settings.json

    fun encodeSettings(value: StructuredWorkspaceSettings): String = JSONObject()
        .put("schemaVersion", value.schemaVersion)
        .put("markdownProtocolVersion", value.markdownProtocolVersion)
        .put("dayBoundary", value.dayBoundary)
        .put(
            "dayBoundaryHistory",
            JSONArray().apply {
                value.dayBoundaryHistory
                    .sortedBy { it.effectiveFromJournalDay }
                    .forEach { entry ->
                        put(
                            JSONObject()
                                .put("effectiveFromJournalDay", entry.effectiveFromJournalDay)
                                .put("value", entry.value),
                        )
                    }
            },
        )
        .toString()

    fun decodeSettings(raw: String): StructuredWorkspaceSettings = runCatching {
        val json = JSONObject(raw)
        val schemaVersion = json.optInt("schemaVersion", 1)
        val protocolVersion = json.optInt("markdownProtocolVersion", 1)
        val boundary = json.optString("dayBoundary").takeIf(String::isNotBlank)
            ?: JournalDayEngine.DEFAULT_DAY_BOUNDARY
        val history = decodeHistory(json.optJSONArray("dayBoundaryHistory"))
        return@runCatching StructuredWorkspaceSettings(
            schemaVersion = schemaVersion,
            markdownProtocolVersion = protocolVersion,
            dayBoundary = if (JournalDayEngine.parseBoundary(boundary) != null) boundary
            else JournalDayEngine.DEFAULT_DAY_BOUNDARY,
            dayBoundaryHistory = history,
        )
    }.getOrElse { StructuredWorkspaceSettings() }

    private fun decodeHistory(array: JSONArray?): List<DayBoundaryRecord> {
        if (array == null) return emptyList()
        val result = ArrayList<DayBoundaryRecord>()
        for (index in 0 until array.length()) {
            val entry = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val from = entry.optString("effectiveFromJournalDay").trim()
            val value = entry.optString("value").trim()
            val validFrom = runCatching { java.time.LocalDate.parse(from) }.getOrNull()
            val validValue = JournalDayEngine.parseBoundary(value)
            if (validFrom == null || validValue == null) continue
            result += DayBoundaryRecord(from, JournalDayEngine.formatBoundary(validValue))
            if (result.size >= MAX_HISTORY) break
        }
        return result.distinctBy { it.effectiveFromJournalDay }
    }

    // ---------------------------------------------------------------- fields.json

    fun encodeFields(fields: List<StructuredField>): String = JSONObject()
        .put("schemaVersion", 1)
        .put(
            "fields",
            JSONArray().apply {
                fields.forEach { field -> put(encodeField(field)) }
            },
        )
        .toString()

    private fun encodeField(field: StructuredField): JSONObject = JSONObject()
        .put("id", field.id)
        .put("name", field.name)
        .put("type", field.type.wireValue)
        .put("source", field.source.wireValue)
        .apply {
            field.unit?.let { put("unit", it) }
            if (field.options.isNotEmpty()) {
                put("options", JSONArray(field.options))
            }
            put("allowCustomOption", field.allowCustomOption)
            field.collector?.let { put("collector", it) }
            put("archived", field.archived)
            field.sortOrder.takeIf { it != 0 }?.let { put("sortOrder", it) }
        }

    fun decodeFields(raw: String): List<StructuredField> = runCatching {
        val json = JSONObject(raw)
        val array = json.optJSONArray("fields") ?: return@runCatching emptyList()
        val result = ArrayList<StructuredField>(array.length())
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val id = item.optString("id").trim().take(120)
            if (id.isBlank() || !ids.add(id)) continue
            val type = StructuredFieldType.fromWire(item.optString("type")) ?: continue
            val source = StructuredFieldSource.fromWire(item.optString("source")) ?: StructuredFieldSource.MANUAL
            val name = item.optString("name").trim().take(MAX_NAME_CHARS)
            if (name.isBlank()) continue
            val options = decodeStringList(item.optJSONArray("options"))
            result += StructuredField(
                id = id,
                name = name,
                type = type,
                source = source,
                unit = item.optString("unit").takeIf(String::isNotBlank)?.take(20),
                options = options,
                allowCustomOption = if (item.has("allowCustomOption")) item.optBoolean("allowCustomOption") else true,
                collector = item.optString("collector").takeIf(String::isNotBlank)?.take(80),
                archived = item.optBoolean("archived"),
                sortOrder = item.optInt("sortOrder", 0),
            )
            if (result.size >= MAX_FIELDS) break
        }
        result
    }.getOrElse {
        // A corrupt workspace must never block the app; surface an empty list so the caller can
        // decide whether to re-seed defaults.
        emptyList()
    }

    // ---------------------------------------------------------------- records.json

    fun encodeTemplates(templates: List<StructuredRecordTemplate>): String = JSONObject()
        .put("schemaVersion", 1)
        .put(
            "records",
            JSONArray().apply {
                templates.forEach { template -> put(encodeTemplate(template)) }
            },
        )
        .toString()

    private fun encodeTemplate(template: StructuredRecordTemplate): JSONObject = JSONObject()
        .put("id", template.id)
        .put("name", template.name)
        .put(
            "segments",
            JSONArray().apply {
                template.segments.forEach { segment ->
                    when (segment) {
                        is StructuredRecordSegment.Text -> put(JSONObject().put("kind", "text").put("value", segment.value))
                        is StructuredRecordSegment.Field -> put(JSONObject().put("kind", "field").put("fieldId", segment.fieldId))
                    }
                }
            },
        )
        .put("archived", template.archived)
        .apply { template.sortOrder.takeIf { it != 0 }?.let { put("sortOrder", it) } }

    fun decodeTemplates(raw: String): List<StructuredRecordTemplate> = runCatching {
        val json = JSONObject(raw)
        val array = json.optJSONArray("records") ?: return@runCatching emptyList()
        val result = ArrayList<StructuredRecordTemplate>(array.length())
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val id = item.optString("id").trim().take(120)
            if (id.isBlank() || !ids.add(id)) continue
            val name = item.optString("name").trim().take(MAX_NAME_CHARS)
            val segments = decodeSegments(item.optJSONArray("segments"))
            if (segments.isEmpty()) continue
            result += StructuredRecordTemplate(
                id = id,
                name = name.ifBlank { "记录" },
                segments = segments,
                archived = item.optBoolean("archived"),
                sortOrder = item.optInt("sortOrder", 0),
            )
            if (result.size >= MAX_TEMPLATES) break
        }
        result
    }.getOrElse { emptyList() }

    private fun decodeSegments(array: JSONArray?): List<StructuredRecordSegment> {
        if (array == null) return emptyList()
        val result = ArrayList<StructuredRecordSegment>(array.length())
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            when (item.optString("kind")) {
                "text" -> item.optString("value").take(MAX_TEXT_CHARS).takeIf(String::isNotEmpty)?.let {
                    result += StructuredRecordSegment.Text(it)
                }
                "field" -> {
                    val fieldId = item.optString("fieldId").trim().take(120)
                    if (fieldId.isNotEmpty()) result += StructuredRecordSegment.Field(fieldId)
                }
            }
            if (result.size >= 64) break
        }
        return result
    }

    // ---------------------------------------------------------------- statistics.json

    fun encodeMetrics(metrics: List<StructuredMetric>): String = JSONObject()
        .put("schemaVersion", 1)
        .put(
            "metrics",
            JSONArray().apply {
                metrics.forEach { metric -> put(encodeMetric(metric)) }
            },
        )
        .toString()

    private fun encodeMetric(metric: StructuredMetric): JSONObject = JSONObject()
        .put("id", metric.id)
        .put("name", metric.name)
        .put("resultType", metric.resultType.wireValue)
        .put("expression", encodeExpression(metric.expression))
        .put(
            "display",
            JSONObject()
                .put("chart", metric.display.chart)
                .put("period", metric.display.period.wireValue),
        )
        .put("archived", metric.archived)
        .apply { metric.sortOrder.takeIf { it != 0 }?.let { put("sortOrder", it) } }

    private fun encodeExpression(expression: MetricExpression): JSONObject = when (expression) {
        is MetricExpression.FieldRef -> JSONObject()
            .put("op", "fieldRef")
            .put("fieldId", expression.ref.fieldId)
            .put("dayOffset", expression.ref.dayOffset)
            .put("selector", expression.ref.selector.wireValue)
        is MetricExpression.Constant -> JSONObject().put("op", "constant").put("value", expression.number)
        is MetricExpression.Add -> binary("add", expression.left, expression.right)
        is MetricExpression.Subtract -> binary("subtract", expression.left, expression.right)
        is MetricExpression.Multiply -> binary("multiply", expression.left, expression.right)
        is MetricExpression.Divide -> binary("divide", expression.left, expression.right)
        is MetricExpression.TimeDiff -> binary("timeDiff", expression.end, expression.start)
    }

    private fun binary(op: String, first: MetricExpression, second: MetricExpression): JSONObject {
        val root = JSONObject()
        // "end"/"start" naming for timeDiff; "left"/"right" for arithmetic.
        return when (op) {
            "timeDiff" -> root
                .put("op", op)
                .put("end", encodeExpression(first))
                .put("start", encodeExpression(second))
            else -> root
                .put("op", op)
                .put("left", encodeExpression(first))
                .put("right", encodeExpression(second))
        }
    }

    fun decodeMetrics(raw: String): List<StructuredMetric> = runCatching {
        val json = JSONObject(raw)
        val array = json.optJSONArray("metrics") ?: return@runCatching emptyList()
        val result = ArrayList<StructuredMetric>(array.length())
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
            val id = item.optString("id").trim().take(120)
            if (id.isBlank() || !ids.add(id)) continue
            val name = item.optString("name").trim().take(MAX_NAME_CHARS)
            val resultType = MetricResultType.fromWire(item.optString("resultType")) ?: continue
            val expression = decodeExpression(item.optJSONObject("expression")) ?: continue
            val display = item.optJSONObject("display")
            val period = MetricChartPeriod.fromWire(display?.optString("period"))
                ?: MetricChartPeriod.DAY
            result += StructuredMetric(
                id = id,
                name = name.ifBlank { "指标" },
                resultType = resultType,
                expression = expression,
                display = MetricDisplay(
                    chart = display?.optString("chart")?.takeIf(String::isNotBlank) ?: "line",
                    period = period,
                ),
                archived = item.optBoolean("archived"),
                sortOrder = item.optInt("sortOrder", 0),
            )
            if (result.size >= MAX_METRICS) break
        }
        result
    }.getOrElse { emptyList() }

    private fun decodeExpression(json: JSONObject?): MetricExpression? {
        if (json == null) return null
        return when (json.optString("op")) {
            "fieldRef" -> {
                val ref = json.optJSONObject("ref")
                val fieldId = (ref?.optString("fieldId") ?: json.optString("fieldId")).trim().take(120)
                if (fieldId.isEmpty()) null
                else MetricExpression.FieldRef(
                    FieldRefNode(
                        fieldId = fieldId,
                        dayOffset = (ref?.opt("dayOffset") ?: json.opt("dayOffset"))
                            ?.let { runCatching { (it as? Number)?.toInt() }.getOrNull() } ?: 0,
                        selector = FieldSelector.fromWire(
                            ref?.optString("selector") ?: json.optString("selector"),
                        ) ?: FieldSelector.LAST,
                    ),
                )
            }
            "constant" -> MetricExpression.Constant(
                json.opt("value")?.let { runCatching { (it as Number).toDouble() }.getOrNull() }
                    ?: return null,
            )
            "add" -> binaryEval("add", json)
            "subtract" -> binaryEval("subtract", json)
            "multiply" -> binaryEval("multiply", json)
            "divide" -> binaryEval("divide", json)
            "timeDiff" -> binaryEval("timeDiff", json)
            else -> null
        }
    }

    private fun binaryEval(op: String, json: JSONObject): MetricExpression? {
        if (op == "timeDiff") {
            val end = decodeExpression(json.optJSONObject("end")) ?: return null
            val start = decodeExpression(json.optJSONObject("start")) ?: return null
            return MetricExpression.TimeDiff(end, start)
        }
        val left = decodeExpression(json.optJSONObject("left")) ?: return null
        val right = decodeExpression(json.optJSONObject("right")) ?: return null
        return when (op) {
            "add" -> MetricExpression.Add(left, right)
            "subtract" -> MetricExpression.Subtract(left, right)
            "multiply" -> MetricExpression.Multiply(left, right)
            "divide" -> MetricExpression.Divide(left, right)
            else -> null
        }
    }

    private fun decodeStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = runCatching { array.getString(index) }.getOrNull()?.trim().orEmpty()
            if (value.isNotEmpty() && value !in result) result += value
            if (result.size >= MAX_OPTIONS) break
        }
        return result
    }
}
