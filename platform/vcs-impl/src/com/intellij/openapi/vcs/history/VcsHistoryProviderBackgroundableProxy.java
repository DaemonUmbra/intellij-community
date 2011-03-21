/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.BackgroundFromStartOption;
import com.intellij.openapi.vcs.diff.DiffProvider;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.impl.BackgroundableActionEnabledHandler;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsBackgroundableActions;
import com.intellij.openapi.vcs.impl.VcsBackgroundableComputable;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

/**
 * also uses memory cache
 */
public class VcsHistoryProviderBackgroundableProxy {
  private final Project myProject;
  private final DiffProvider myDiffProvider;
  private final VcsHistoryProvider myDelegate;
  private VcsHistoryCache myVcsHistoryCache;
  private boolean myCachesHistory;
  private final HistoryComputerFactory myHistoryComputerFactory;

  public VcsHistoryProviderBackgroundableProxy(final Project project, final VcsHistoryProvider delegate, DiffProvider diffProvider) {
    myDelegate = delegate;
    myProject = project;
    myCachesHistory = myDelegate instanceof VcsCacheableHistorySessionFactory;
    myDiffProvider = diffProvider;
    myVcsHistoryCache = ProjectLevelVcsManager.getInstance(myProject).getVcsHistoryCache();
    myHistoryComputerFactory = new HistoryComputerFactory() {
      @Override
      public ThrowableComputable<VcsHistorySession, VcsException> create(FilePath filePath,
                                                                         Consumer<VcsHistorySession> consumer,
                                                                         VcsKey vcsKey) {
        if (myCachesHistory) {
          return new CachingHistoryComputer(filePath, consumer, vcsKey);
        } else {
          return new SimpelHistoryComputer(filePath, consumer);
        }
      }
    };
  }

  public void createSessionFor(final VcsKey vcsKey, final FilePath filePath, final Consumer<VcsHistorySession> continuation,
                               @Nullable VcsBackgroundableActions actionKey,
                               final boolean silent,
                               @Nullable final Consumer<VcsHistorySession> backgroundSpecialization) {
    final ThrowableComputable<VcsHistorySession, VcsException> throwableComputable =
      myHistoryComputerFactory.create(filePath, backgroundSpecialization, vcsKey);
    final VcsBackgroundableActions resultingActionKey = actionKey == null ? VcsBackgroundableActions.CREATE_HISTORY_SESSION : actionKey;
    final Object key = VcsBackgroundableActions.keyFrom(filePath);

    if (silent) {
      VcsBackgroundableComputable.createAndRunSilent(myProject, resultingActionKey, key, VcsBundle.message("loading.file.history.progress"),
                                                     throwableComputable, continuation);
    } else {
      VcsBackgroundableComputable.createAndRun(myProject, resultingActionKey, key, VcsBundle.message("loading.file.history.progress"),
                                               VcsBundle.message("message.title.could.not.load.file.history"), throwableComputable, continuation, null);
    }
  }

  public void executeAppendableSession(final VcsKey vcsKey, final FilePath filePath, final VcsAppendableHistorySessionPartner partner,
                                       @Nullable VcsBackgroundableActions actionKey, boolean canUseCache) {
    if (myCachesHistory && canUseCache) {
      final VcsAbstractHistorySession session =
        myVcsHistoryCache.getFull(filePath, vcsKey, (VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate);
      if (session != null) {
        partner.reportCreatedEmptySession(session);
        partner.finished();
        partner.forceRefresh();
        return;
      }
    }

    final ProjectLevelVcsManagerImpl vcsManager = (ProjectLevelVcsManagerImpl) ProjectLevelVcsManager.getInstance(myProject);
    final VcsBackgroundableActions resultingActionKey = actionKey == null ? VcsBackgroundableActions.CREATE_HISTORY_SESSION : actionKey;

    final BackgroundableActionEnabledHandler handler;
    handler = vcsManager.getBackgroundableActionHandler(resultingActionKey);
    // fo not start same action twice
    if (handler.isInProgress(resultingActionKey)) return;

    handler.register(resultingActionKey);

    final VcsAppendableHistorySessionPartner cachedPartner;
    if (myCachesHistory) {
      cachedPartner = new HistoryPartnerProxy(partner, new Consumer<VcsAbstractHistorySession>() {
        @Override
        public void consume(VcsAbstractHistorySession session) {
          if (session == null) return;
          final FilePath correctedPath =
            ((VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate).getUsedFilePath(session);
          myVcsHistoryCache.put(filePath, correctedPath, vcsKey, (VcsAbstractHistorySession)session.copy(),
                                (VcsCacheableHistorySessionFactory<Serializable,VcsAbstractHistorySession>) myDelegate, true);
        }
      });
    } else {
      cachedPartner = partner;
    }
    reportHistory(filePath, vcsKey, resultingActionKey, handler, cachedPartner);
  }

  private void reportHistory(final FilePath filePath, final VcsKey vcsKey,
                             final VcsBackgroundableActions resultingActionKey,
                             final BackgroundableActionEnabledHandler handler,
                             final VcsAppendableHistorySessionPartner cachedPartner) {
    ProgressManager.getInstance().run(new Task.Backgroundable(myProject, VcsBundle.message("loading.file.history.progress"),
                                                              true, BackgroundFromStartOption.getInstance()) {
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          final VcsHistorySession cachedSession = myCachesHistory ? getSessionFromCacheWithLastRevisionCheck(filePath, vcsKey) : null;
          if (cachedSession != null) {
            cachedPartner.reportCreatedEmptySession((VcsAbstractHistorySession)cachedSession);
          } else {
            myDelegate.reportAppendableHistory(filePath, cachedPartner);
          }
        }
        catch (VcsException e) {
          cachedPartner.reportException(e);
        }
        finally {
          cachedPartner.finished();
          ApplicationManager.getApplication().invokeLater(new Runnable() {
              public void run() {
                handler.completed(resultingActionKey);
              }
            }, ModalityState.NON_MODAL);
        }
      }
    });
  }

