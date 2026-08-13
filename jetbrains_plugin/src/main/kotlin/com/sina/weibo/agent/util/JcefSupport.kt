// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.util

import com.intellij.ui.jcef.JBCefApp

/**
 * Safe wrapper around JCEF availability checks.
 *
 * Since IntelliJ 2026.2, JCEF ships in a separate bundled module and its classes
 * are only visible to plugins that declare a dependency on `com.intellij.modules.jcef`.
 * If that module is absent — or the runtime simply does not bundle JCEF — the
 * `com.intellij.ui.jcef.JBCefApp` class itself may be missing. Touching
 * `JBCefApp.isSupported()` directly then throws `NoClassDefFoundError` /
 * `ClassNotFoundException`, which must never abort plugin startup or the tool window.
 *
 * Always go through this helper instead of referencing `JBCefApp` directly, so a
 * missing JCEF degrades gracefully into "not supported" (and the existing
 * "JCEF not supported" placeholder UI is shown) rather than crashing.
 */
object JcefSupport {

    /**
     * `true` only when JCEF is both present on the classpath and supported by the
     * current runtime. Catches the linkage errors raised when the JCEF module is
     * unavailable and reports `false` instead.
     */
    fun isSupported(): Boolean {
        return try {
            JBCefApp.isSupported()
        } catch (e: Throwable) {
            // NoClassDefFoundError / ClassNotFoundException when JCEF is unavailable.
            false
        }
    }

    /**
     * `true` when the JBCefApp class is resolvable on the classpath, without
     * initializing JCEF. Use this to gate code paths that construct JCEF objects.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName("com.intellij.ui.jcef.JBCefApp") != null
        } catch (e: Throwable) {
            false
        }
    }
}
