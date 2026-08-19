package com.deskcubby.app.agent

import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.AppAPI
import com.deskcubby.plugin.api.core.api.AppSettingMutationResult
import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import com.deskcubby.plugin.api.core.api.DeskCubbyDataEntry
import com.deskcubby.plugin.api.core.api.DeskCubbyDataMutationRequest
import com.deskcubby.plugin.api.core.api.DeskCubbyDataQuery
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationOperation
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationResult
import com.deskcubby.plugin.api.core.api.FileDocument
import com.deskcubby.plugin.api.core.api.FileAPI
import com.deskcubby.plugin.api.core.api.FileEntry
import com.deskcubby.plugin.api.core.api.FileMutationOperation
import com.deskcubby.plugin.api.core.api.FileMutationRequest
import com.deskcubby.plugin.api.core.api.FileMutationResult
import com.deskcubby.plugin.api.core.api.FileQuery
import com.deskcubby.plugin.api.core.api.FileSearchQuery
import com.deskcubby.plugin.api.core.api.AIToolCall
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.structuredrecords.FieldSelector
import com.deskcubby.app.data.structuredrecords.StructuredFieldNormalizer
import com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
import com.deskcubby.app.data.structuredrecords.StructuredStatisticsRepository
import com.deskcubby.app.data.structuredrecords.StructuredWorkspaceRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class BuiltInAgentToolContributor @Inject constructor(
    private val dataApi: DeskCubbyDataAPI,
    private val fileApi: FileAPI,
    private val appApi: AppAPI,
    private val webService: AgentWebService,
    private val settingsRepository: SettingsRepository,
    private val structuredWorkspaceRepository: StructuredWorkspaceRepository,
    private val structuredRecordsRepository: StructuredRecordsRepository,
    private val structuredStatisticsRepository: StructuredStatisticsRepository,
) : AgentToolContributor {
    override fun tools(): List<AgentTool> = listOf(
        listSourcesTool(),
        listEntriesTool(search = false),
        listEntriesTool(search = true),
        readEntryTool(multiple = false),
        readEntryTool(multiple = true),
        DataMutationTool("create_content", DeskCubbyMutationOperation.CREATE, dataApi),
        DataMutationTool("edit_content", DeskCubbyMutationOperation.UPDATE, dataApi),
        DataMutationTool("delete_content", DeskCubbyMutationOperation.DELETE, dataApi),
        listFilesTool(search = false),
        listFilesTool(search = true),
        readFileTool(),
        FileMutationTool("create_file", FileMutationOperation.CREATE, fileApi),
        FileMutationTool("modify_file", FileMutationOperation.UPDATE, fileApi),
        webSearchTool(),
        webReadTool(),
        appSettingsTool(),
        appStateTool(),
        AppSettingMutationTool(appApi),
        listStructuredFieldsTool(),
        getStructuredFieldValuesTool(),
        getStructuredFieldStatsTool(),
        listStructuredMetricsTool(),
        getStructuredMetricTool(),
    )

    private fun listSourcesTool() = FunctionalAgentTool(
        definition = readDefinition(
            "list_sources",
            "List only the DeskCubby data sources authorized for this run, with lightweight metadata.",
            emptySchema(),
        ),
        prepareBlock = { call, _ ->
            AgentArgs(call.arguments).only()
            AgentToolPreparation(call, "DeskCubby", "List authorized data sources", "")
        },
        executeBlock = { _, scope ->
            val sources = dataApi.sources(scope.allowedSources)
            AgentToolOutcome(
                jsonResult(
                    "sources" to JSONArray(sources.map { source ->
                        JSONObject()
                            .put("id", source.id)
                            .put("label", if (scope.english) source.labelEnglish else source.labelChinese)
                            .put("description", if (scope.english) source.descriptionEnglish else source.descriptionChinese)
                            .put("entryCount", source.entryCount)
                            .put("categories", JSONArray(source.categories.take(MAX_METADATA_ITEMS)))
                            .put("earliestDate", source.earliestDateIso)
                            .put("latestDate", source.latestDateIso)
                            .put("mutable", source.mutable)
                    }),
                ),
                if (scope.english) "Listed ${sources.size} authorized sources" else "已列出 ${sources.size} 个已授权数据源",
                "DeskCubby",
            )
        },
    )

    private fun listEntriesTool(search: Boolean): AgentTool {
        val name = if (search) "search_entries" else "list_entries"
        val description = if (search) {
            "Search one authorized DeskCubby source using text and optional date/category filters. Returns bounded snippets and metadata."
        } else {
            "List entries in one authorized DeskCubby source using optional date/category filters and pagination. Does not return full content."
        }
        val schema = objectSchema(
            properties = linkedMapOf(
                "source" to stringProperty("Authorized source id"),
                "query" to stringProperty("Search text", MAX_QUERY_CHARS),
                "category" to stringProperty("Exact category filter", MAX_CATEGORY_CHARS),
                "start_date" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                "end_date" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                "offset" to integerProperty(0, MAX_OFFSET),
                "limit" to integerProperty(1, MAX_LIST_LIMIT),
            ),
            required = if (search) listOf("source", "query") else listOf("source"),
        )
        return FunctionalAgentTool(
            readDefinition(name, description, schema),
            prepareBlock = { call, scope ->
                val args = AgentArgs(call.arguments).only(
                    "source", "query", "category", "start_date", "end_date", "offset", "limit",
                )
                val source = args.string("source", MAX_ID_CHARS)
                scope.requireSource(source)
                val query = args.optionalString("query", MAX_QUERY_CHARS)
                if (search && query.isNullOrBlank()) invalidArguments("query is required")
                val start = args.optionalDate("start_date")
                val end = args.optionalDate("end_date")
                if (start != null && end != null && start > end) invalidArguments("date range is invalid")
                val normalized = JSONObject()
                    .put("source", source)
                    .put("query", query)
                    .put("category", args.optionalString("category", MAX_CATEGORY_CHARS))
                    .put("start", start?.toString())
                    .put("end", end?.toString())
                    .put("offset", args.int("offset", 0, MAX_OFFSET, 0))
                    .put("limit", args.int("limit", 1, MAX_LIST_LIMIT, DEFAULT_LIST_LIMIT))
                AgentToolPreparation(
                    call,
                    source,
                    if (search) "Search $source" else "List $source",
                    normalized.toString(),
                    executionToken = normalized.toString(),
                )
            },
            executeBlock = { preparation, scope ->
                val args = JSONObject(preparation.executionToken)
                val page = dataApi.list(
                    DeskCubbyDataQuery(
                        sourceId = args.getString("source"),
                        text = args.optNullableString("query"),
                        category = args.optNullableString("category"),
                        startDateIso = args.optNullableString("start"),
                        endDateIso = args.optNullableString("end"),
                        offset = args.getInt("offset"),
                        limit = args.getInt("limit"),
                    ),
                )
                val values = page.entries.map { it.toJson(includeContent = false, snippet = search) }
                AgentToolOutcome(
                    jsonResult(
                        "entries" to JSONArray(values),
                        "offset" to page.offset,
                        "limit" to page.limit,
                        "hasMore" to page.hasMore,
                    ),
                    if (scope.english) "Found ${values.size} entries" else "找到 ${values.size} 条记录",
                    preparation.target,
                )
            },
        )
    }

    private fun readEntryTool(multiple: Boolean): AgentTool {
        val name = if (multiple) "read_entries" else "read_entry"
        val schema = objectSchema(
            linkedMapOf(
                "source" to stringProperty("Authorized source id"),
                "entry_id" to stringProperty("Opaque entry id", MAX_ID_CHARS),
                "entry_ids" to arrayProperty(stringProperty("Opaque entry id", MAX_ID_CHARS), MAX_READ_ENTRIES),
                "content_offset" to integerProperty(0, MAX_CONTENT_OFFSET),
                "content_limit" to integerProperty(1, if (multiple) MAX_MULTI_CONTENT_CHARS else MAX_CONTENT_CHARS),
            ),
            if (multiple) listOf("source", "entry_ids") else listOf("source", "entry_id"),
        )
        return FunctionalAgentTool(
            readDefinition(
                name,
                if (multiple) {
                    "Read several explicitly selected entries from one authorized source. Results are content-windowed and bounded."
                } else {
                    "Read one entry from an authorized source. Use content_offset/content_limit to page through long content."
                },
                schema,
            ),
            prepareBlock = { call, scope ->
                val args = AgentArgs(call.arguments).only(
                    "source", "entry_id", "entry_ids", "content_offset", "content_limit",
                )
                val source = args.string("source", MAX_ID_CHARS)
                scope.requireSource(source)
                val ids = if (multiple) {
                    args.strings("entry_ids", MAX_READ_ENTRIES, MAX_ID_CHARS)
                } else {
                    listOf(args.string("entry_id", MAX_ID_CHARS))
                }
                if (ids.isEmpty()) invalidArguments("At least one entry id is required")
                val token = JSONObject()
                    .put("source", source)
                    .put("ids", JSONArray(ids.distinct()))
                    .put("offset", args.int("content_offset", 0, MAX_CONTENT_OFFSET, 0))
                    .put(
                        "limit",
                        args.int(
                            "content_limit",
                            1,
                            if (multiple) MAX_MULTI_CONTENT_CHARS else MAX_CONTENT_CHARS,
                            if (multiple) DEFAULT_MULTI_CONTENT_CHARS else DEFAULT_CONTENT_CHARS,
                        ),
                    )
                AgentToolPreparation(
                    call,
                    "$source/${ids.joinToString(",")}",
                    "Read ${ids.size} $source entr${if (ids.size == 1) "y" else "ies"}",
                    token.toString(),
                    executionToken = token.toString(),
                )
            },
            executeBlock = { preparation, scope ->
                val token = JSONObject(preparation.executionToken)
                val source = token.getString("source")
                val offset = token.getInt("offset")
                val limit = token.getInt("limit")
                val ids = token.getJSONArray("ids").strings()
                val entries = ids.map { id -> dataApi.read(source, id).toJson(offset, limit) }
                AgentToolOutcome(
                    jsonResult("entries" to JSONArray(entries)),
                    if (scope.english) "Read ${entries.size} entries" else "已读取 ${entries.size} 条记录",
                    preparation.target,
                )
            },
        )
    }

    private fun listFilesTool(search: Boolean): AgentTool {
        val name = if (search) "search_files" else "list_files"
        return FunctionalAgentTool(
            readDefinition(
                name,
                if (search) {
                    "Search file names and text within one authorized DeskCubby SAF root."
                } else {
                    "List files and folders within one authorized DeskCubby SAF root with pagination."
                },
                objectSchema(
                    linkedMapOf(
                        "root" to stringProperty("Authorized SAF root: diary or notes"),
                        "folder_id" to stringProperty("Opaque notes folder id", MAX_ID_CHARS),
                        "query" to stringProperty("Search text", MAX_QUERY_CHARS),
                        "offset" to integerProperty(0, MAX_OFFSET),
                        "limit" to integerProperty(1, MAX_LIST_LIMIT),
                    ),
                    if (search) listOf("root", "query") else listOf("root"),
                ),
            ),
            prepareBlock = { call, scope ->
                val args = AgentArgs(call.arguments).only("root", "folder_id", "query", "offset", "limit")
                val root = args.string("root", MAX_ID_CHARS)
                scope.requireSource(root)
                val query = args.optionalString("query", MAX_QUERY_CHARS)
                if (search && query.isNullOrBlank()) invalidArguments("query is required")
                val token = JSONObject()
                    .put("root", root)
                    .put("folder", args.optionalString("folder_id", MAX_ID_CHARS))
                    .put("query", query)
                    .put("offset", args.int("offset", 0, MAX_OFFSET, 0))
                    .put("limit", args.int("limit", 1, MAX_LIST_LIMIT, DEFAULT_LIST_LIMIT))
                AgentToolPreparation(call, root, if (search) "Search files" else "List files", token.toString(), executionToken = token.toString())
            },
            executeBlock = { preparation, scope ->
                val token = JSONObject(preparation.executionToken)
                val page = if (search) {
                    fileApi.search(
                        FileSearchQuery(
                            token.getString("root"),
                            token.getString("query"),
                            token.getInt("offset"),
                            token.getInt("limit"),
                        ),
                    )
                } else {
                    fileApi.list(
                        FileQuery(
                            token.getString("root"),
                            token.optNullableString("folder"),
                            token.getInt("offset"),
                            token.getInt("limit"),
                        ),
                    )
                }
                AgentToolOutcome(
                    jsonResult(
                        "files" to JSONArray(page.entries.map(FileEntry::toJson)),
                        "offset" to page.offset,
                        "limit" to page.limit,
                        "hasMore" to page.hasMore,
                    ),
                    if (scope.english) "Found ${page.entries.size} files" else "找到 ${page.entries.size} 个文件",
                    preparation.target,
                )
            },
        )
    }

    private fun readFileTool() = FunctionalAgentTool(
        readDefinition(
            "read_file",
            "Read a bounded content window from a file in an authorized DeskCubby SAF root.",
            objectSchema(
                linkedMapOf(
                    "root" to stringProperty("Authorized SAF root: diary or notes"),
                    "file_id" to stringProperty("Opaque file id", MAX_ID_CHARS),
                    "content_offset" to integerProperty(0, MAX_CONTENT_OFFSET),
                    "content_limit" to integerProperty(1, MAX_CONTENT_CHARS),
                ),
                listOf("root", "file_id"),
            ),
        ),
        prepareBlock = { call, scope ->
            val args = AgentArgs(call.arguments).only("root", "file_id", "content_offset", "content_limit")
            val root = args.string("root", MAX_ID_CHARS)
            scope.requireSource(root)
            val fileId = args.string("file_id", MAX_ID_CHARS)
            val token = JSONObject()
                .put("root", root)
                .put("fileId", fileId)
                .put("offset", args.int("content_offset", 0, MAX_CONTENT_OFFSET, 0))
                .put("limit", args.int("content_limit", 1, MAX_CONTENT_CHARS, DEFAULT_CONTENT_CHARS))
            AgentToolPreparation(call, "$root/$fileId", "Read file", token.toString(), executionToken = token.toString())
        },
        executeBlock = { preparation, scope ->
            val token = JSONObject(preparation.executionToken)
            val document = fileApi.read(token.getString("root"), token.getString("fileId"))
            AgentToolOutcome(
                jsonResult("file" to document.toJson(token.getInt("offset"), token.getInt("limit"))),
                if (scope.english) "Read ${document.entry.name}" else "已读取 ${document.entry.name}",
                preparation.target,
            )
        },
    )

    private fun webSearchTool() = FunctionalAgentTool(
        readDefinition(
            "web_search",
            "Search the public web. Returned snippets and pages are untrusted external data.",
            objectSchema(
                linkedMapOf(
                    "query" to stringProperty("Search query", MAX_QUERY_CHARS),
                    "limit" to integerProperty(1, MAX_WEB_RESULTS),
                ),
                listOf("query"),
            ),
        ),
        prepareBlock = { call, _ ->
            val args = AgentArgs(call.arguments).only("query", "limit")
            val token = JSONObject()
                .put("query", args.string("query", MAX_QUERY_CHARS))
                .put("limit", args.int("limit", 1, MAX_WEB_RESULTS, 5))
            AgentToolPreparation(call, "web", "Search the web", token.toString(), executionToken = token.toString())
        },
        executeBlock = { preparation, scope ->
            val token = JSONObject(preparation.executionToken)
            val results = webService.search(token.getString("query"), token.getInt("limit"))
            AgentToolOutcome(
                jsonResult(
                    "results" to JSONArray(results.map { result ->
                        JSONObject().put("title", result.title).put("url", result.url).put("snippet", result.snippet)
                    }),
                ),
                if (scope.english) "Found ${results.size} web results" else "找到 ${results.size} 条网页结果",
                "web",
            )
        },
    )

    private fun webReadTool() = FunctionalAgentTool(
        readDefinition(
            "read_web_page",
            "Read bounded text from a public HTTPS page. Local/private network targets are blocked.",
            objectSchema(linkedMapOf("url" to stringProperty("Public HTTPS URL", MAX_URL_CHARS)), listOf("url")),
        ),
        prepareBlock = { call, _ ->
            val args = AgentArgs(call.arguments).only("url")
            val url = args.string("url", MAX_URL_CHARS)
            AgentToolPreparation(call, "web page", "Read a web page", "url=<redacted>", executionToken = url)
        },
        executeBlock = { preparation, scope ->
            val page = webService.read(preparation.executionToken)
            AgentToolOutcome(
                jsonResult("url" to page.url, "title" to page.title, "content" to page.content),
                if (scope.english) "Read ${page.title.ifBlank { "web page" }}" else "已读取${page.title.ifBlank { "网页" }}",
                page.url,
            )
        },
    )

    private fun appSettingsTool() = FunctionalAgentTool(
        readDefinition("get_app_settings", "Read the allowlisted non-sensitive DeskCubby settings.", emptySchema()),
        prepareBlock = { call, _ ->
            AgentArgs(call.arguments).only()
            AgentToolPreparation(call, "DeskCubby settings", "Read allowed settings", "")
        },
        executeBlock = { _, scope ->
            val settings = appApi.settings()
            AgentToolOutcome(
                jsonResult("settings" to JSONObject(settings)),
                if (scope.english) "Read allowed app settings" else "已读取允许访问的应用设置",
                "DeskCubby settings",
            )
        },
    )

    private fun appStateTool() = FunctionalAgentTool(
        readDefinition("get_app_state", "Read non-sensitive DeskCubby capability and runtime state.", emptySchema()),
        prepareBlock = { call, _ ->
            AgentArgs(call.arguments).only()
            AgentToolPreparation(call, "DeskCubby", "Read app state", "")
        },
        executeBlock = { _, scope ->
            AgentToolOutcome(
                jsonResult("state" to JSONObject(appApi.state())),
                if (scope.english) "Read app state" else "已读取应用状态",
                "DeskCubby",
            )
        },
    )

    private fun listStructuredFieldsTool() = FunctionalAgentTool(
        readDefinition(
            "list_structured_fields",
            "List the structured record field definitions of the DeskCubby workspace, with type, source, unit, and options.",
            emptySchema(),
        ),
        prepareBlock = { call, scope ->
            AgentArgs(call.arguments).only()
            scope.requireSource("statistics")
            AgentToolPreparation(call, "structured records", "List structured fields", "")
        },
        executeBlock = { _, scope ->
            val settings = settingsRepository.settings.first()
            val fields = structuredWorkspaceRepository.loadFields(settings)
            AgentToolOutcome(
                jsonResult(
                    "fields" to JSONArray(fields.map { field ->
                        JSONObject()
                            .put("id", field.id)
                            .put("name", field.name)
                            .put("type", field.type.wireValue)
                            .put("source", field.source.wireValue)
                            .put("unit", field.unit)
                            .put("options", JSONArray(field.options))
                            .put("archived", field.archived)
                    }),
                ),
                if (scope.english) "List structured record fields" else "列出结构化记录字段",
                "structured records",
            )
        },
    )

    private fun getStructuredFieldValuesTool() = FunctionalAgentTool(
        readDefinition(
            "get_structured_field_values",
            "Read the recorded values for one structured field, one value per journal day.",
            objectSchema(
                linkedMapOf(
                    "fieldId" to stringProperty("Structured field id"),
                    "start" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                    "end" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                ),
                listOf("fieldId"),
            ),
        ),
        prepareBlock = { call, scope ->
            val args = AgentArgs(call.arguments).only("fieldId", "start", "end")
            scope.requireSource("statistics")
            val token = JSONObject()
                .put("fieldId", args.string("fieldId", MAX_ID_CHARS))
                .put("start", args.optionalString("start", 10))
                .put("end", args.optionalString("end", 10))
            AgentToolPreparation(call, "structured records", "Read field values", token.toString(), executionToken = token.toString())
        },
        executeBlock = { preparation, scope ->
            val args = JSONObject(preparation.executionToken)
            val start = args.optNullableString("start") ?: "0001-01-01"
            val end = args.optNullableString("end") ?: "9999-12-31"
            if (start > end) invalidArguments("date range is invalid")
            val occurrences = structuredRecordsRepository.occurrencesForField(
                args.getString("fieldId"),
                start,
                end,
            )
            AgentToolOutcome(
                jsonResult(
                    "values" to JSONArray(occurrences.map { occurrence ->
                        JSONObject()
                            .put("journalDay", occurrence.journalDay)
                            .put("value", occurrence.rawValue)
                    }),
                ),
                if (scope.english) "Read structured record values" else "读取结构化记录值",
                preparation.target,
            )
        },
    )

    private fun getStructuredFieldStatsTool() = FunctionalAgentTool(
        readDefinition(
            "get_structured_field_stats",
            "Compute automatic statistics for one structured field across a date range: count, latest, and a per-day series or category histogram.",
            objectSchema(
                linkedMapOf(
                    "fieldId" to stringProperty("Structured field id"),
                    "start" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                    "end" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                    "selector" to stringProperty("Aggregation selector: first, last, min, max, sum, average, count (default last)"),
                ),
                listOf("fieldId"),
            ),
        ),
        prepareBlock = { call, scope ->
            val args = AgentArgs(call.arguments).only("fieldId", "start", "end", "selector")
            scope.requireSource("statistics")
            val token = JSONObject()
                .put("fieldId", args.string("fieldId", MAX_ID_CHARS))
                .put("selector", args.optionalString("selector", 32) ?: "last")
                .put("start", args.optionalString("start", 10))
                .put("end", args.optionalString("end", 10))
            AgentToolPreparation(call, "structured records", "Get field stats", token.toString(), executionToken = token.toString())
        },
        executeBlock = { preparation, scope ->
            val args = JSONObject(preparation.executionToken)
            val fieldId = args.getString("fieldId")
            val settings = settingsRepository.settings.first()
            val field = structuredWorkspaceRepository.loadFields(settings).firstOrNull { it.id == fieldId }
                ?: invalidArguments("Unknown field id: $fieldId")
            val start = args.optNullableString("start") ?: "0001-01-01"
            val end = args.optNullableString("end") ?: "9999-12-31"
            if (start > end) invalidArguments("date range is invalid")
            val selector = FieldSelector.fromWire(args.optNullableString("selector")) ?: FieldSelector.LAST
            if (selector !in StructuredFieldNormalizer.allowedSelectors(field.type)) {
                invalidArguments("selector is not supported for this field type")
            }
            val stats = structuredStatisticsRepository.autoFieldStats(
                settings,
                field,
                start,
                end,
                selector,
            )
            AgentToolOutcome(
                jsonResult(
                    "fieldId" to fieldId,
                    "name" to field.name,
                    "count" to stats.count,
                    "latest" to stats.latest,
                    "series" to stats.series.takeIf { it.isNotEmpty() }?.let { series ->
                        JSONArray(series.map { point ->
                            JSONObject()
                                .put("journalDay", point.journalDay.toString())
                                .put("value", point.chartValue)
                                .put("display", point.display)
                        })
                    },
                    "categories" to stats.categoryCounts.takeIf { it.isNotEmpty() }?.let { categories ->
                        JSONArray(categories.map { JSONObject().put("category", it.category).put("count", it.count) })
                    },
                ),
                if (scope.english) "Statistics for field ${field.name}" else "统计字段 ${field.name}",
                preparation.target,
            )
        },
    )

    private fun listStructuredMetricsTool() = FunctionalAgentTool(
        readDefinition(
            "list_structured_metrics",
            "List the derived structured-record metrics of the DeskCubby workspace with id, name, and result type.",
            emptySchema(),
        ),
        prepareBlock = { call, scope ->
            AgentArgs(call.arguments).only()
            scope.requireSource("statistics")
            AgentToolPreparation(call, "structured records", "List structured metrics", "")
        },
        executeBlock = { _, scope ->
            val settings = settingsRepository.settings.first()
            val metrics = structuredWorkspaceRepository.loadMetrics(settings)
            AgentToolOutcome(
                jsonResult(
                    "metrics" to JSONArray(metrics.map { metric ->
                        JSONObject()
                            .put("id", metric.id)
                            .put("name", metric.name)
                            .put("resultType", metric.resultType.wireValue)
                    }),
                ),
                if (scope.english) "List structured record metrics" else "列出结构化记录指标",
                "structured records",
            )
        },
    )

    private fun getStructuredMetricTool() = FunctionalAgentTool(
        readDefinition(
            "get_structured_metric",
            "Evaluate one derived structured-record metric across a date range, returning a per-day points series.",
            objectSchema(
                linkedMapOf(
                    "metricId" to stringProperty("Structured metric id"),
                    "start" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                    "end" to stringProperty("Inclusive ISO date YYYY-MM-DD"),
                ),
                listOf("metricId"),
            ),
        ),
        prepareBlock = { call, scope ->
            val args = AgentArgs(call.arguments).only("metricId", "start", "end")
            scope.requireSource("statistics")
            val token = JSONObject()
                .put("metricId", args.string("metricId", MAX_ID_CHARS))
                .put("start", args.optionalString("start", 10))
                .put("end", args.optionalString("end", 10))
            AgentToolPreparation(call, "structured records", "Calculate metric", token.toString(), executionToken = token.toString())
        },
        executeBlock = { preparation, scope ->
            val args = JSONObject(preparation.executionToken)
            val metricId = args.getString("metricId")
            val settings = settingsRepository.settings.first()
            val metric = structuredWorkspaceRepository.loadMetrics(settings).firstOrNull { it.id == metricId }
                ?: invalidArguments("Unknown metric id: $metricId")
            val today = LocalDate.now()
            val series = structuredStatisticsRepository.metricSeries(
                settings,
                metric,
                args.optNullableString("start") ?: today.minusDays(30).toString(),
                args.optNullableString("end") ?: today.toString(),
            )
            val points = series.mapNotNull { point ->
                point.display?.let { display ->
                    JSONObject().put("journalDay", point.journalDay.toString()).put("display", display)
                }
            }
            AgentToolOutcome(
                jsonResult(
                    "metricId" to metricId,
                    "name" to metric.name,
                    "resultType" to metric.resultType.wireValue,
                    "points" to JSONArray(points),
                ),
                if (scope.english) "Calculate metric ${metric.name}" else "计算指标 ${metric.name}",
                preparation.target,
            )
        },
    )
}

