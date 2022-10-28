package com.hsseek.betterthanyesterday.util

abstract class OneShotEvent<out T>(private val content: T) {
    private var isHandled: Boolean = false

    fun getContentIfNotHandled(): T? {
        return if (isHandled) {
            null
        } else {
            isHandled = true
            content
        }
    }
}

class ToastEvent(messageId: Int): OneShotEvent<Int>(messageId)
class SnackBarEvent(snackBarContent: SnackBarContent): OneShotEvent<SnackBarContent>(snackBarContent)
data class SnackBarContent(val messageId: Int?, val actionId: Int? = null, val action: (() -> Unit) = {})