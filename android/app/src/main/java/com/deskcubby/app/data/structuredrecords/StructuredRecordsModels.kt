package com.deskcubby.app.data.structuredrecords

/** The five V1 strongly-typed field kinds. Each has its own validation and statistics semantics. */
enum class StructuredFieldType(val wireValue: String) {
    WORD("word"),
    NUMBER("number"),
    TYPE("type"),
    TIME("time"),
    DURATION("duration");

    companion object {
        fun fromWire(value: String?): StructuredFieldType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** How a raw (original) field value is obtained. */
enum class StructuredFieldSource(val wireValue: String) {
    MANUAL("manual"),
    SYSTEM("system"),
    AGENT("agent");

    companion object {
        fun fromWire(value: String?): StructuredFieldSource? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * A structured field definition. Identity is the stable [id]; display [name] may change freely
 * without breaking history. Lives in `.deskcubby/fields.json`. Unknown JSON properties are
 * tolerated by the codec so future fields stay forward-compatible.
 */
data class StructuredField(
    val id: String,
    val name: String,
    val type: StructuredFieldType,
    val source: StructuredFieldSource = StructuredFieldSource.MANUAL,
    val unit: String? = null,
    val options: List<String> = emptyList(),
    val allowCustomOption: Boolean = true,
    /** For system fields: the collector tag, e.g. "first_phone_unlock" / "last_phone_lock". */
    val collector: String? = null,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * One segment of a structured-record template. Templates keep the user-friendly DSL/placeholder
 * notion (`[number](俯卧撑)`, `[time]`, ...) internally, but persist as a list of segments that
 * reference stable field IDs directly instead of a bare string that must be re-parsed by name.
 */
sealed interface StructuredRecordSegment {
    data class Text(val value: String) : StructuredRecordSegment
    data class Field(val fieldId: String) : StructuredRecordSegment
}

/** A reusable template that inserts a structured record into a journal Markdown file. */
data class StructuredRecordTemplate(
    val id: String,
    val name: String,
    val segments: List<StructuredRecordSegment>,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/** A checked unit-compatibility / selector constraint failure surfaced to the user. */
class StructuredRecordsException(message: String) : Exception(message)

/** The period a chart is bucketed by. */
enum class MetricChartPeriod(val wireValue: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    companion object {
        fun fromWire(value: String?): MetricChartPeriod? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Aggregation selector applied to all values of a field on one natural calendar date. */
enum class FieldSelector(val wireValue: String) {
    FIRST("first"),
    LAST("last"),
    MIN("min"),
    MAX("max"),
    SUM("sum"),
    AVERAGE("average"),
    COUNT("count");

    companion object {
        fun fromWire(value: String?): FieldSelector? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The type produced by a derived metric. This mirrors [StructuredFieldType] but is a separate axis:
 * a metric's resultType documents what its expression evaluates to (number, time, duration, ...),
 * by construction for the user's chosen "result type".
 */
enum class MetricResultType(val wireValue: String) {
    NUMBER("number"),
    TIME("time"),
    DURATION("duration");

    companion object {
        fun fromWire(value: String?): MetricResultType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A reference to a field's value on a natural calendar date, with an aggregation selector. */
data class FieldRefNode(
    val fieldId: String,
    val dayOffset: Int = 0,
    val selector: FieldSelector = FieldSelector.LAST,
)

/** A structured expression AST. Custom statistics are built from these nodes, never from eval(). */
sealed interface MetricExpression {
    data class FieldRef(val ref: FieldRefNode) : MetricExpression
    data class Constant(val number: Double) : MetricExpression
    data class Add(val left: MetricExpression, val right: MetricExpression) : MetricExpression
    data class Subtract(val left: MetricExpression, val right: MetricExpression) : MetricExpression
    data class Multiply(val left: MetricExpression, val right: MetricExpression) : MetricExpression
    data class Divide(val left: MetricExpression, val right: MetricExpression) : MetricExpression
    /** Duration between two time/datetime-like expressions: result = end - start. */
    data class TimeDiff(val end: MetricExpression, val start: MetricExpression) : MetricExpression
}

data class MetricDisplay(val chart: String = "line", val period: MetricChartPeriod = MetricChartPeriod.DAY)

/**
 * A user-created derived statistic. Lives in `.deskcubby/statistics.json` and is NOT a raw field
 * written back to Markdown every day.
 */
data class StructuredMetric(
    val id: String,
    val name: String,
    val resultType: MetricResultType,
    val expression: MetricExpression,
    val display: MetricDisplay = MetricDisplay(),
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * Workspace protocol settings stored in `.deskcubby/settings.json`.
 *
 * [dayBoundary] is a compatibility/UI adapter only: [StructuredWorkspaceRepository] overlays the
 * device-local “今日日记切换时间” here so the existing settings screen can keep using its current
 * state shape. [StructuredRecordsCodec] never persists it, and no record/statistics code may use it.
 */
data class StructuredWorkspaceSettings(
    val schemaVersion: Int = 1,
    val markdownProtocolVersion: Int = 1,
    val dayBoundary: String = JournalDayEngine.DEFAULT_DAY_BOUNDARY,
)

/** The five default starter examples, one per type (editable / deletable by the user). */
object DefaultStructuredExamples {
    const val FIELD_TODAY_SENTENCE = "f_word_today"
    const val FIELD_PUSHUPS = "f_number_pushups"
    const val FIELD_TOP_COLOR = "f_type_top_color"
    const val FIELD_LUNCH_TIME = "f_time_lunch"
    const val FIELD_NAP_DURATION = "f_duration_nap"

    val FIELDS: List<StructuredField> = listOf(
        StructuredField(
            id = FIELD_TODAY_SENTENCE,
            name = "今日一句话",
            type = StructuredFieldType.WORD,
            source = StructuredFieldSource.MANUAL,
            sortOrder = 0,
        ),
        StructuredField(
            id = FIELD_PUSHUPS,
            name = "俯卧撑次数",
            type = StructuredFieldType.NUMBER,
            source = StructuredFieldSource.MANUAL,
            unit = "次",
            sortOrder = 1,
        ),
        StructuredField(
            id = FIELD_TOP_COLOR,
            name = "今天衣服颜色",
            type = StructuredFieldType.TYPE,
            source = StructuredFieldSource.MANUAL,
            options = listOf("黑色", "白色", "蓝色", "灰色"),
            allowCustomOption = true,
            sortOrder = 2,
        ),
        StructuredField(
            id = FIELD_LUNCH_TIME,
            name = "午饭时间",
            type = StructuredFieldType.TIME,
            source = StructuredFieldSource.MANUAL,
            sortOrder = 3,
        ),
        StructuredField(
            id = FIELD_NAP_DURATION,
            name = "午睡时长",
            type = StructuredFieldType.DURATION,
            source = StructuredFieldSource.MANUAL,
            sortOrder = 4,
        ),
    )

    val TEMPLATES: List<StructuredRecordTemplate> = listOf(
        StructuredRecordTemplate(
            id = "r_word_today",
            name = "今日一句话",
            segments = listOf(
                StructuredRecordSegment.Field(FIELD_TODAY_SENTENCE),
            ),
            sortOrder = 0,
        ),
        StructuredRecordTemplate(
            id = "r_pushups",
            name = "俯卧撑次数",
            segments = listOf(
                StructuredRecordSegment.Text("做了 "),
                StructuredRecordSegment.Field(FIELD_PUSHUPS),
                StructuredRecordSegment.Text(" 个俯卧撑"),
            ),
            sortOrder = 1,
        ),
        StructuredRecordTemplate(
            id = "r_top_color",
            name = "今天衣服颜色",
            segments = listOf(
                StructuredRecordSegment.Text("上衣："),
                StructuredRecordSegment.Field(FIELD_TOP_COLOR),
            ),
            sortOrder = 2,
        ),
        StructuredRecordTemplate(
            id = "r_lunch_time",
            name = "午饭时间",
            segments = listOf(
                StructuredRecordSegment.Text("午饭："),
                StructuredRecordSegment.Field(FIELD_LUNCH_TIME),
            ),
            sortOrder = 3,
        ),
        StructuredRecordTemplate(
            id = "r_nap_duration",
            name = "午睡时长",
            segments = listOf(
                StructuredRecordSegment.Text("午睡："),
                StructuredRecordSegment.Field(FIELD_NAP_DURATION),
            ),
            sortOrder = 4,
        ),
    )
}

/** Custom field source tags used by the system sleep/wake collectors. */
const val COLLECTOR_FIRST_PHONE_UNLOCK = "first_phone_unlock"
const val COLLECTOR_LAST_PHONE_LOCK = "last_phone_lock"
const val SYSTEM_FIELD_SLEEP_TIME = "f_system_sleep_time"
const val SYSTEM_FIELD_WAKE_TIME = "f_system_wake_time"
