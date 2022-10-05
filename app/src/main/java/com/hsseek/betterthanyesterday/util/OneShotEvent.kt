package com.hsseek.betterthanyesterday.util

open class OneShotEvent<out T>(private val content: T) {
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