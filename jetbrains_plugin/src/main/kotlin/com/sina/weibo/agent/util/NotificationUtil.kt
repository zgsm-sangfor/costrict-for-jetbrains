// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.util

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.awt.datatransfer.StringSelection

/**
 * Notification utility class
 * Used to encapsulate notification functionality for the plugin
 */
object NotificationUtil {

    private const val NOTIFICATION_GROUP_ID = "CoStrict"

    private val logger = Logger.getInstance(NotificationUtil::class.java)
    
    /**
     * Show error notification
     * @param title Notification title
     * @param content Notification content
     * @param project Project instance, if null the default project is used
     */
    fun showError(title: String, content: String, project: Project? = null) {
        showNotification(title, content, NotificationType.ERROR, project)
    }
    
    /**
     * Show warning notification
     * @param title Notification title
     * @param content Notification content
     * @param project Project instance, if null the default project is used
     */
    fun showWarning(title: String, content: String, project: Project? = null) {
        showNotification(title, content, NotificationType.WARNING, project)
    }
    
    /**
     * Show info notification
     * @param title Notification title
     * @param content Notification content
     * @param project Project instance, if null the default project is used
     */
    fun showInfo(title: String, content: String, project: Project? = null) {
        showNotification(title, content, NotificationType.INFORMATION, project)
    }
    
    /**
     * Show notification
     * @param title Notification title
     * @param content Notification content
     * @param type Notification type
     * @param project Project instance, if null the default project is used
     */
    private fun showNotification(title: String, content: String, type: NotificationType, project: Project?) {
        val targetProject = project ?: ProjectManager.getInstance().defaultProject
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)

        notificationGroup?.createNotification(title, content, type)?.notify(targetProject)
    }

    /**
     * Open [url] in the OS default browser, but never let a failure propagate.
     *
     * On intranet / restricted / headless machines `BrowserUtil.browse` can throw
     * (e.g. "Desktop API is not supported on the current platform", missing default
     * browser, `IOException` from `xdg-open`, etc.). When called from a JCEF
     * callback such an uncaught throwable will freeze or crash the embedded
     * webview. This wrapper catches everything, copies the URL to the clipboard
     * as a fallback so the user can still reach the link, and shows a warning.
     *
     * @param url URL to open. If null/blank, nothing happens.
     * @param project Project used for the fallback notification, if any.
     */
    @JvmOverloads
    fun safeBrowse(url: String?, project: Project? = null) {
        if (url.isNullOrBlank()) return
        try {
            BrowserUtil.browse(url)
        } catch (t: Throwable) {
            logger.warn("Failed to open URL in default browser: $url", t)
            try {
                CopyPasteManager.getInstance().setContents(StringSelection(url))
            } catch (copyErr: Throwable) {
                logger.warn("Failed to copy URL to clipboard as fallback", copyErr)
            }
            showWarning(
                "无法打开浏览器",
                "唤起系统默认浏览器失败，链接已复制到剪贴板：$url",
                project
            )
        }
    }


}