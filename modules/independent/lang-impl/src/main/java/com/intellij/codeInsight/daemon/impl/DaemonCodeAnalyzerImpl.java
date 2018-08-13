/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.*;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.daemon.*;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.impl.FileLevelIntentionComponent;
import com.intellij.codeInsight.intention.impl.IntentionHintComponent;
import com.intellij.diagnostic.ThreadDumper;
import com.intellij.ide.PowerSaveMode;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.text.DesktopAsyncEditorLoader;
import com.intellij.openapi.fileEditor.impl.text.DesktopTextEditorImpl;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.impl.FileTypeManagerImpl;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.RefreshQueueImpl;
import com.intellij.packageDependencies.DependencyValidationManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiDocumentManagerBase;
import com.intellij.psi.search.scope.packageSet.NamedScopeManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.*;
import com.intellij.util.concurrency.EdtExecutorService;
import com.intellij.util.io.storage.HeavyProcessLatch;
import com.intellij.util.ui.UIUtil;
import consulo.fileEditor.impl.text.TextEditorProvider;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.TestOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * This class also controls the auto-reparse and auto-hints.
 */
@State(name = "DaemonCodeAnalyzer", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public class DaemonCodeAnalyzerImpl extends DaemonCodeAnalyzerEx implements PersistentStateComponent<Element>, Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl");

  private static final Key<List<HighlightInfo>> FILE_LEVEL_HIGHLIGHTS = Key.create("FILE_LEVEL_HIGHLIGHTS");
  private final Project myProject;
  private final DaemonCodeAnalyzerSettings mySettings;
  @Nonnull
  private final EditorTracker myEditorTracker;
  @Nonnull
  private final PsiDocumentManager myPsiDocumentManager;
  private DaemonProgressIndicator myUpdateProgress = new DaemonProgressIndicator(); //guarded by this

  private final UpdateRunnable myUpdateRunnable;
  // use scheduler instead of Alarm because the latter requires ModalityState.current() which is obtainable from EDT only which requires too many invokeLaters
  private final ScheduledExecutorService myAlarm = EdtExecutorService.getScheduledExecutorInstance();
  @Nonnull
  private volatile Future<?> myUpdateRunnableFuture = CompletableFuture.completedFuture(null);
  private boolean myUpdateByTimerEnabled = true;
  private final Collection<VirtualFile> myDisabledHintsFiles = new THashSet<>();
  private final Collection<VirtualFile> myDisabledHighlightingFiles = new THashSet<>();

  private final FileStatusMap myFileStatusMap;
  private DaemonCodeAnalyzerSettings myLastSettings;

  private volatile IntentionHintComponent myLastIntentionHint;
  private volatile boolean myDisposed;     // the only possible transition: false -> true
  private volatile boolean myInitialized;  // the only possible transition: false -> true

  @NonNls
  private static final String DISABLE_HINTS_TAG = "disable_hints";
  @NonNls
  private static final String FILE_TAG = "file";
  @NonNls
  private static final String URL_ATT = "url";
  private final PassExecutorService myPassExecutorService;

  public DaemonCodeAnalyzerImpl(@Nonnull Project project,
                                @Nonnull DaemonCodeAnalyzerSettings daemonCodeAnalyzerSettings,
                                @Nonnull EditorTracker editorTracker,
                                @Nonnull PsiDocumentManager psiDocumentManager,
                                @SuppressWarnings("UnusedParameters") @Nonnull final NamedScopeManager namedScopeManager,
                                @SuppressWarnings("UnusedParameters") @Nonnull final DependencyValidationManager dependencyValidationManager) {
    myProject = project;
    mySettings = daemonCodeAnalyzerSettings;
    myEditorTracker = editorTracker;
    myPsiDocumentManager = psiDocumentManager;
    myLastSettings = ((DaemonCodeAnalyzerSettingsImpl)daemonCodeAnalyzerSettings).clone();

    myFileStatusMap = new FileStatusMap(project);
    myPassExecutorService = new PassExecutorService(project);
    Disposer.register(this, myPassExecutorService);
    Disposer.register(this, myFileStatusMap);
    DaemonProgressIndicator.setDebug(LOG.isDebugEnabled());

    assert !myInitialized : "Double Initializing";
    Disposer.register(this, new StatusBarUpdater(project));

    myInitialized = true;
    myDisposed = false;
    myFileStatusMap.markAllFilesDirty("DCAI init");
    myUpdateRunnable = new UpdateRunnable(myProject);
    Disposer.register(this, () -> {
      assert myInitialized : "Disposing not initialized component";
      assert !myDisposed : "Double dispose";
      myUpdateRunnable.clearFieldsOnDispose();

      stopProcess(false, "Dispose");

      myDisposed = true;
      myLastSettings = null;
    });
  }

  @Override
  public synchronized void dispose() {
    myUpdateProgress = new DaemonProgressIndicator(); // leak of highlight session via user data
    myUpdateRunnableFuture.cancel(true);
  }

  @Nonnull
  @TestOnly
  public static List<HighlightInfo> getHighlights(@Nonnull Document document, HighlightSeverity minSeverity, @Nonnull Project project) {
    List<HighlightInfo> infos = new ArrayList<>();
    processHighlights(document, project, minSeverity, 0, document.getTextLength(), Processors.cancelableCollectProcessor(infos));
    return infos;
  }

  @Override
  @Nonnull
  @TestOnly
  public List<HighlightInfo> getFileLevelHighlights(@Nonnull Project project, @Nonnull PsiFile file) {
    VirtualFile vFile = file.getViewProvider().getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    return Arrays.stream(manager.getEditors(vFile)).map(fileEditor -> fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS)).filter(Objects::nonNull).flatMap(Collection::stream).collect(Collectors.toList());
  }

  @Override
  public void cleanFileLevelHighlights(@Nonnull Project project, final int group, PsiFile psiFile) {
    if (psiFile == null) return;
    FileViewProvider provider = psiFile.getViewProvider();
    VirtualFile vFile = provider.getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    for (FileEditor fileEditor : manager.getEditors(vFile)) {
      final List<HighlightInfo> infos = fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS);
      if (infos == null) continue;
      List<HighlightInfo> infosToRemove = new ArrayList<>();
      for (HighlightInfo info : infos) {
        if (info.getGroup() == group) {
          manager.removeTopComponent(fileEditor, info.fileLevelComponent);
          infosToRemove.add(info);
        }
      }
      infos.removeAll(infosToRemove);
    }
  }

  @Override
  public void addFileLevelHighlight(@Nonnull final Project project, final int group, @Nonnull final HighlightInfo info, @Nonnull final PsiFile psiFile) {
    VirtualFile vFile = psiFile.getViewProvider().getVirtualFile();
    final FileEditorManager manager = FileEditorManager.getInstance(project);
    for (FileEditor fileEditor : manager.getEditors(vFile)) {
      if (fileEditor instanceof TextEditor) {
        FileLevelIntentionComponent component = new FileLevelIntentionComponent(info.getDescription(), info.getSeverity(), info.getGutterIconRenderer(), info.quickFixActionRanges, project, psiFile,
                                                                                ((TextEditor)fileEditor).getEditor(), info.getToolTip());
        manager.addTopComponent(fileEditor, component);
        List<HighlightInfo> fileLevelInfos = fileEditor.getUserData(FILE_LEVEL_HIGHLIGHTS);
        if (fileLevelInfos == null) {
          fileLevelInfos = new ArrayList<>();
          fileEditor.putUserData(FILE_LEVEL_HIGHLIGHTS, fileLevelInfos);
        }
        info.fileLevelComponent = component;
        info.setGroup(group);
        fileLevelInfos.add(info);
      }
    }
  }

  @Override
  @Nonnull
  public List<HighlightInfo> runMainPasses(@Nonnull PsiFile psiFile, @Nonnull Document document, @Nonnull final ProgressIndicator progress) {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      throw new IllegalStateException("Must not run highlighting from under EDT");
    }
    if (!ApplicationManager.getApplication().isReadAccessAllowed()) {
      throw new IllegalStateException("Must run highlighting from under read action");
    }
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    if (!(indicator instanceof DaemonProgressIndicator)) {
      throw new IllegalStateException("Must run highlighting under progress with DaemonProgressIndicator");
    }
    // clear status maps to run passes from scratch so that refCountHolder won't conflict and try to restart itself on partially filled maps
    myFileStatusMap.markAllFilesDirty("prepare to run main passes");
    stopProcess(false, "disable background daemon");
    myPassExecutorService.cancelAll(true);

    final List<HighlightInfo> result;
    try {
      result = new ArrayList<>();
      final VirtualFile virtualFile = psiFile.getVirtualFile();
      if (virtualFile != null && !virtualFile.getFileType().isBinary()) {
        List<TextEditorHighlightingPass> passes = TextEditorHighlightingPassManager.getInstance(myProject).instantiateMainPasses(psiFile, document, HighlightInfoProcessor.getEmpty());

        Collections.sort(passes, (o1, o2) -> {
          if (o1 instanceof GeneralHighlightingPass) return -1;
          if (o2 instanceof GeneralHighlightingPass) return 1;
          return 0;
        });

        try {
          for (TextEditorHighlightingPass pass : passes) {
            pass.doCollectInformation(progress);
            result.addAll(pass.getInfos());
          }
        }
        catch (ProcessCanceledException e) {
          LOG.debug("Canceled: " + progress);
          throw e;
        }
      }
    }
    finally {
      stopProcess(true, "re-enable background daemon after main passes run");
    }

    return result;
  }

  @Nonnull
  @TestOnly
  public List<HighlightInfo> runPasses(@Nonnull PsiFile file,
                                       @Nonnull Document document,
                                       @Nonnull TextEditor textEditor,
                                       @Nonnull int[] toIgnore,
                                       boolean canChangeDocument,
                                       @Nullable Runnable callbackWhileWaiting) throws ProcessCanceledException {
    return runPasses(file, document, Collections.singletonList(textEditor), toIgnore, canChangeDocument, callbackWhileWaiting);
  }

  private volatile boolean mustWaitForSmartMode = true;

  @TestOnly
  public void mustWaitForSmartMode(final boolean mustWait, @Nonnull Disposable parent) {
    final boolean old = mustWaitForSmartMode;
    mustWaitForSmartMode = mustWait;
    Disposer.register(parent, () -> mustWaitForSmartMode = old);
  }

  @Nonnull
  @TestOnly
  List<HighlightInfo> runPasses(@Nonnull PsiFile file,
                                @Nonnull Document document,
                                @Nonnull List<TextEditor> textEditors,
                                @Nonnull int[] toIgnore,
                                boolean canChangeDocument,
                                @Nullable final Runnable callbackWhileWaiting) throws ProcessCanceledException {
    assert myInitialized;
    assert !myDisposed;
    ApplicationEx application = ApplicationManagerEx.getApplicationEx();
    application.assertIsDispatchThread();
    if (application.isWriteAccessAllowed()) {
      throw new AssertionError("Must not start highlighting from within write action, or deadlock is imminent");
    }
    DaemonProgressIndicator.setDebug(!ApplicationInfoImpl.isInPerformanceTest());
    ((FileTypeManagerImpl)FileTypeManager.getInstance()).drainReDetectQueue();
    // pump first so that queued event do not interfere
    UIUtil.dispatchAllInvocationEvents();

    // refresh will fire write actions interfering with highlighting
    while (RefreshQueueImpl.isRefreshInProgress() || HeavyProcessLatch.INSTANCE.isRunning()) {
      UIUtil.dispatchAllInvocationEvents();
    }
    long dstart = System.currentTimeMillis();
    while (mustWaitForSmartMode && DumbService.getInstance(myProject).isDumb()) {
      if (System.currentTimeMillis() > dstart + 100000) {
        throw new IllegalStateException("Timeout waiting for smart mode. If you absolutely want to be dumb, please use DaemonCodeAnalyzerImpl.mustWaitForSmartMode(false).");
      }
      UIUtil.dispatchAllInvocationEvents();
    }

    UIUtil.dispatchAllInvocationEvents();

    Project project = file.getProject();
    FileStatusMap fileStatusMap = getFileStatusMap();
    fileStatusMap.allowDirt(canChangeDocument);

    Map<FileEditor, HighlightingPass[]> map = new HashMap<>();
    for (TextEditor textEditor : textEditors) {
      if (textEditor instanceof DesktopTextEditorImpl) {
        try {
          ((DesktopTextEditorImpl)textEditor).waitForLoaded(10, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
          throw new RuntimeException(textEditor + " has not completed loading in 10 seconds");
        }
      }
      TextEditorBackgroundHighlighter highlighter = (TextEditorBackgroundHighlighter)textEditor.getBackgroundHighlighter();
      if (highlighter == null) {
        Editor editor = textEditor.getEditor();
        throw new RuntimeException("Null highlighter from " + textEditor + "; loaded: " + DesktopAsyncEditorLoader.isEditorLoaded(editor));
      }
      final List<TextEditorHighlightingPass> passes = highlighter.getPasses(toIgnore);
      HighlightingPass[] array = passes.toArray(new HighlightingPass[passes.size()]);
      assert array.length != 0 : "Highlighting is disabled for the file " + file;
      map.put(textEditor, array);
    }
    for (int ignoreId : toIgnore) {
      fileStatusMap.markFileUpToDate(document, ignoreId);
    }

    myUpdateRunnableFuture.cancel(false);

    final DaemonProgressIndicator progress = createUpdateProgress();
    myPassExecutorService.submitPasses(map, progress);
    try {
      long start = System.currentTimeMillis();
      while (progress.isRunning() && System.currentTimeMillis() < start + 5 * 60 * 1000) {
        wrap(() -> {
          progress.checkCanceled();
          if (callbackWhileWaiting != null) {
            callbackWhileWaiting.run();
          }
          waitInOtherThread(50, canChangeDocument);
          UIUtil.dispatchAllInvocationEvents();
          Throwable savedException = PassExecutorService.getSavedException(progress);
          if (savedException != null) throw savedException;
        });
      }
      if (progress.isRunning() && !progress.isCanceled()) {
        throw new RuntimeException("Highlighting still running after " + (System.currentTimeMillis() - start) / 1000 + " seconds.\n" + ThreadDumper.dumpThreadsToString());
      }

      final HighlightingSessionImpl session = (HighlightingSessionImpl)HighlightingSessionImpl.getOrCreateHighlightingSession(file, textEditors.get(0).getEditor(), progress, null);
      wrap(() -> {
        if (!waitInOtherThread(60000, canChangeDocument)) {
          throw new TimeoutException("Unable to complete in 60s");
        }
        session.waitForHighlightInfosApplied();
      });
      UIUtil.dispatchAllInvocationEvents();
      UIUtil.dispatchAllInvocationEvents();
      assert progress.isCanceled() && progress.isDisposed();

      return getHighlights(document, null, project);
    }
    finally {
      DaemonProgressIndicator.setDebug(false);
      fileStatusMap.allowDirt(true);
      waitForTermination();
    }
  }

  @TestOnly
  private boolean waitInOtherThread(int millis, boolean canChangeDocument) throws Throwable {
    Disposable disposable = Disposer.newDisposable();
    // last hope protection against PsiModificationTrackerImpl.incCounter() craziness (yes, Kotlin)
    myProject.getMessageBus().connect(disposable).subscribe(PsiModificationTracker.TOPIC, () -> {
      throw new IllegalStateException("You must not perform PSI modifications from inside highlighting");
    });
    if (!canChangeDocument) {
      myProject.getMessageBus().connect(disposable).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, new DaemonListenerAdapter() {
        @Override
        public void daemonCancelEventOccurred(@Nonnull String reason) {
          throw new IllegalStateException("You must not cancel daemon inside highlighting test: " + reason);
        }
      });
    }

    try {
      Future<Boolean> future = ApplicationManager.getApplication().executeOnPooledThread(() -> {
        try {
          return myPassExecutorService.waitFor(millis);
        }
        catch (Throwable e) {
          throw new RuntimeException(e);
        }
      });
      return future.get();
    }
    finally {
      Disposer.dispose(disposable);
    }
  }

  @TestOnly
  public void prepareForTest() {
    setUpdateByTimerEnabled(false);
    waitForTermination();
  }

  @TestOnly
  public void cleanupAfterTest() {
    if (myProject.isOpen()) {
      prepareForTest();
    }
  }

  @TestOnly
  void waitForTermination() {
    myPassExecutorService.cancelAll(true);
  }

  @Override
  public void settingsChanged() {
    DaemonCodeAnalyzerSettings settings = DaemonCodeAnalyzerSettings.getInstance();
    if (settings.isCodeHighlightingChanged(myLastSettings)) {
      restart();
    }
    myLastSettings = ((DaemonCodeAnalyzerSettingsImpl)settings).clone();
  }

  @Override
  public void updateVisibleHighlighters(@Nonnull Editor editor) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    // no need, will not work anyway
  }

  @Override
  public void setUpdateByTimerEnabled(boolean value) {
    myUpdateByTimerEnabled = value;
    stopProcess(value, "Update by timer change");
  }

  private int myDisableCount;

  @Override
  public void disableUpdateByTimer(@Nonnull Disposable parentDisposable) {
    setUpdateByTimerEnabled(false);
    myDisableCount++;
    ApplicationManager.getApplication().assertIsDispatchThread();

    Disposer.register(parentDisposable, () -> {
      myDisableCount--;
      if (myDisableCount == 0) {
        setUpdateByTimerEnabled(true);
      }
    });
  }

  boolean isUpdateByTimerEnabled() {
    return myUpdateByTimerEnabled;
  }

  @Override
  public void setImportHintsEnabled(@Nonnull PsiFile file, boolean value) {
    VirtualFile vFile = file.getVirtualFile();
    if (value) {
      myDisabledHintsFiles.remove(vFile);
      stopProcess(true, "Import hints change");
    }
    else {
      myDisabledHintsFiles.add(vFile);
      HintManager.getInstance().hideAllHints();
    }
  }

  @Override
  public void resetImportHintsEnabledForProject() {
    myDisabledHintsFiles.clear();
  }

  @Override
  public void setHighlightingEnabled(@Nonnull PsiFile file, boolean value) {
    VirtualFile virtualFile = PsiUtilCore.getVirtualFile(file);
    if (value) {
      myDisabledHighlightingFiles.remove(virtualFile);
    }
    else {
      myDisabledHighlightingFiles.add(virtualFile);
    }
  }

  @Override
  public boolean isHighlightingAvailable(@Nullable PsiFile file) {
    if (file == null || !file.isPhysical()) return false;
    if (myDisabledHighlightingFiles.contains(PsiUtilCore.getVirtualFile(file))) return false;

    if (file instanceof PsiCompiledElement) return false;
    final FileType fileType = file.getFileType();

    // To enable T.O.D.O. highlighting
    return !fileType.isBinary();
  }

  @Override
  public boolean isImportHintsEnabled(@Nonnull PsiFile file) {
    return isAutohintsAvailable(file) && !myDisabledHintsFiles.contains(file.getVirtualFile());
  }

  @Override
  public boolean isAutohintsAvailable(PsiFile file) {
    return isHighlightingAvailable(file) && !(file instanceof PsiCompiledElement);
  }

  @Override
  public void restart() {
    doRestart();
  }

  // return true if the progress was really canceled
  boolean doRestart() {
    myFileStatusMap.markAllFilesDirty("Global restart");
    return stopProcess(true, "Global restart");
  }

  @Override
  public void restart(@Nonnull PsiFile file) {
    Document document = myPsiDocumentManager.getCachedDocument(file);
    if (document == null) return;
    String reason = "Psi file restart: " + file.getName();
    myFileStatusMap.markFileScopeDirty(document, new TextRange(0, document.getTextLength()), file.getTextLength(), reason);
    stopProcess(true, reason);
  }

  @Nonnull
  List<TextEditorHighlightingPass> getPassesToShowProgressFor(Document document) {
    List<TextEditorHighlightingPass> allPasses = myPassExecutorService.getAllSubmittedPasses();
    List<TextEditorHighlightingPass> result = new ArrayList<>(allPasses.size());
    for (TextEditorHighlightingPass pass : allPasses) {
      if (pass.getDocument() == document || pass.getDocument() == null) {
        result.add(pass);
      }
    }
    return result;
  }

  boolean isAllAnalysisFinished(@Nonnull PsiFile file) {
    if (myDisposed) return false;
    Document document = myPsiDocumentManager.getCachedDocument(file);
    return document != null && document.getModificationStamp() == file.getViewProvider().getModificationStamp() && myFileStatusMap.allDirtyScopesAreNull(document);
  }

  @Override
  public boolean isErrorAnalyzingFinished(@Nonnull PsiFile file) {
    if (myDisposed) return false;
    Document document = myPsiDocumentManager.getCachedDocument(file);
    return document != null && document.getModificationStamp() == file.getViewProvider().getModificationStamp() && myFileStatusMap.getFileDirtyScope(document, Pass.UPDATE_ALL) == null;
  }

  @Override
  @Nonnull
  public FileStatusMap getFileStatusMap() {
    return myFileStatusMap;
  }

  synchronized boolean isRunning() {
    return !myUpdateProgress.isCanceled();
  }

  @TestOnly
  public boolean isRunningOrPending() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return isRunning() || !myUpdateRunnableFuture.isDone();
  }

  // return true if the progress really was canceled
  synchronized boolean stopProcess(boolean toRestartAlarm, @Nonnull @NonNls String reason) {
    boolean canceled = cancelUpdateProgress(toRestartAlarm, reason);
    // optimisation: this check is to avoid too many re-schedules in case of thousands of events spikes
    boolean restart = toRestartAlarm && !myDisposed && myInitialized;

    if (restart && myUpdateRunnableFuture.isDone()) {
      myUpdateRunnableFuture.cancel(false);
      myUpdateRunnableFuture = myAlarm.schedule(myUpdateRunnable, mySettings.AUTOREPARSE_DELAY, TimeUnit.MILLISECONDS);
    }

    return canceled;
  }

  // return true if the progress really was canceled
  private synchronized boolean cancelUpdateProgress(boolean toRestartAlarm, @NonNls String reason) {
    DaemonProgressIndicator updateProgress = myUpdateProgress;
    if (myDisposed) return false;
    boolean wasCanceled = updateProgress.isCanceled();
    myPassExecutorService.cancelAll(false);
    if (!wasCanceled) {
      PassExecutorService.log(updateProgress, null, "Cancel", reason, toRestartAlarm);
      updateProgress.cancel();
      return true;
    }
    return false;
  }


  static boolean processHighlightsNearOffset(@Nonnull Document document,
                                             @Nonnull Project project,
                                             @Nonnull final HighlightSeverity minSeverity,
                                             final int offset,
                                             final boolean includeFixRange,
                                             @Nonnull final Processor<HighlightInfo> processor) {
    return processHighlights(document, project, null, 0, document.getTextLength(), info -> {
      if (!isOffsetInsideHighlightInfo(offset, info, includeFixRange)) return true;

      int compare = info.getSeverity().compareTo(minSeverity);
      return compare < 0 || processor.process(info);
    });
  }

  @Nullable
  public HighlightInfo findHighlightByOffset(@Nonnull Document document, final int offset, final boolean includeFixRange) {
    return findHighlightByOffset(document, offset, includeFixRange, HighlightSeverity.INFORMATION);
  }

  @Nullable
  HighlightInfo findHighlightByOffset(@Nonnull Document document, final int offset, final boolean includeFixRange, @Nonnull HighlightSeverity minSeverity) {
    final List<HighlightInfo> foundInfoList = new SmartList<>();
    processHighlightsNearOffset(document, myProject, minSeverity, offset, includeFixRange, info -> {
      if (info.getSeverity() == HighlightInfoType.ELEMENT_UNDER_CARET_SEVERITY) {
        return true;
      }
      if (!foundInfoList.isEmpty()) {
        HighlightInfo foundInfo = foundInfoList.get(0);
        int compare = foundInfo.getSeverity().compareTo(info.getSeverity());
        if (compare < 0) {
          foundInfoList.clear();
        }
        else if (compare > 0) {
          return true;
        }
      }
      foundInfoList.add(info);
      return true;
    });

    if (foundInfoList.isEmpty()) return null;
    if (foundInfoList.size() == 1) return foundInfoList.get(0);
    return new HighlightInfoComposite(foundInfoList);
  }

  private static boolean isOffsetInsideHighlightInfo(int offset, @Nonnull HighlightInfo info, boolean includeFixRange) {
    RangeHighlighterEx highlighter = info.highlighter;
    if (highlighter == null || !highlighter.isValid()) return false;
    int startOffset = highlighter.getStartOffset();
    int endOffset = highlighter.getEndOffset();
    if (startOffset <= offset && offset <= endOffset) {
      return true;
    }
    if (!includeFixRange) return false;
    RangeMarker fixMarker = info.fixMarker;
    if (fixMarker != null) {  // null means its range is the same as highlighter
      if (!fixMarker.isValid()) return false;
      startOffset = fixMarker.getStartOffset();
      endOffset = fixMarker.getEndOffset();
      return startOffset <= offset && offset <= endOffset;
    }
    return false;
  }

  @Nonnull
  public static List<LineMarkerInfo> getLineMarkers(@Nonnull Document document, @Nonnull Project project) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    List<LineMarkerInfo> result = new ArrayList<>();
    LineMarkersUtil.processLineMarkers(project, document, new TextRange(0, document.getTextLength()), -1, new CommonProcessors.CollectProcessor<>(result));
    return result;
  }

  void setLastIntentionHint(@Nonnull Project project, @Nonnull PsiFile file, @Nonnull Editor editor, @Nonnull ShowIntentionsPass.IntentionsInfo intentions, boolean hasToRecreate) {
    if (!editor.getSettings().isShowIntentionBulb()) {
      return;
    }
    ApplicationManager.getApplication().assertIsDispatchThread();
    hideLastIntentionHint();

    if (editor.getCaretModel().getCaretCount() > 1) return;

    IntentionHintComponent hintComponent = IntentionHintComponent.showIntentionHint(project, file, editor, intentions, false);
    if (hasToRecreate) {
      hintComponent.recreate();
    }
    myLastIntentionHint = hintComponent;
  }

  void hideLastIntentionHint() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    IntentionHintComponent hint = myLastIntentionHint;
    if (hint != null && hint.isVisible()) {
      hint.hide();
      myLastIntentionHint = null;
    }
  }

  @Nullable
  public IntentionHintComponent getLastIntentionHint() {
    return myLastIntentionHint;
  }

  @Nullable
  @Override
  public Element getState() {
    Element state = new Element("state");
    if (myDisabledHintsFiles.isEmpty()) {
      return state;
    }

    List<String> array = new SmartList<>();
    for (VirtualFile file : myDisabledHintsFiles) {
      if (file.isValid()) {
        array.add(file.getUrl());
      }
    }

    if (!array.isEmpty()) {
      Collections.sort(array);

      Element disableHintsElement = new Element(DISABLE_HINTS_TAG);
      state.addContent(disableHintsElement);
      for (String url : array) {
        disableHintsElement.addContent(new Element(FILE_TAG).setAttribute(URL_ATT, url));
      }
    }
    return state;
  }

  @Override
  public void loadState(Element state) {
    myDisabledHintsFiles.clear();

    Element element = state.getChild(DISABLE_HINTS_TAG);
    if (element != null) {
      for (Element e : element.getChildren(FILE_TAG)) {
        String url = e.getAttributeValue(URL_ATT);
        if (url != null) {
          VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
          if (file != null) {
            myDisabledHintsFiles.add(file);
          }
        }
      }
    }
  }

  private final Runnable submitPassesRunnable = new Runnable() {
    @Override
    public void run() {
      PassExecutorService.log(getUpdateProgress(), null, "Update Runnable. myUpdateByTimerEnabled:", myUpdateByTimerEnabled, " something disposed:",
                              PowerSaveMode.isEnabled() || myDisposed || !myProject.isInitialized(), " activeEditors:", myProject.isDisposed() ? null : getSelectedEditors());
      if (!myUpdateByTimerEnabled) return;
      if (myDisposed) return;
      ApplicationManager.getApplication().assertIsDispatchThread();

      final Collection<FileEditor> activeEditors = getSelectedEditors();
      if (activeEditors.isEmpty()) return;

      if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
        // makes no sense to start from within write action, will cancel anyway
        // we'll restart when the write action finish
        return;
      }
      final PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)myPsiDocumentManager;
      if (documentManager.hasUncommitedDocuments()) {
        documentManager.cancelAndRunWhenAllCommitted("restart daemon when all committed", this);
        return;
      }
      if (RefResolveService.ENABLED && !RefResolveService.getInstance(myProject).isUpToDate() && RefResolveService.getInstance(myProject).getQueueSize() == 1) {
        return; // if the user have just typed in something, wait until the file is re-resolved
        // (or else it will blink like crazy since unused symbols calculation depends on resolve service)
      }

      Map<FileEditor, HighlightingPass[]> passes = new THashMap<>(activeEditors.size());
      for (FileEditor fileEditor : activeEditors) {
        BackgroundEditorHighlighter highlighter = fileEditor.getBackgroundHighlighter();
        if (highlighter != null) {
          HighlightingPass[] highlightingPasses = highlighter.createPassesForEditor();
          passes.put(fileEditor, highlightingPasses);
        }
      }
      // cancel all after calling createPasses() since there are perverts {@link com.intellij.util.xml.ui.DomUIFactoryImpl} who are changing PSI there
      cancelUpdateProgress(true, "Cancel by alarm");
      myUpdateRunnableFuture.cancel(false);
      DaemonProgressIndicator progress = createUpdateProgress();
      myPassExecutorService.submitPasses(passes, progress);
    }
  };

  // made this class static and fields cleareable to avoid leaks when this object stuck in invokeLater queue
  private static class UpdateRunnable implements Runnable {
    private Project myProject;

    private UpdateRunnable(@Nonnull Project project) {
      myProject = project;
    }

    @Override
    public void run() {
      ApplicationManager.getApplication().assertIsDispatchThread();
      Project project = myProject;
      DaemonCodeAnalyzerImpl daemonCodeAnalyzer;
      if (project == null ||
          !project.isInitialized() ||
          project.isDisposed() ||
          PowerSaveMode.isEnabled() ||
          (daemonCodeAnalyzer = (DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(project)).myDisposed) {
        return;
      }

      // wait for heavy processing to stop, re-schedule daemon but not too soon
      if (HeavyProcessLatch.INSTANCE.isRunning()) {
        HeavyProcessLatch.INSTANCE.executeOutOfHeavyProcess(() -> daemonCodeAnalyzer.stopProcess(true, "re-scheduled to execute after heavy processing finished"));
        return;
      }
      Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();

      if (activeEditor == null) {
        AutoPopupController.runTransactionWithEverythingCommitted(project, daemonCodeAnalyzer.submitPassesRunnable);
      }
      else {
        ((PsiDocumentManagerBase)PsiDocumentManager.getInstance(project)).cancelAndRunWhenAllCommitted("start daemon when all committed", daemonCodeAnalyzer.submitPassesRunnable);
      }
    }

    private void clearFieldsOnDispose() {
      myProject = null;
    }
  }

  @Nonnull
  private synchronized DaemonProgressIndicator createUpdateProgress() {
    DaemonProgressIndicator old = myUpdateProgress;
    if (!old.isCanceled()) {
      old.cancel();
    }
    DaemonProgressIndicator progress = new DaemonProgressIndicator() {
      @Override
      public void stopIfRunning() {
        super.stopIfRunning();
        myProject.getMessageBus().syncPublisher(DAEMON_EVENT_TOPIC).daemonFinished();
      }
    };
    progress.setModalityProgress(null);
    progress.start();
    myUpdateProgress = progress;
    return progress;
  }

  @Override
  public void autoImportReferenceAtCursor(@Nonnull Editor editor, @Nonnull PsiFile file) {
    for (ReferenceImporter importer : Extensions.getExtensions(ReferenceImporter.EP_NAME)) {
      if (importer.autoImportReferenceAtCursor(editor, file)) break;
    }
  }

  @TestOnly
  @Nonnull
  synchronized DaemonProgressIndicator getUpdateProgress() {
    return myUpdateProgress;
  }

  @Nonnull
  private Collection<FileEditor> getSelectedEditors() {
    ApplicationManager.getApplication().assertIsDispatchThread();

    // Editors in modal context
    List<Editor> editors = getActiveEditors();

    Collection<FileEditor> activeTextEditors = new THashSet<>(editors.size());
    for (Editor editor : editors) {
      if (editor.isDisposed()) continue;
      TextEditor textEditor = TextEditorProvider.getInstance().getTextEditor(editor);
      activeTextEditors.add(textEditor);
    }
    if (ApplicationManager.getApplication().getCurrentModalityState() != ModalityState.NON_MODAL) {
      return activeTextEditors;
    }

    // Editors in tabs.
    Collection<FileEditor> result = new THashSet<>();
    Collection<VirtualFile> files = new THashSet<>(activeTextEditors.size());
    final FileEditor[] tabEditors = FileEditorManager.getInstance(myProject).getSelectedEditors();
    for (FileEditor tabEditor : tabEditors) {
      if (!tabEditor.isValid()) continue;
      VirtualFile file = ((FileEditorManagerEx)FileEditorManager.getInstance(myProject)).getFile(tabEditor);
      if (file != null) {
        files.add(file);
      }
      result.add(tabEditor);
    }
    // do not duplicate documents
    for (FileEditor fileEditor : activeTextEditors) {
      VirtualFile file = ((FileEditorManagerEx)FileEditorManager.getInstance(myProject)).getFile(fileEditor);
      if (file != null && files.contains(file)) continue;
      result.add(fileEditor);
    }
    return result;
  }

  @Nonnull
  private List<Editor> getActiveEditors() {
    return myEditorTracker.getActiveEditors();
  }

  @TestOnly
  private static void wrap(@Nonnull ThrowableRunnable runnable) {
    try {
      runnable.run();
    }
    catch (RuntimeException | Error e) {
      throw e;
    }
    catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }
}
