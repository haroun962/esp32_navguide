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
 * Google Maps keeps the current maneuver info in its notification while
 * navigating.  The accessibility service intercepts window content changes
 * from com.google.android.apps.maps and parses the relevant nodes.
 *
 * Supported direction strings sent to ESP32:
 *   NONE, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, U_TURN, ARRIVE
 */
class NavBridgeService : AccessibilityService() {

    companion object {
        private const val TAG        = "NavBridgeService"
        private const val MAPS_PKG   = "com.google.android.apps.maps"

        // Callback set by MainActivity to forward data
        var onNavUpdate: ((direction: String, street: String, distance: String, eta: String) -> Unit)? = null
    }

    // Debounce — only push if something actually changed
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

    // ── Window parser ──────────────────────────────────────────────────────
    private fun parseNavFromWindow(root: AccessibilityNodeInfo) {
        // Collect all visible text nodes
        val texts = mutableListOf<String>()
        collectTexts(root, texts)

        if (texts.isEmpty()) return

        // ── Distance: pick the SMALLEST distance-like value on screen.
        // Google Maps shows both "distance to next turn" and a trip-summary
        // distance simultaneously — the next-turn value is always the smaller
        // one, since it's a sub-segment of the full remaining route.
        val distanceRegex = Regex("""(\d+\.?\d*)\s*(mi|ft|km|m)\b""", RegexOption.IGNORE_CASE)

        fun toMeters(value: String, unit: String): Double = when (unit.lowercase()) {
            "mi" -> value.toDouble() * 1609.34
            "km" -> value.toDouble() * 1000.0
            "ft" -> value.toDouble() * 0.3048
            else -> value.toDouble() // "m"
        }

        val distance = texts
            .mapNotNull { distanceRegex.find(it) }
            .minByOrNull { toMeters(it.groupValues[1], it.groupValues[2]) }
            ?.value
            ?.replace('\u00A0', ' ')          // Google Maps uses NBSP between number and unit
            ?.filter { it.code in 32..126 }   // strip anything else non-ASCII, just in case
            ?: lastDistance

        // ── ETA: looks like "2:45 PM" or "12 min" ──
        val etaRegex = Regex("""(\d{1,2}:\d{2}\s*(AM|PM)|\d+\s*min)""", RegexOption.IGNORE_CASE)
        val eta = texts.firstOrNull { etaRegex.containsMatchIn(it) }
            ?.let { etaRegex.find(it)?.value } ?: lastEta

        // ── Direction: inferred from maneuver content-description on ImageViews ──
        val direction = inferDirection(root) ?: lastDirection

        // ── Street: the text node after the distance, or the longest non-numeric string ──
        val street = inferStreet(texts, distance) ?: lastStreet

        // Only update if changed
        if (direction == lastDirection && street == lastStreet &&
            distance == lastDistance  && eta == lastEta) return

        lastDirection = direction
        lastStreet    = street
        lastDistance  = distance
        lastEta       = eta

        Log.d(TAG, "Nav update → dir=$direction | street=$street | dist=$distance | eta=$eta")
        fun sanitize(s: String): String = s.replace('\u00A0', ' ').filter { it.code in 32..126 }

        val cleanDirection = sanitize(direction)
        val cleanStreet    = sanitize(street)
        val cleanDistance  = sanitize(distance)
        val cleanEta       = sanitize(eta)

        if (cleanDirection == lastDirection && cleanStreet == lastStreet &&
            cleanDistance == lastDistance  && cleanEta == lastEta) return

        lastDirection = cleanDirection
        lastStreet    = cleanStreet
        lastDistance  = cleanDistance
        lastEta       = cleanEta

        onNavUpdate?.invoke(cleanDirection, cleanStreet, cleanDistance, cleanEta)
        onNavUpdate?.invoke(direction, street, distance, eta)
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

    /**
     * Google Maps renders the maneuver arrow as an ImageView whose
     * contentDescription contains the maneuver type text.
     */
    private fun inferDirection(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        collectContentDescriptions(root, candidates)

        for (cd in candidates) {
            val lower = cd.lowercase()
            return when {
                "arrive"        in lower || "destination" in lower -> "ARRIVE"
                "u-turn"        in lower || "u turn"      in lower -> "U_TURN"
                "slight left"   in lower                           -> "SLIGHT_LEFT"
                "slight right"  in lower                           -> "SLIGHT_RIGHT"
                "turn left"     in lower || "left"        in lower -> "LEFT"
                "turn right"    in lower || "right"       in lower -> "RIGHT"
                "straight"      in lower || "continue"    in lower -> "STRAIGHT"
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
        // Street names are generally the longest non-numeric, non-ETA text node
        val skip = Regex("""^\d|mi$|ft$|km$|min$|AM$|PM$""", RegexOption.IGNORE_CASE)
        return texts
            .filter { it.length in 3..50 && !skip.containsMatchIn(it) && it != distance }
            .maxByOrNull { it.length }
    }
}