private class DataMutationTool(
    name: String,
    private val operation: DeskCubbyMutationOperation,
    private val api: DeskCubbyDataAPI,
) : AgentTool {
    override val definition = mutationDefinition(
        name,
        when (operation) {
            DeskCubbyMutationOperation.CREATE -> "Create exactly one item in an authorized DeskCubby data source."
            DeskCubbyMutationOperation.UPDATE -> "Edit exactly one item in an authorized DeskCubby data source."
            DeskCubbyMutationOperation.DELETE -> "Delete exactly one item from an authorized DeskCubby data source."
        },
        objectSchema(
            linkedMapOf(
                "source" to stringProperty("Authorized mutable source id"),
                "entry_id" to stringProperty("Opaque entry id", MAX_ID_CHARS),
                "title" to stringProperty("Title or name", MAX_TITLE_CHARS),
                "content" to stringProperty("Complete intended content", MAX_MUTATION_CONTENT_CHARS),
                "date" to stringProperty("ISO date YYYY-MM-DD"),
                "category" to stringProperty("Category", MAX_CATEGORY_CHARS),
            ),
            when (operation) {
                DeskCubbyMutationOperation.CREATE -> listOf("source")
                DeskCubbyMutationOperation.UPDATE -> listOf("source", "entry_id")
                DeskCubbyMutationOperation.DELETE -> listOf("source", "entry_id")
            },
        ),
    )

    override suspend fun prepare(call: AIToolCall, scope: AgentRunScope): AgentToolPreparation {
        val args = AgentArgs(call.arguments).only("source", "entry_id", "title", "content", "date", "category")
        val source = args.string("source", MAX_ID_CHARS)
        scope.requireSource(source)
        val entryId = args.optionalString("entry_id", MAX_ID_CHARS)
        if (operation != DeskCubbyMutationOperation.CREATE && entryId == null) invalidArguments("entry_id is required")
        val plan = safeApi {
            api.prepareMutation(
                DeskCubbyDataMutationRequest(
                    operation,
                    source,
                    entryId,
                    args.optionalString("title", MAX_TITLE_CHARS),
                    args.optionalString("content", MAX_MUTATION_CONTENT_CHARS),
                    args.optionalDate("date")?.toString(),
                    args.optionalString("category", MAX_CATEGORY_CHARS),
                ),
            )
        }
        return AgentToolPreparation(
            call,
            plan.target,
            plan.summary,
            "source=$source, entry=${entryId.orEmpty()}",
            before = plan.before?.reviewText().orEmpty(),
            after = plan.after?.reviewText().orEmpty(),
            executionToken = plan.planToken,
        )
    }

    override suspend fun execute(preparation: AgentToolPreparation, scope: AgentRunScope): AgentToolOutcome =
        api.commitMutation(preparation.executionToken).toOutcome()

    override suspend fun undo(undoToken: String): AgentToolOutcome = api.undoMutation(undoToken).toOutcome()
}