  private static class HistoryPartnerProxy implements VcsAppendableHistorySessionPartner {
    private final VcsAppendableHistorySessionPartner myPartner;
    private final Consumer<VcsAbstractHistorySession> myFinish;
    private VcsAbstractHistorySession myCopy;

    private HistoryPartnerProxy(VcsAppendableHistorySessionPartner partner, final Consumer<VcsAbstractHistorySession> finish) {
      myPartner = partner;
      myFinish = finish;
    }

    @Override
    public void reportCreatedEmptySession(VcsAbstractHistorySession session) {
      myCopy = (VcsAbstractHistorySession) session.copy();
      myPartner.reportCreatedEmptySession(session);
    }

    @Override
    public void acceptRevision(VcsFileRevision revision) {
      myCopy.appendRevision(revision);
      myPartner.acceptRevision(revision);
    }

    @Override
    public void reportException(VcsException exception) {
      myPartner.reportException(exception);
    }

    @Override
    public void finished() {
      myPartner.finished();
      myFinish.consume(myCopy);
    }

    @Override
    public void forceRefresh() {
      myPartner.forceRefresh();
    }
  }

  private interface HistoryComputerFactory {
    ThrowableComputable<VcsHistorySession, VcsException> create(FilePath filePath, Consumer<VcsHistorySession> consumer, VcsKey vcsKey);
  }

  private class SimpelHistoryComputer implements ThrowableComputable<VcsHistorySession, VcsException> {
    private final FilePath myFilePath;
    private final Consumer<VcsHistorySession> myConsumer;

    private SimpelHistoryComputer(FilePath filePath, Consumer<VcsHistorySession> consumer) {
      myFilePath = filePath;
      myConsumer = consumer;
    }

    @Override
    public VcsHistorySession compute() throws VcsException {
      VcsHistorySession session = myDelegate.createSessionFor(myFilePath);
      if (myConsumer != null) {
        myConsumer.consume(session);
      }
      return session;
    }
  }

  private class CachingHistoryComputer implements ThrowableComputable<VcsHistorySession, VcsException> {
    private final FilePath myFilePath;
    private final Consumer<VcsHistorySession> myConsumer;
    private final VcsKey myVcsKey;

    private CachingHistoryComputer(FilePath filePath, Consumer<VcsHistorySession> consumer, VcsKey vcsKey) {
      myFilePath = filePath;
      myConsumer = consumer;
      myVcsKey = vcsKey;
    }

    @Override
    public VcsHistorySession compute() throws VcsException {
      VcsHistorySession session = null;
      // we check for the last revision, since requests to this exact method at the moment only request history once, and no refresh is possible later
      session = getSessionFromCacheWithLastRevisionCheck(myFilePath, myVcsKey);
      if (session == null) {
        session = myDelegate.createSessionFor(myFilePath);
        final FilePath correctedPath =
          ((VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate).getUsedFilePath(
            (VcsAbstractHistorySession)session);
        myVcsHistoryCache.put(myFilePath, correctedPath, myVcsKey, (VcsAbstractHistorySession)((VcsAbstractHistorySession) session).copy(),
                              (VcsCacheableHistorySessionFactory<Serializable,VcsAbstractHistorySession>) myDelegate, true);
      }
      if (myConsumer != null) {
        myConsumer.consume(session);
      }
      return session;
    }
  }

  @Nullable
  private VcsHistorySession getSessionFromCacheWithLastRevisionCheck(final FilePath filePath, final VcsKey vcsKey) {
    final ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();
    if (pi != null) {
      pi.setText2("Checking last revision");
    }
    final VcsAbstractHistorySession cached = myVcsHistoryCache
      .getFull(filePath, vcsKey, (VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate);
    if (cached == null) return null;
    final FilePath correctedFilePath =
      ((VcsCacheableHistorySessionFactory<Serializable, VcsAbstractHistorySession>)myDelegate).getUsedFilePath(cached);

    final ItemLatestState lastRevision = myDiffProvider.getLastRevision(correctedFilePath != null ? correctedFilePath : filePath);
    if (lastRevision != null && ! lastRevision.isDefaultHead() && lastRevision.isItemExists()) {
      final List<VcsFileRevision> revisionList = cached.getRevisionList();
      if (! revisionList.isEmpty() && revisionList.get(0).getRevisionNumber().equals(lastRevision.getNumber())) {
        return cached;
      }
    }
    return null;
  }
}
