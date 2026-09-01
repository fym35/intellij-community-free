// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.externalSystem.autoimport

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.ApiStatus.Internal

@ApiStatus.NonExtendable
interface ExternalSystemProjectTracker {

  /**
   * Starts tracking of project settings that will be defined by [projectAware]
   *
   * Auto reloads will be activated after first project refresh
   * (i.e. after first [ExternalSystemProjectListener.onProjectReloadStart] / [ExternalSystemProjectListener.onProjectReloadFinish])
   * @see [ExternalSystemProjectTracker.activate] for details
   */
  fun register(projectAware: ExternalSystemProjectAware)

  /**
   * @see [ExternalSystemProjectTracker.register]
   * @param [parentDisposable] allows to remove [projectAware] when it will be disposed
   */
  fun register(projectAware: ExternalSystemProjectAware, parentDisposable: Disposable) {
    register(projectAware)
    Disposer.register(parentDisposable, Disposable { remove(projectAware.projectId) })
  }

  /**
   * Activates auto reload for project with [id]
   *
   * Allows to detect project that loaded from local cashes but previously didn't register here
   */
  fun activate(id: ExternalSystemProjectId)

  /**
   * Stops tracking of project settings that were defined by [ExternalSystemProjectAware] with [id]
   */
  fun remove(id: ExternalSystemProjectId)

  /**
   * Marks that project [id] has undefined modifications.
   *
   * Also, it schedules auto-sync with:
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.ALL],
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.SELECTIVE];
   * Also, it shows notification with:
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.NONE].
   */
  fun markDirty(id: ExternalSystemProjectId)

  /**
   * Marks that all projects have undefined modifications.
   *
   * @see markDirty
   */
  fun markDirtyAllProjects()

  /**
   * Marks that project [id] has an internal undefined modification.
   *
   * Also, it schedules auto-sync with:
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.ALL];
   * Also, it shows notification with:
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.SELECTIVE],
   *  - [ExternalSystemProjectTrackerSettings.AutoReloadType.NONE].
   *
   * @see markDirty
   */
  @Internal
  fun markDirtyInternal(id: ExternalSystemProjectId)

  /**
   * Schedules sync for all dirty or modified project.
   *
   * This method ignores the [ExternalSystemProjectTrackerSettings.AutoReloadType] setting.
   *
   * Call this method to sync only projects that already have modifications.
   *
   * Call this method right after [markDirty] to include all marked projects to sync (force sync).
   *
   * @see markDirty
   * @see markDirtyAllProjects
   */
  fun scheduleProjectRefresh()

  companion object {
    @JvmStatic
    fun getInstance(project: Project): ExternalSystemProjectTracker {
      return project.getService(ExternalSystemProjectTracker::class.java)
    }
  }
}