private class FileMutationTool(
    name: String,
    private val operation: FileMutationOperation,
    private val api: FileAPI,
) : AgentTool {
    override val definition = mutationDefinition(
        name,
        if (operation == FileMutationOperation.CREATE) {
            "Create exactly one text file inside an authorized DeskCubby SAF root."
        } else {
            "Replace the content of exactly one text file inside an authorized DeskCubby SAF root."
        },
        objectSchema(
            linkedMapOf(
                "root" to stringProperty("Authorized SAF root: diary or notes"),
                "file_id" to stringProperty("Opaque file id", MAX_ID_CHARS),
                "folder_id" to stringProperty("Opaque notes folder id", MAX_ID_CHARS),
                "name" to stringProperty("New file name", MAX_FILE_NAME_CHARS),
                "content" to stringProperty("Complete intended file content", MAX_MUTATION_CONTENT_CHARS),
            ),
            if (operation == FileMutationOperation.CREATE) listOf("root", "name", "content")
            else listOf("root", "file_id", "content"),
        ),
    )

    override suspend fun prepare(call: AIToolCall, scope: AgentRunScope): AgentToolPreparation {
        val args = AgentArgs(call.arguments).only("root", "file_id", "folder_id", "name", "content")
        val root = args.string("root", MAX_ID_CHARS)
        scope.requireSource(root)
        val plan = safeApi {
            api.prepareMutation(
                FileMutationRequest(
                    operation = operation,
                    rootId = root,
                    fileId = args.optionalString("file_id", MAX_ID_CHARS),
                    folderId = args.optionalString("folder_id", MAX_ID_CHARS),
                    name = args.optionalString("name", MAX_FILE_NAME_CHARS),
                    content = args.string("content", MAX_MUTATION_CONTENT_CHARS, allowEmpty = true),
                ),
            )
        }
        return AgentToolPreparation(
            call,
            plan.target,
            plan.summary,
            "root=$root",
            plan.before?.reviewText().orEmpty(),
            plan.after?.reviewText().orEmpty(),
            plan.planToken,
        )
    }

    override suspend fun execute(preparation: AgentToolPreparation, scope: AgentRunScope): AgentToolOutcome =
        api.commitMutation(preparation.executionToken).toOutcome()

    override suspend fun undo(undoToken: String): AgentToolOutcome = api.undoMutation(undoToken).toOutcome()
}

