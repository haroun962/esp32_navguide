package com.navwidget

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * NavBridgeService
 *
 * Reads the active Google Maps navigation UI and extracts:
 * direction, street name, distance, and ETA.
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

    // Holds a text string alongside its on-screen Y position
    private data class TextNode(val text: String, val top: Int)

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

    // Strip non-breaking spaces and non-ASCII before sending over BLE.
    // Google Maps uses U+00A0 between number and unit — the ESP32 renders
    // the raw high byte as a garbled glyph.
    private fun sanitize(s: String): String =
        s.replace('\u00A0', ' ').filter { it.code in 32..126 }

    // ── Window parser ──────────────────────────────────────────────────────
    private fun parseNavFromWindow(root: AccessibilityNodeInfo) {
        val nodes = mutableListOf<TextNode>()
        collectTextNodes(root, nodes)
        if (nodes.isEmpty()) return

        val texts = nodes.map { it.text }

        // ── Distance: take the TOPMOST standalone distance node.
        // Google Maps shows two standalone distance strings simultaneously:
        //   • next-turn distance in the maneuver banner (top of screen)
        //   • trip-total distance in the ETA sheet (bottom of screen)
        // Both fully match the anchored regex, so minByOrNull { it.top }
        // disambiguates by screen position rather than traversal order.
        val distanceRegex = Regex("""^(\d+\.?\d*)\s*(mi|ft|km|m)$""", RegexOption.IGNORE_CASE)

        val distanceCandidates = nodes.filter {
            distanceRegex.matches(it.text.replace('\u00A0', ' ').trim())
        }
        // Log all candidates so you can confirm the right one is picked
        distanceCandidates.forEach {
            Log.d(TAG, "distance candidate: '${it.text}' top=${it.top}")
        }

        val distance = distanceCandidates
            .minByOrNull { it.top }
            ?.text?.replace('\u00A0', ' ')?.trim()
            ?: lastDistance

        // ── ETA: looks like "2:45 PM" or "12 min" ──
        val etaRegex = Regex("""(\d{1,2}:\d{2}\s*(AM|PM)|\d+\s*min)""", RegexOption.IGNORE_CASE)
        val eta = texts.firstOrNull { etaRegex.containsMatchIn(it) }
            ?.let { etaRegex.find(it)?.value } ?: lastEta

        // ── Direction: inferred from maneuver image contentDescription ──
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

    // Bounds-aware text collector — captures each node's on-screen Y position
    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<TextNode>) {
        val r = Rect()
        node.getBoundsInScreen(r)
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty())                 out.add(TextNode(text, r.top))
        if (!desc.isNullOrEmpty() && desc != text) out.add(TextNode(desc, r.top))
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextNodes(it, out) }
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
