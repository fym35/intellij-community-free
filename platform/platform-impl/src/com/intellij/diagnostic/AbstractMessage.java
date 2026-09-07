// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic;

import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** Internal API. See a note in {@link MessagePool}. */
@ApiStatus.Internal
public abstract class AbstractMessage {
  private final Date myDate = Calendar.getInstance().getTime();
  private boolean myIsRead;
  private Runnable myOnReadCallback;
  private boolean myIsSubmitting;
  private SubmittedReportInfo mySubmissionInfo;
  private String myAdditionalInfo;
  private String myAppInfo;

  public abstract @NotNull Throwable getThrowable();

  public abstract @NotNull String getThrowableText();

  /** Returns a message passed along with a throwable to {@link com.intellij.openapi.diagnostic.Logger#error}, if present. */
  public abstract @Nullable String getMessage();

  /** Returns a (possibly empty) list of all attachments. */
  public @NotNull @Unmodifiable List<Attachment> getAllAttachments() {
    return List.of();
  }

  public final @NotNull @Unmodifiable List<Attachment> getIncludedAttachments() {
    return ContainerUtil.filter(getAllAttachments(), Attachment::isIncluded);
  }

  public final @NotNull Date getDate() {
    return myDate;
  }

  public final boolean isRead() {
    return myIsRead;
  }

  public final void setRead(boolean isRead) {
    myIsRead = isRead;
    if (isRead && myOnReadCallback != null) {
      myOnReadCallback.run();
      myOnReadCallback = null;
    }
  }

  public final void setOnReadCallback(Runnable callback) {
    myOnReadCallback = callback;
  }

  public final boolean isSubmitting() {
    return myIsSubmitting;
  }

  public final void setSubmitting(boolean isSubmitting) {
    myIsSubmitting = isSubmitting;
  }

  public final SubmittedReportInfo getSubmissionInfo() {
    return mySubmissionInfo;
  }

  public final boolean isSubmitted() {
    return mySubmissionInfo != null &&
           (mySubmissionInfo.getStatus() == SubmittedReportInfo.SubmissionStatus.NEW_ISSUE ||
            mySubmissionInfo.getStatus() == SubmittedReportInfo.SubmissionStatus.DUPLICATE);
  }

  public final void setSubmitted(SubmittedReportInfo info) {
    myIsSubmitting = false;
    mySubmissionInfo = info;
  }

  public final String getAdditionalInfo() {
    return myAdditionalInfo;
  }

  public final void setAdditionalInfo(String additionalInfo) {
    myAdditionalInfo = additionalInfo;
  }

  protected final @Nullable String getAppInfo() {
    return myAppInfo;
  }

  protected final void setAppInfo(String appInfo) {
    myAppInfo = appInfo;
  }
}