private class AppSettingMutationTool(
    private val api: AppAPI,
) : AgentTool {
    override val definition = mutationDefinition(
        "update_app_setting",
        "Modify exactly one allowlisted, non-sensitive DeskCubby setting. Secret, path, AI-model, and permission settings are unavailable.",
        objectSchema(
            linkedMapOf(
                "key" to stringProperty("Allowlisted key returned by get_app_settings", 100),
                "value" to stringProperty("New setting value", 500),
            ),
            listOf("key", "value"),
        ),
    )

    override suspend fun prepare(call: AIToolCall, scope: AgentRunScope): AgentToolPreparation {
        val args = AgentArgs(call.arguments).only("key", "value")
        val plan = safeApi {
            api.prepareSettingMutation(args.string("key", 100), args.string("value", 500, allowEmpty = true))
        }
        return AgentToolPreparation(
            call,
            plan.key,
            plan.summary,
            "key=${plan.key}",
            plan.before,
            plan.after,
            plan.planToken,
        )
    }

    override suspend fun execute(preparation: AgentToolPreparation, scope: AgentRunScope): AgentToolOutcome =
        api.commitSettingMutation(preparation.executionToken).toOutcome()

    override suspend fun undo(undoToken: String): AgentToolOutcome = api.undoSettingMutation(undoToken).toOutcome()
}

