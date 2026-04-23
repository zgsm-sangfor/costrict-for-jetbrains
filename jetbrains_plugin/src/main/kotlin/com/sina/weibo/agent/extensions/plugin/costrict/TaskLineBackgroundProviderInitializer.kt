// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.extensions.plugin.costrict

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer

/**
 * 任务行背景提供者初始化器
 * 在 IDE 启动时初始化 TaskLineBackgroundProvider
 */
class TaskLineBackgroundProviderInitializer : StartupActivity.DumbAware {
    private var taskLineBackgroundProvider: TaskLineBackgroundProvider? = null
    
    override fun runActivity(project: Project) {
        // println("TaskLineBackgroundProviderInitializer: 开始初始化项目 $project")
        
        // 初始化任务行背景提供者
        taskLineBackgroundProvider = TaskLineBackgroundProvider()
        taskLineBackgroundProvider?.init()
        
        // 修复：确保在项目关闭时清理资源
        Disposer.register(project, {
            // println("TaskLineBackgroundProviderInitializer: 项目关闭，清理资源")
            dispose()
        })
        
        // println("TaskLineBackgroundProviderInitializer: 初始化完成")
    }
    
    /**
     * 清理资源
     */
    fun dispose() {
        taskLineBackgroundProvider?.dispose()
        taskLineBackgroundProvider = null
    }
}