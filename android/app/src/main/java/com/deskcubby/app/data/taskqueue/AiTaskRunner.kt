package com.deskcubby.app.data.taskqueue

import android.net.Uri
import com.deskcubby.app.agent.AgentConversationMessage
import com.deskcubby.app.agent.AgentConversationRole
import com.deskcubby.app.agent.AgentExecutionUpdate
import com.deskcubby.app.agent.AgentRunRequest
import com.deskcubby.app.agent.AgentRuntime
import com.deskcubby.app.data.local.AiTaskDao
import com.deskcubby.app.data.local.AiTaskQueueEntity
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.repository.AiAttachmentKind
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.CalorieEstimationRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.MAX_MEAL_NOTE_CHARS
import com.deskcubby.app.data.repository.MealDayDetails
import com.deskcubby.app.data.repository.MealEnergyEstimate
import com.deskcubby.app.data.repository.MealImageRecognition
import com.deskcubby.plugin.api.core.api.AIImage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes one durable [AiTaskQueueEntity] on the worker. This is the only place that drives the
 * LLM/agent stack from a background context; the rest of the app only enqueues and observes.
 */
@Singleton
class AiTaskRunner @Inject constructor(
    database: AppDatabase,
    private val chatRepository: AiChatRepository,
    private val calorieRepository: CalorieEstimationRepository,
    private val diaryRepository: DiaryFileRepository,
    private val agentRuntime: AgentRuntime,
) {
    private val dao: AiTaskDao = database.aiTaskDao()
    private val claimMutex = Mutex()

    /** Runs queued work until the queue drains or [shouldContinue] returns false. */
    suspend fun drain(shouldContinue: () -> Boolean) {
        while (shouldContinue()) {
            val claimed = claimMutex.withLock { claimNext() ?: return }
            executeSafely(claimed)
        }
    }

    private suspend fun claimNext(): AiTaskQueueEntity? {
        val updated = dao.claimOldest(
            queued = AiTaskStateEntity.QUEUED,
            running = AiTaskStateEntity.RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        return if (updated > 0) {
            dao.peekOldest(AiTaskStateEntity.RUNNING)
        } else {
            null
        }
    }

    private suspend fun executeSafely(task: AiTaskQueueEntity) {
        try {
            when (task.type) {
                com.deskcubby.app.data.local.AiTaskTypeEntity.CALORIE_DAY ->
                    executeCalorieDay(task, CalorieDayTaskPayload.decode(task.payloadJson))
                com.deskcubby.app.data.local.AiTaskTypeEntity.CALORIE_SINGLE ->
                    executeCalorieSingle(task, CalorieSingleTaskPayload.decode(task.payloadJson))
                com.deskcubby.app.data.local.AiTaskTypeEntity.AGENT_RUN ->
                    executeAgentRun(task, AgentRunTaskPayload.decode(task.payloadJson))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.markFailed(
                id = task.id,
                failed = AiTaskStateEntity.FAILED,
                errorSummary = error.message.orEmpty().take(500),
                errorFailure = failureCode(error),
                attemptCount = task.attemptCount + 1,
                completedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun executeCalorieDay(task: AiTaskQueueEntity, payload: CalorieDayTaskPayload) {
        val settings = payload.settings
        require(settings.diaryTreeUri != null && settings.mediaTreeUri != null) {
            "请先选择日记和媒体目录"
        }
        dao.setProgress(
            task.id,
            calorieProgress(progressStage = "IMAGE_RECOGNITION", payload = payload),
        )
        val recognitions = withContext(Dispatchers.IO) {
            payload.photos.map { photo ->
                RecognizedCaloriePhoto(
                    fileName = photo.fileName,
                    recognition = calorieRepository.recognizeImage(
                        imageUri = photo.uri,
                        settings = settings,
                    ),
                )
            }
        }
        val calculationNote = payload.noteOverride
            ?.trim()
            ?.take(MAX_MEAL_NOTE_CHARS)
            ?.takeIf(String::isNotEmpty)
            ?: payload.fallbackNote
        dao.setProgress(
            task.id,
            calorieProgress(
                progressStage = "TEXT_ESTIMATION",
                payload = payload,
                completedPhotoCount = payload.photos.size,
            ),
        )
        val estimateList = withContext(Dispatchers.IO) {
            calorieRepository.estimateRecognizedDay(
                recognitions = recognitions.map(RecognizedCaloriePhoto::recognition),
                settings = settings,
                note = calculationNote,
            )
        }
        val estimates = linkedMapOf<String, MealEnergyEstimate>().apply {
            recognitions.zip(estimateList).forEach { (recognized, estimate) ->
                put(recognized.fileName, estimate)
            }
        }
        val detailsByDate = if (payload.force) {
            mapOf(
                payload.dateIso to MealDayDetails(
                    totalEnergyKjOverride = if (payload.clearManualTotalOnSave) {
                        null
                    } else {
                        payload.existingTotalEnergyKjOverride
                    },
                    note = payload.noteOverride ?: payload.fallbackNote,
                ),
            )
        } else {
            emptyMap()
        }
        dao.setProgress(
            task.id,
            calorieProgress(progressStage = "SAVING", payload = payload),
        )
        withContext(Dispatchers.IO) {
            diaryRepository.setMealEnergyResults(estimates, detailsByDate, settings)
        }
        dao.markSucceeded(
            id = task.id,
            succeeded = AiTaskStateEntity.SUCCEEDED,
            resultJson = encodeCalorieResult(estimates),
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun executeCalorieSingle(task: AiTaskQueueEntity, payload: CalorieSingleTaskPayload) {
        val settings = payload.settings
        require(settings.mediaTreeUri != null) { "请先选择媒体目录" }
        dao.setProgress(
            task.id,
            calorieProgress(progressStage = "IMAGE_RECOGNITION", payload = payload),
        )
        val recognition = withContext(Dispatchers.IO) {
            calorieRepository.recognizeImage(
                imageUri = payload.uri,
                settings = settings,
            )
        }
        dao.setProgress(
            task.id,
            calorieProgress(progressStage = "TEXT_ESTIMATION", payload = payload),
        )
        val estimate = withContext(Dispatchers.IO) {
            calorieRepository.estimateRecognizedDay(
                recognitions = listOf(recognition),
                settings = settings,
            ).single()
        }
        withContext(Dispatchers.IO) {
            diaryRepository.setMealPhotoEstimate(payload.fileName, estimate, settings)
        }
        dao.markSucceeded(
            id = task.id,
            succeeded = AiTaskStateEntity.SUCCEEDED,
            resultJson = encodeCalorieResult(mapOf(payload.fileName to estimate)),
            completedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun executeAgentRun(task: AiTaskQueueEntity, payload: AgentRunTaskPayload) {
        val conversationId = payload.conversationId
        chatRepository.getConversation(conversationId)
            ?: throw IllegalStateException("对话已不存在")
        val messages = chatRepository.getMessages(conversationId)
        val request = AgentRunRequest(
            runId = payload.runId,
            conversationId = conversationId,
            conversationTitle = payload.conversationTitle,
            userRequest = payload.userRequest,
            modelConfigId = payload.modelConfigId,
            customModelInstructions = payload.customModelInstructions,
            allowedSources = payload.allowedSources,
            permissionMode = payload.permissionMode,
            english = payload.english,
            messages = buildAgentConversation(messages),
        )
        val answer = agentRuntime.run(request, ::recordExecutionUpdate)
        chatRepository.appendMessage(
            conversationId = conversationId,
            role = AiChatRole.ASSISTANT,
            content = answer.content,
            reasoning = "",
        )
        dao.markSucceeded(
            id = task.id,
            succeeded = AiTaskStateEntity.SUCCEEDED,
            resultJson = encodeAgentResult(answer.usage),
            completedAt = System.currentTimeMillis(),
        )
    }

    private fun recordExecutionUpdate(update: AgentExecutionUpdate) {
        // Agent-run progress is already durable in the AgentReviewStore Room tables, which the
        // chat/review screens observe directly. No in-memory passthrough is needed here.
    }

    private fun buildAgentConversation(messages: List<AiChatMessage>): List<AgentConversationMessage> {
        var remaining = MAX_HISTORY_CONTENT_CHARS
        val reversed = mutableListOf<AgentConversationMessage>()
        messages.asReversed().take(MAX_HISTORY_MESSAGES).forEach { message ->
            if (remaining <= 0) return@forEach
            val documentContext = message.attachments
                .asSequence()
                .filter { it.kind == AiAttachmentKind.DOCUMENT && !it.extractedText.isNullOrBlank() }
                .joinToString("\n\n") { attachment ->
                    "<untrusted_attachment name=\"${attachment.displayName.xmlEscape()}\" " +
                        "mime=\"${attachment.mimeType.xmlEscape()}\">\n" +
                        attachment.extractedText.orEmpty() + "\n</untrusted_attachment>"
                }
            val syncedImageNotice = message.attachments.any {
                it.kind == AiAttachmentKind.IMAGE && it.uri.isBlank()
            }
            val combined = buildString {
                append(message.content)
                if (documentContext.isNotBlank()) append("\n\n").append(documentContext)
                if (syncedImageNotice) {
                    append("\n\n[An image attachment exists in synced history but its device-local URI is unavailable.]")
                }
            }.takeLast(remaining)
            remaining -= combined.length
            val images = buildList {
                message.image?.takeIf { it.uri.isNotBlank() }?.let { add(AIImage(it.uri, it.mimeType)) }
                message.attachments.asSequence()
                    .filter { it.kind == AiAttachmentKind.IMAGE && it.uri.isNotBlank() }
                    .map { AIImage(it.uri, it.mimeType) }
                    .filterNot { candidate -> any { it.contentUri == candidate.contentUri } }
                    .forEach(::add)
            }
            reversed += AgentConversationMessage(
                role = when (message.role) {
                    AiChatRole.USER -> AgentConversationRole.USER
                    AiChatRole.ASSISTANT -> AgentConversationRole.ASSISTANT
                    AiChatRole.CONTEXT -> AgentConversationRole.UNTRUSTED_CONTEXT
                },
                content = combined,
                images = images,
            )
        }
        return reversed.asReversed()
    }

    private fun String.xmlEscape(): String = replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .take(500)

    private fun failureCode(error: Throwable): String = when (error) {
        is AiChatException -> error.failure.name
        is com.deskcubby.app.agent.AgentRuntimeException -> error.code
        else -> "UNKNOWN"
    }

    private fun encodeCalorieResult(estimates: Map<String, MealEnergyEstimate>): String =
        JSONObject().apply {
            put(
                "estimates",
                JSONArray().apply {
                    estimates.forEach { (fileName, estimate) ->
                        put(
                            JSONObject()
                                .put("fileName", fileName)
                                .put("energyKj", estimate.energyKj),
                        )
                    }
                },
            )
        }.toString()

    private fun encodeAgentResult(usage: com.deskcubby.app.agent.AgentRunUsage): String =
        JSONObject().apply {
            put(
                "usage",
                JSONObject()
                    .put("modelCalls", usage.modelCallCount)
                    .put("inputTokens", usage.inputTokens)
                    .put("outputTokens", usage.outputTokens)
                    .put("totalTokens", usage.totalTokens),
            )
        }.toString()

    private fun calorieProgress(
        progressStage: String,
        payload: CalorieDayTaskPayload,
        completedPhotoCount: Int = 0,
        activePhotoCount: Int = 0,
    ): String = CalorieTaskProgress(
        stage = progressStage,
        selectedPhotoCount = payload.photos.size,
        dayPhotoCount = payload.dayPhotoCount,
        force = payload.force,
        completedPhotoCount = completedPhotoCount,
        activePhotoCount = activePhotoCount,
    ).encode()

    private fun calorieProgress(
        progressStage: String,
        payload: CalorieSingleTaskPayload,
    ): String = CalorieTaskProgress(
        stage = progressStage,
        selectedPhotoCount = 1,
        dayPhotoCount = 1,
        force = false,
    ).encode()

    private companion object {
        const val MAX_HISTORY_MESSAGES = 80
        const val MAX_HISTORY_CONTENT_CHARS = 1024 * 1024
    }

    private data class RecognizedCaloriePhoto(
        val fileName: String,
        val recognition: MealImageRecognition,
    )
}