private class FunctionalAgentTool(
    override val definition: AgentToolDefinition,
    private val prepareBlock: suspend (AIToolCall, AgentRunScope) -> AgentToolPreparation,
    private val executeBlock: suspend (AgentToolPreparation, AgentRunScope) -> AgentToolOutcome,
) : AgentTool {
    override suspend fun prepare(call: AIToolCall, scope: AgentRunScope) = prepareBlock(call, scope)

    override suspend fun execute(preparation: AgentToolPreparation, scope: AgentRunScope) =
        executeBlock(preparation, scope)
}

internal class AgentArgs(private val values: Map<String, Any?>) {
    fun only(vararg allowed: String): AgentArgs {
        val unknown = values.keys - allowed.toSet()
        if (unknown.isNotEmpty()) invalidArguments("Unknown argument: ${unknown.first()}")
        return this
    }

    fun string(key: String, max: Int, allowEmpty: Boolean = false): String {
        val value = values[key] as? String ?: invalidArguments("$key must be a string")
        if ((!allowEmpty && value.isBlank()) || value.length > max) invalidArguments("$key is invalid")
        return value
    }

    fun optionalString(key: String, max: Int): String? {
        val raw = values[key] ?: return null
        val value = raw as? String ?: invalidArguments("$key must be a string")
        if (value.length > max) invalidArguments("$key is too long")
        return value.takeIf(String::isNotBlank)
    }

