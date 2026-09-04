// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.project;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface DumbUtil {

  static DumbUtil getInstance() {
    return ApplicationManager.getApplication().getService(DumbUtil.class);
  }

  /**
   * @return true iff one may use file based indices, i.e. project is not in dumb mode, or
   * {@link com.intellij.util.indexing.FileBasedIndex#ignoreDumbMode} was used
   */
  boolean mayUseIndices(@NotNull Project project);
}
