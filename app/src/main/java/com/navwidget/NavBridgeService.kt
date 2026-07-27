package com.navwidget

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NavBridgeService
 *
 * Reads the active Google Maps navigation notification / on-screen UI
 * and extracts: direction, street name, distance, and ETA.
 *
 * Supported direction strings sent to ESP32:
 *   NONE, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, U_TURN, ARRIVE
 */
class NavBridgeService : AccessibilityService() {

    companion object {
        private const val TAG      = "NavBridgeService"
        private const val MAPS_PKG = "com.google.android.apps.maps"

        var onNavUpdate: ((direction: String, street: String, distance: String, eta: String) -> Unit)? = null
    }

    private var lastDirection = ""
    private var lastStreet    = ""
    private var lastDistance  = ""
    private var lastEta       = ""

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            packageNames = arrayOf(MAPS_PKG)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.d(TAG, "NavBridgeService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != MAPS_PKG) return
        val root = rootInActiveWindow ?: return
        parseNavFromWindow(root)
    }

    override fun onInterrupt() {}

    // ── Sanitize: normalize NBSP and strip non-ASCII before sending over BLE.
    // Google Maps uses U+00A0 (non-breaking space) between number and unit —
    // the ESP32's drawString() renders the raw high byte as a garbled glyph.
    private fun sanitize(s: String): String =
        s.replace('\u00A0', ' ').filter { it.code in 32..126 }

    // ── Window parser ──────────────────────────────────────────────────────
    private fun parseNavFromWindow(root: AccessibilityNodeInfo) {
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        if (texts.isEmpty()) return

        // ── Distance: match only nodes whose ENTIRE content is a distance.
        // Google Maps shows both "distance to next turn" and a trip-summary
        // distance on screen simultaneously. Using ^...$ anchors ensures we
        // only match the standalone turn-distance node (e.g. "160 m"),
        // ignoring anything that merely contains a distance in a longer string.
        val distanceRegex = Regex("""^(\d+\.?\d*)\s*(mi|ft|km|m)$""", RegexOption.IGNORE_CASE)
        val distance = texts
            .map { it.replace('\u00A0', ' ').trim() }
            .firstOrNull { distanceRegex.matches(it) }
            ?: lastDistance

        // ── ETA: looks like "2:45 PM" or "12 min" ──
        val etaRegex = Regex("""(\d{1,2}:\d{2}\s*(AM|PM)|\d+\s*min)""", RegexOption.IGNORE_CASE)
        val eta = texts.firstOrNull { etaRegex.containsMatchIn(it) }
            ?.let { etaRegex.find(it)?.value } ?: lastEta

        // ── Direction: inferred from maneuver content-description on ImageViews ──
        val direction = inferDirection(root) ?: lastDirection

        // ── Street: longest non-numeric, non-distance, non-ETA text node ──
        val street = inferStreet(texts, distance) ?: lastStreet

        // Sanitize all fields before comparison and send
        val cleanDirection = sanitize(direction)
        val cleanStreet    = sanitize(street)
        val cleanDistance  = sanitize(distance)
        val cleanEta       = sanitize(eta)

        // Only push if something changed
        if (cleanDirection == lastDirection && cleanStreet == lastStreet &&
            cleanDistance  == lastDistance  && cleanEta   == lastEta) return

        lastDirection = cleanDirection
        lastStreet    = cleanStreet
        lastDistance  = cleanDistance
        lastEta       = cleanEta

        Log.d(TAG, "Nav update → dir=$cleanDirection | street=$cleanStreet | dist=$cleanDistance | eta=$cleanEta")
        onNavUpdate?.invoke(cleanDirection, cleanStreet, cleanDistance, cleanEta)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        if (!desc.isNullOrEmpty() && desc != text) out.add(desc)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out) }
        }
    }

    private fun inferDirection(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        collectContentDescriptions(root, candidates)

        for (cd in candidates) {
            val lower = cd.lowercase()
            return when {
                "arrive"       in lower || "destination" in lower -> "ARRIVE"
                "u-turn"       in lower || "u turn"      in lower -> "U_TURN"
                "slight left"  in lower                           -> "SLIGHT_LEFT"
                "slight right" in lower                           -> "SLIGHT_RIGHT"
                "turn left"    in lower || "left"        in lower -> "LEFT"
                "turn right"   in lower || "right"       in lower -> "RIGHT"
                "straight"     in lower || "continue"    in lower -> "STRAIGHT"
                else -> continue
            }
        }
        return null
    }

    private fun collectContentDescriptions(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val cd = node.contentDescription?.toString()?.trim()
        if (!cd.isNullOrEmpty()) out.add(cd)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectContentDescriptions(it, out) }
        }
    }

    private fun inferStreet(texts: List<String>, distance: String): String? {
        val skip = Regex("""^\d|mi$|ft$|km$|min$|AM$|PM$""", RegexOption.IGNORE_CASE)
        return texts
            .filter { it.length in 3..50 && !skip.containsMatchIn(it) && it != distance }
            .maxByOrNull { it.length }
    }
}