    fun int(key: String, min: Int, max: Int, default: Int): Int {
        val raw = values[key] ?: return default
        val value = when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Double -> raw.takeIf { it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
            else -> null
        } ?: invalidArguments("$key must be an integer")
        if (value !in min..max) invalidArguments("$key is outside the allowed range")
        return value
    }

    fun strings(key: String, maxItems: Int, maxChars: Int): List<String> {
        val values = values[key] as? List<*> ?: invalidArguments("$key must be an array")
        if (values.size > maxItems) invalidArguments("$key has too many items")
        return values.map { value ->
            (value as? String)?.takeIf { it.isNotBlank() && it.length <= maxChars }
                ?: invalidArguments("$key contains an invalid value")
        }
    }

    fun optionalDate(key: String): LocalDate? = optionalString(key, 10)?.let { value ->
        try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            invalidArguments("$key must use YYYY-MM-DD")
        }
    }
}

private fun readDefinition(name: String, description: String, schema: String) = AgentToolDefinition(
    name,
    description,
    schema,
    AgentToolClassification.READ_ONLY,
)

private fun mutationDefinition(name: String, description: String, schema: String) = AgentToolDefinition(
    name,
    description,
    schema,
    AgentToolClassification.MUTATION,
)

private fun emptySchema() = objectSchema(linkedMapOf(), emptyList())

