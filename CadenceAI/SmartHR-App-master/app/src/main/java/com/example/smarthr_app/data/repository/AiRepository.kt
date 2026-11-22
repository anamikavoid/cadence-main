package com.example.smarthr_app.data.repository

import com.example.smarthr_app.data.local.DataStoreManager
import com.example.smarthr_app.data.model.AiChatRequest
import com.example.smarthr_app.data.model.AiChatResponse
import com.example.smarthr_app.data.model.AiConfirmRequest
import com.example.smarthr_app.data.model.AiHistoryResponse
import com.example.smarthr_app.data.remote.RetrofitInstance
import com.example.smarthr_app.utils.Resource
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.io.IOException
import java.util.TimeZone

/**
 * REPOSITORY = the one place that knows HOW to talk to the backend for the AI assistant.
 * The ViewModel asks this class for data and never touches Retrofit directly.
 *
 * Every call:
 *  1. reads the saved login token from DataStore,
 *  2. sends it as "Authorization: Bearer <token>" (this is how the server knows WHO is asking),
 *  3. turns the HTTP result into a [Resource]: Success(data) or Error(message the user can read).
 *
 * The Gemini API key is NOT here (and must never be): it lives only on the server.
 */
class AiRepository(private val dataStoreManager: DataStoreManager) {

    /** The phone's current time zone, e.g. "Asia/Kolkata". */
    private fun timeZoneId(): String = TimeZone.getDefault().id

    suspend fun sendMessage(message: String): Resource<AiChatResponse> =
        execute { token -> RetrofitInstance.api.aiChat(token, AiChatRequest(message, timeZoneId())) }

    suspend fun confirm(pendingActionId: String, confirm: Boolean): Resource<AiChatResponse> =
        execute { token -> RetrofitInstance.api.aiConfirm(token, AiConfirmRequest(pendingActionId, confirm, timeZoneId())) }

    suspend fun loadHistory(): Resource<AiHistoryResponse> =
        execute { token -> RetrofitInstance.api.aiHistory(token) }

    suspend fun clearConversation(): Resource<Unit> {
        val token = dataStoreManager.token.first()
        if (token.isNullOrBlank()) return Resource.Error("Please log in again.")
        return try {
            val response = RetrofitInstance.api.aiClearConversation("Bearer $token")
            // The server answers 204 No Content (no body), so success is judged by the status code only.
            if (response.isSuccessful) Resource.Success(Unit)
            else Resource.Error(errorMessage(response.code(), response.errorBody()?.string()))
        } catch (e: IOException) {
            Resource.Error(NETWORK_ERROR)
        } catch (e: Exception) {
            Resource.Error(GENERIC_ERROR)
        }
    }

    /** Shared plumbing: token -> call -> Success / Error. [block] receives the "Bearer ..." header value. */
    private suspend fun <T> execute(block: suspend (String) -> Response<T>): Resource<T> {
        val token = dataStoreManager.token.first()
        if (token.isNullOrBlank()) return Resource.Error("Please log in again.")
        return try {
            val response = block("Bearer $token")
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) Resource.Success(body) else Resource.Error("The server sent an empty answer. Please try again.")
            } else {
                Resource.Error(errorMessage(response.code(), response.errorBody()?.string()))
            }
        } catch (e: IOException) {
            // No internet, timeout, DNS problem...
            Resource.Error(NETWORK_ERROR)
        } catch (e: Exception) {
            Resource.Error(GENERIC_ERROR)
        }
    }

    /** The backend reports errors as {"error": "message"}; show that message, else a sensible fallback by status code. */
    private fun errorMessage(code: Int, rawBody: String?): String {
        val fromServer = try {
            Gson().fromJson(rawBody, ErrorBody::class.java)?.error
        } catch (e: Exception) {
            null
        }
        if (!fromServer.isNullOrBlank()) return fromServer
        return when (code) {
            401, 403 -> "Your session has expired. Please log in again."
            429 -> "You're sending messages too quickly. Please wait a moment."
            in 500..599 -> "The server had a problem. Please try again."
            else -> "Something went wrong (error $code)."
        }
    }

    private data class ErrorBody(val error: String?)

    private companion object {
        const val NETWORK_ERROR = "Can't reach the server. Check your internet connection and try again."
        const val GENERIC_ERROR = "Something went wrong. Please try again."
    }
}
