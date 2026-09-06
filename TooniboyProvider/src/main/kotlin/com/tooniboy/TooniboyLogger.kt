package com.tooniboy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory logger for Tooniboy.
 * Keeps the last MAX_ENTRIES lines. Each entry is timestamped.
 * Settings screen reads getEntries() and lets the user copy all at once.
 */
object TooniboyLogger {

    private const val MAX_ENTRIES = 300
    private val entries = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun stamp() = fmt.format(Date())

    fun i(tag: String, msg: String) = append("[ INFO ][$tag] $msg")
    fun d(tag: String, msg: String) = append("[DEBUG ][$tag] $msg")
    fun w(tag: String, msg: String) = append("[ WARN ][$tag] $msg")
    fun e(tag: String, msg: String) = append("[ERROR ][$tag] $msg")

    /** Plain separator line to group log sections visually */
    fun section(title: String) = append("──── $title ────")

    private fun append(line: String) {
        val full = "${stamp()} $line"
        entries.add(full)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    fun getEntries(): List<String> = entries.toList()

    fun clear() = entries.clear()
}