private fun objectSchema(properties: LinkedHashMap<String, JSONObject>, required: List<String>): String =
    JSONObject()
        .put("type", "object")
        .put("properties", JSONObject(properties as Map<*, *>))
        .put("required", JSONArray(required))
        .put("additionalProperties", false)
        .toString()

private fun stringProperty(description: String, maxLength: Int? = null) = JSONObject()
    .put("type", "string")
    .put("description", description)
    .also { if (maxLength != null) it.put("maxLength", maxLength) }

private fun integerProperty(minimum: Int, maximum: Int) = JSONObject()
    .put("type", "integer")
    .put("minimum", minimum)
    .put("maximum", maximum)

private fun arrayProperty(items: JSONObject, maxItems: Int) = JSONObject()
    .put("type", "array")
    .put("items", items)
    .put("maxItems", maxItems)

private fun jsonResult(vararg values: Pair<String, Any?>): String = JSONObject()
    .put("ok", true)
    .put("untrusted", true)
    .also { result -> values.forEach { (key, value) -> result.put(key, value) } }
    .toString()

private fun DeskCubbyDataEntry.toJson(includeContent: Boolean, snippet: Boolean = false): JSONObject =
    JSONObject()
        .put("source", sourceId)
        .put("entryId", entryId)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("date", dateIso)
        .put("category", category)
        .put("createdAt", createdAtMillis)
        .put("updatedAt", updatedAtMillis)
        .put("metadata", JSONObject(metadata))
        .also { value ->
            if (includeContent) value.put("content", content)
            if (snippet) value.put("snippet", content.replace(Regex("\\s+"), " ").take(MAX_SNIPPET_CHARS))
        }

private fun DeskCubbyDataEntry.toJson(offset: Int, limit: Int): JSONObject {
    val safeOffset = offset.coerceAtMost(content.length)
    val end = (safeOffset + limit).coerceAtMost(content.length)
    return toJson(includeContent = false)
        .put("content", content.substring(safeOffset, end))
        .put("contentOffset", safeOffset)
        .put("contentLength", content.length)
        .put("hasMoreContent", end < content.length)
}

private fun FileEntry.toJson() = JSONObject()
    .put("root", rootId)
    .put("fileId", fileId)
    .put("parentId", parentId)
    .put("parentPath", parentRelativePath)
    .put("name", name)
    .put("isDirectory", isDirectory)
    .put("size", size)
    .put("lastModified", lastModifiedMillis)

private fun FileDocument.toJson(offset: Int, limit: Int): JSONObject {
    val safeOffset = offset.coerceAtMost(content.length)
    val end = (safeOffset + limit).coerceAtMost(content.length)
    return entry.toJson()
        .put("content", content.substring(safeOffset, end))
        .put("contentOffset", safeOffset)
        .put("contentLength", content.length)
        .put("hasMoreContent", end < content.length)
}

private fun DeskCubbyDataEntry.reviewText(): String = buildString {
    if (title.isNotBlank()) append(title).append('\n')
    if (!dateIso.isNullOrBlank()) append("Date: ").append(dateIso).append('\n')
    if (!category.isNullOrBlank()) append("Category: ").append(category).append('\n')
    append(content)
}.take(MAX_REVIEW_CHARS)

private fun FileDocument.reviewText(): String = content.take(MAX_REVIEW_CHARS)

private fun DeskCubbyMutationResult.toOutcome() = AgentToolOutcome(
    content = jsonResult(
        "operation" to operation.name,
        "target" to target,
        "summary" to summary,
        "entry" to after?.toJson(includeContent = false),
    ),
    summary = summary,
    target = target,
    before = before?.reviewText().orEmpty(),
    after = after?.reviewText().orEmpty(),
    undoToken = undoToken,
)

private fun FileMutationResult.toOutcome() = AgentToolOutcome(
    content = jsonResult(
        "operation" to operation.name,
        "target" to target,
        "summary" to summary,
        "file" to after?.entry?.toJson(),
    ),
    summary = summary,
    target = target,
    before = before?.reviewText().orEmpty(),
    after = after?.reviewText().orEmpty(),
    undoToken = undoToken,
)

private fun AppSettingMutationResult.toOutcome() = AgentToolOutcome(
    content = jsonResult("key" to key, "before" to before, "after" to after, "summary" to summary),
    summary = summary,
    target = key,
    before = before,
    after = after,
    undoToken = undoToken,
)

private suspend fun <T> safeApi(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: PluginApiException) {
    throw AgentToolException(error.code, error.message ?: "DeskCubby operation failed.", error)
} catch (error: IllegalArgumentException) {
    throw AgentToolException("INVALID_ARGUMENTS", error.message ?: "Tool arguments are invalid.", error)
} catch (error: IllegalStateException) {
    throw AgentToolException("DATA_CONFLICT", error.message ?: "DeskCubby data changed.", error)
}

private fun invalidArguments(message: String): Nothing =
    throw AgentToolException("INVALID_ARGUMENTS", message)

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

private const val MAX_ID_CHARS = 2_048
private const val MAX_TITLE_CHARS = 2_000
private const val MAX_CATEGORY_CHARS = 500
private const val MAX_QUERY_CHARS = 500
private const val MAX_URL_CHARS = 4_096
private const val MAX_FILE_NAME_CHARS = 240
private const val MAX_OFFSET = 1_000_000
private const val MAX_LIST_LIMIT = 100
private const val DEFAULT_LIST_LIMIT = 20
private const val MAX_READ_ENTRIES = 10
private const val MAX_METADATA_ITEMS = 100
private const val MAX_CONTENT_OFFSET = 10_000_000
private const val MAX_CONTENT_CHARS = 80_000
private const val DEFAULT_CONTENT_CHARS = 30_000
private const val MAX_MULTI_CONTENT_CHARS = 20_000
private const val DEFAULT_MULTI_CONTENT_CHARS = 10_000
private const val MAX_MUTATION_CONTENT_CHARS = 256 * 1024
private const val MAX_REVIEW_CHARS = 256 * 1024
private const val MAX_SNIPPET_CHARS = 600
private const val MAX_WEB_RESULTS = 10
