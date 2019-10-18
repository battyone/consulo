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
package com.intellij.openapi.fileTypes.impl;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.ide.highlighter.custom.SyntaxTable;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.*;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.fileTypes.ex.*;
import com.intellij.openapi.options.BaseSchemeProcessor;
import com.intellij.openapi.options.SchemesManager;
import com.intellij.openapi.options.SchemesManagerFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.ByteSequence;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.text.StringUtilRt;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.FileAttribute;
import com.intellij.openapi.vfs.newvfs.FileSystemInterface;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.impl.StubVirtualFile;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.GuiUtils;
import com.intellij.util.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.BoundedTaskExecutor;
import com.intellij.util.containers.ConcurrentPackedBitsArray;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.URLUtil;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import consulo.logging.Logger;
import consulo.util.ApplicationPropertiesComponent;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.ide.PooledThreadExecutor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
@State(name = "FileTypeManager", storages = @Storage("filetypes.xml"), additionalExportFile = FileTypeManagerImpl.FILE_SPEC)
public class FileTypeManagerImpl extends FileTypeManagerEx implements PersistentStateComponent<Element>, Disposable {
  private static final Logger LOG = Logger.getInstance(FileTypeManagerImpl.class);

  // You must update all existing default configurations accordingly
  private static final int VERSION = 17;
  private static final ThreadLocal<Pair<VirtualFile, FileType>> FILE_TYPE_FIXED_TEMPORARILY = new ThreadLocal<>();

  // cached auto-detected file type. If the file was auto-detected as plain text or binary
  // then the value is null and AUTO_DETECTED_* flags stored in packedFlags are used instead.
  static final Key<FileType> DETECTED_FROM_CONTENT_FILE_TYPE_KEY = Key.create("DETECTED_FROM_CONTENT_FILE_TYPE_KEY");
  private static final int DETECT_BUFFER_SIZE = 8192; // the number of bytes to read from the file to feed to the file type detector

  // must be sorted
  private static final String DEFAULT_IGNORED =
          "*.hprof;*.pyc;*.pyo;*.rbc;*.yarb;*~;.DS_Store;.git;.hg;.svn;CVS;RCS;SCCS;__pycache__;_svn;rcs;vssver.scc;vssver2.scc;";

  static {
    List<String> strings = StringUtil.split(DEFAULT_IGNORED, ";");
    for (int i = 0; i < strings.size(); i++) {
      String string = strings.get(i);
      String prev = i == 0 ? "" : strings.get(i - 1);
      assert prev.compareTo(string) < 0 : "DEFAULT_IGNORED must be sorted, but got: '" + prev + "' >= '" + string + "'";
    }
  }

  private static boolean RE_DETECT_ASYNC;
  private final Set<FileType> myDefaultTypes = new THashSet<>();
  private FileTypeIdentifiableByVirtualFile[] mySpecialFileTypes = FileTypeIdentifiableByVirtualFile.EMPTY_ARRAY;

  private FileTypeAssocTable<FileType> myPatternsTable = new FileTypeAssocTable<>();
  private final IgnoredPatternSet myIgnoredPatterns = new IgnoredPatternSet();
  private final IgnoredFileCache myIgnoredFileCache = new IgnoredFileCache(myIgnoredPatterns);

  private final FileTypeAssocTable<FileType> myInitialAssociations = new FileTypeAssocTable<>();
  private final Map<FileNameMatcher, String> myUnresolvedMappings = new THashMap<>();
  private final Map<FileNameMatcher, Trinity<String, String, Boolean>> myUnresolvedRemovedMappings =
          new THashMap<>();
  /**
   * This will contain removed mappings with "approved" states
   */
  private final Map<FileNameMatcher, Pair<FileType, Boolean>> myRemovedMappings = new THashMap<>();

  @NonNls
  private static final String ELEMENT_FILETYPE = "filetype";
  @NonNls
  private static final String ELEMENT_IGNORE_FILES = "ignoreFiles";
  @NonNls
  private static final String ATTRIBUTE_LIST = "list";

  @NonNls
  private static final String ATTRIBUTE_VERSION = "version";
  @NonNls
  private static final String ATTRIBUTE_NAME = "name";
  @NonNls
  private static final String ATTRIBUTE_DESCRIPTION = "description";

  private static class StandardFileType {
    @Nonnull
    private final FileType fileType;
    @Nonnull
    private final List<FileNameMatcher> matchers;

    private StandardFileType(@Nonnull FileType fileType, @Nonnull List<FileNameMatcher> matchers) {
      this.fileType = fileType;
      this.matchers = matchers;
    }
  }

  private final MessageBus myMessageBus;
  private final Map<String, StandardFileType> myStandardFileTypes = new LinkedHashMap<>();
  @NonNls
  private static final String[] FILE_TYPES_WITH_PREDEFINED_EXTENSIONS = {"JSP", "JSPX", "DTD", "HTML", "Properties", "XHTML"};
  private final SchemesManager<FileType, AbstractFileType> mySchemesManager;
  @NonNls
  static final String FILE_SPEC = StoragePathMacros.ROOT_CONFIG + "/filetypes";

  // these flags are stored in 'packedFlags' as chunks of four bits
  private static final byte AUTO_DETECTED_AS_TEXT_MASK = 1 << 0;     // set if the file was auto-detected as text
  private static final byte AUTO_DETECTED_AS_BINARY_MASK = 1 << 1;   // set if the file was auto-detected as binary

  // set if auto-detection was performed for this file.
  // if some detector returned some custom file type, it's stored in DETECTED_FROM_CONTENT_FILE_TYPE_KEY file key.
  // otherwise if auto-detected as text or binary, the result is stored in AUTO_DETECTED_AS_TEXT_MASK|AUTO_DETECTED_AS_BINARY_MASK bits
  private static final byte AUTO_DETECT_WAS_RUN_MASK = 1 << 2;
  private static final byte ATTRIBUTES_WERE_LOADED_MASK = 1 << 3;
  // set if AUTO_* bits above were loaded from the file persistent attributes and saved to packedFlags
  private final ConcurrentPackedBitsArray packedFlags = new ConcurrentPackedBitsArray(4);

  private final AtomicInteger counterAutoDetect = new AtomicInteger();
  private final AtomicLong elapsedAutoDetect = new AtomicLong();

  @Inject
  public FileTypeManagerImpl(Application application, SchemesManagerFactory schemesManagerFactory, ApplicationPropertiesComponent propertiesComponent) {
    int fileTypeChangedCounter = StringUtilRt.parseInt(propertiesComponent.getValue("fileTypeChangedCounter"), 0);
    fileTypeChangedCount = new AtomicInteger(fileTypeChangedCounter);
    autoDetectedAttribute = new FileAttribute("AUTO_DETECTION_CACHE_ATTRIBUTE", fileTypeChangedCounter + getVersionFromDetectors(), true);

    RE_DETECT_ASYNC = !application.isUnitTestMode();

    myMessageBus = application.getMessageBus();
    mySchemesManager = schemesManagerFactory.createSchemesManager(FILE_SPEC, new BaseSchemeProcessor<AbstractFileType>() {
      @Nonnull
      @Override
      public AbstractFileType readScheme(@Nonnull Element element, boolean duringLoad) {
        if (!duringLoad) {
          fireBeforeFileTypesChanged();
        }
        AbstractFileType type = (AbstractFileType)loadFileType(element, false);
        if (!duringLoad) {
          fireFileTypesChanged();
        }
        return type;
      }

      @Nonnull
      @Override
      public State getState(@Nonnull AbstractFileType fileType) {
        if (!shouldSave(fileType)) {
          return State.NON_PERSISTENT;
        }
        if (!myDefaultTypes.contains(fileType)) {
          return State.POSSIBLY_CHANGED;
        }
        return fileType.isModified() ? State.POSSIBLY_CHANGED : State.NON_PERSISTENT;
      }

      @Override
      public Element writeScheme(@Nonnull AbstractFileType fileType) {
        Element root = new Element(ELEMENT_FILETYPE);

        root.setAttribute("binary", String.valueOf(fileType.isBinary()));
        if (!StringUtil.isEmpty(fileType.getDefaultExtension())) {
          root.setAttribute("default_extension", fileType.getDefaultExtension());
        }
        root.setAttribute(ATTRIBUTE_DESCRIPTION, fileType.getDescription());
        root.setAttribute(ATTRIBUTE_NAME, fileType.getName());

        fileType.writeExternal(root);

        Element map = new Element(AbstractFileType.ELEMENT_EXTENSION_MAP);
        writeExtensionsMap(map, fileType, false);
        if (!map.getChildren().isEmpty()) {
          root.addContent(map);
        }
        return root;
      }

      @Override
      public void onSchemeDeleted(@Nonnull final AbstractFileType scheme) {
        GuiUtils.invokeLaterIfNeeded(() -> {
          Application app = ApplicationManager.getApplication();
          app.runWriteAction(() -> fireBeforeFileTypesChanged());
          myPatternsTable.removeAllAssociations(scheme);
          app.runWriteAction(() -> fireFileTypesChanged());
        }, ModalityState.NON_MODAL);
      }
    }, RoamingType.PER_USER);
    myMessageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@Nonnull List<? extends VFileEvent> events) {
        Collection<VirtualFile> files = ContainerUtil.map2Set(events, new Function<VFileEvent, VirtualFile>() {
          @Override
          public VirtualFile fun(VFileEvent event) {
            VirtualFile file = event instanceof VFileCreateEvent ? /* avoid expensive find child here */ null : event.getFile();
            VirtualFile filtered = file != null && wasAutoDetectedBefore(file) && isDetectable(file) ? file : null;
            if (toLog()) {
              log("F: after() VFS event " + event +
                  "; filtered file: " + filtered +
                  " (file: " + file +
                  "; wasAutoDetectedBefore(file): " + (file == null ? null : wasAutoDetectedBefore(file)) +
                  "; isDetectable(file): " + (file == null ? null : isDetectable(file)) +
                  "; file.getLength(): " + (file == null ? null : file.getLength()) +
                  "; file.isValid(): " + (file == null ? null : file.isValid()) +
                  "; file.is(VFileProperty.SPECIAL): " + (file == null ? null : file.is(VFileProperty.SPECIAL)) +
                  "; packedFlags.get(id): " + (file instanceof VirtualFileWithId ? readableFlags(packedFlags.get(((VirtualFileWithId)file).getId())) : null) +
                  "; file.getFileSystem():" + (file == null ? null : file.getFileSystem()) + ")");
            }
            return filtered;
          }
        });
        files.remove(null);
        if (toLog()) {
          log("F: after() VFS events: " + events + "; files: " + files);
        }
        if (!files.isEmpty() && RE_DETECT_ASYNC) {
          if (toLog()) {
            log("F: after() queued to redetect: " + files);
          }

          if (filesToRedetect.addAll(files)) {
            awakeReDetectExecutor();
          }
        }
      }
    });

    //noinspection SpellCheckingInspection
    myIgnoredPatterns.setIgnoreMasks(DEFAULT_IGNORED);

    // this should be done BEFORE reading state
    initStandardFileTypes();
  }

  @VisibleForTesting
  void initStandardFileTypes() {
    FileTypeConsumer consumer = new FileTypeConsumer() {
      @Override
      public void consume(@Nonnull FileType fileType) {
        register(fileType, parse(fileType.getDefaultExtension()));
      }

      @Override
      public void consume(@Nonnull final FileType fileType, String extensions) {
        register(fileType, parse(extensions));
      }

      @Override
      public void consume(@Nonnull final FileType fileType, @Nonnull final FileNameMatcher... matchers) {
        register(fileType, new ArrayList<>(Arrays.asList(matchers)));
      }

      @Override
      public FileType getStandardFileTypeByName(@Nonnull final String name) {
        final StandardFileType type = myStandardFileTypes.get(name);
        return type != null ? type.fileType : null;
      }

      private void register(@Nonnull FileType fileType, @Nonnull List<FileNameMatcher> fileNameMatchers) {
        final StandardFileType type = myStandardFileTypes.get(fileType.getId());
        if (type != null) {
          type.matchers.addAll(fileNameMatchers);
        }
        else {
          myStandardFileTypes.put(fileType.getId(), new StandardFileType(fileType, fileNameMatchers));
        }
      }
    };

    for (FileTypeFactory factory : FileTypeFactory.FILE_TYPE_FACTORY_EP.getExtensions()) {
      try {
        factory.createFileTypes(consumer);
      }
      catch (Throwable e) {
        PluginManager.handleComponentError(e, factory.getClass().getName(), null);
      }
    }
    for (StandardFileType pair : myStandardFileTypes.values()) {
      registerFileTypeWithoutNotification(pair.fileType, pair.matchers, true);
    }

    try {
      URL defaultFileTypesUrl = FileTypeManagerImpl.class.getResource("/defaultFileTypes.xml");
      if (defaultFileTypesUrl != null) {
        Element defaultFileTypesElement = JDOMUtil.load(URLUtil.openStream(defaultFileTypesUrl));
        for (Element e : defaultFileTypesElement.getChildren()) {
          //noinspection SpellCheckingInspection
          if ("filetypes".equals(e.getName())) {
            for (Element element : e.getChildren(ELEMENT_FILETYPE)) {
              loadFileType(element, true);
            }
          }
          else if (AbstractFileType.ELEMENT_EXTENSION_MAP.equals(e.getName())) {
            readGlobalMappings(e);
          }
        }
      }
    }
    catch (Exception e) {
      LOG.error(e);
    }
  }

  boolean toLog;

  private boolean toLog() {
    return toLog;
  }

  private static void log(String message) {
    System.out.println(message + " - " + Thread.currentThread());
  }

  private final Executor reDetectExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("FileTypeManager Redetect Pool", PooledThreadExecutor.INSTANCE, 1, this);
  private final BlockingQueue<VirtualFile> filesToRedetect = new LinkedBlockingDeque<>();

  private void awakeReDetectExecutor() {
    reDetectExecutor.execute(new Runnable() {
      private static final int CHUNK = 10;

      @Override
      public void run() {
        List<VirtualFile> files = new ArrayList<>();
        int drained = filesToRedetect.drainTo(files, CHUNK);
        reDetect(files);
        if (drained == CHUNK) {
          awakeReDetectExecutor();
        }
      }
    });
  }

  @TestOnly
  public void drainReDetectQueue() {
    try {
      ((BoundedTaskExecutor)reDetectExecutor).waitAllTasksExecuted(1, TimeUnit.MINUTES);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @TestOnly
  @Nonnull
  Collection<VirtualFile> dumpReDetectQueue() {
    return new ArrayList<>(filesToRedetect);
  }

  @TestOnly
  static void reDetectAsync(boolean enable) {
    RE_DETECT_ASYNC = enable;
  }

  private void reDetect(@Nonnull Collection<VirtualFile> files) {
    final Collection<VirtualFile> changed = new ArrayList<>();
    for (VirtualFile file : files) {
      boolean shouldRedetect = wasAutoDetectedBefore(file) && isDetectable(file);
      if (toLog()) {
        log("F: reDetect(" + file.getName() + ") " + file.getName() + "; shouldRedetect: " + shouldRedetect);
      }
      if (shouldRedetect) {
        int id = ((VirtualFileWithId)file).getId();
        long flags = packedFlags.get(id);
        FileType before = ObjectUtils.notNull(textOrBinaryFromCachedFlags(flags),
                                              ObjectUtils.notNull(file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY), PlainTextFileType.INSTANCE));

        FileType after = getOrDetectByFile(file);

        if (toLog()) {
          log("F: reDetect(" +
              file.getName() +
              ") prepare to redetect. flags: " +
              readableFlags(flags) +
              "; beforeType: " +
              before.getName() +
              "; afterByFileType: " +
              (after == null ? null : after.getName()));
        }

        if (after == null) {
          after = detectFromContentAndCache(file);
        }
        else {
          // back to standard file type
          // detected by conventional methods, no need to run detect-from-content
          file.putUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY, null);
          flags = 0;
          packedFlags.set(id, flags);
        }
        if (toLog()) {
          log("F: reDetect(" +
              file.getName() +
              ") " +
              "before: " +
              before.getName() +
              "; after: " +
              after.getName() +
              "; now getFileType()=" +
              file.getFileType().getName() +
              "; getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY): " +
              file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY));
        }

        if (before != after) {
          changed.add(file);
        }
      }
    }
    if (!changed.isEmpty()) {
      ApplicationManager.getApplication().invokeLater(() -> FileContentUtilCore.reparseFiles(changed), ApplicationManager.getApplication().getDisposed());
    }
  }

  private boolean wasAutoDetectedBefore(@Nonnull VirtualFile file) {
    if (file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY) != null) {
      return true;
    }
    if (file instanceof VirtualFileWithId) {
      int id = Math.abs(((VirtualFileWithId)file).getId());
      // do not re-detect binary files
      return (packedFlags.get(id) & (AUTO_DETECT_WAS_RUN_MASK | AUTO_DETECTED_AS_BINARY_MASK)) == AUTO_DETECT_WAS_RUN_MASK;
    }
    return false;
  }

  @Override
  @Nonnull
  public FileType getStdFileType(@Nonnull @NonNls String name) {
    StandardFileType stdFileType = myStandardFileTypes.get(name);
    return stdFileType != null ? stdFileType.fileType : PlainTextFileType.INSTANCE;
  }

  @Override
  public void afterLoadState() {
    if (!myUnresolvedMappings.isEmpty()) {
      for (StandardFileType pair : myStandardFileTypes.values()) {
        registerReDetectedMappings(pair);
      }
    }
    // Resolve unresolved mappings initialized before certain plugin initialized.
    for (StandardFileType pair : myStandardFileTypes.values()) {
      bindUnresolvedMappings(pair.fileType);
    }

    boolean isAtLeastOneStandardFileTypeHasBeenRead = false;
    for (AbstractFileType fileType : mySchemesManager.loadSchemes()) {
      isAtLeastOneStandardFileTypeHasBeenRead |= myInitialAssociations.hasAssociationsFor(fileType);
    }
    if (isAtLeastOneStandardFileTypeHasBeenRead) {
      restoreStandardFileExtensions();
    }
  }

  @Override
  @Nonnull
  public FileType getFileTypeByFileName(@Nonnull String fileName) {
    return getFileTypeByFileName((CharSequence)fileName);
  }

  @Nonnull
  public FileType getFileTypeByFileName(@Nonnull CharSequence fileName) {
    FileType type = myPatternsTable.findAssociatedFileType(fileName);
    return ObjectUtil.notNull(type, UnknownFileType.INSTANCE);
  }

  public void freezeFileTypeTemporarilyIn(@Nonnull VirtualFile file, @Nonnull Runnable runnable) {
    FileType fileType = file.getFileType();
    Pair<VirtualFile, FileType> old = FILE_TYPE_FIXED_TEMPORARILY.get();
    FILE_TYPE_FIXED_TEMPORARILY.set(Pair.create(file, fileType));
    if (toLog()) {
      log("F: freezeFileTypeTemporarilyIn(" + file.getName() + ") to " + fileType.getName() + " in " + Thread.currentThread());
    }
    try {
      runnable.run();
    }
    finally {
      if (old == null) {
        FILE_TYPE_FIXED_TEMPORARILY.remove();
      }
      else {
        FILE_TYPE_FIXED_TEMPORARILY.set(old);
      }
      if (toLog()) {
        log("F: unfreezeFileType(" + file.getName() + ") in " + Thread.currentThread());
      }
    }
  }

  @Override
  @Nonnull
  public FileType getFileTypeByFile(@Nonnull VirtualFile file) {
    FileType fileType = getOrDetectByFile(file);

    if (fileType == null) {
      fileType = file instanceof StubVirtualFile ? UnknownFileType.INSTANCE : getOrDetectFromContent(file);
    }

    return fileType;
  }

  @Nullable // null means all conventional detect methods returned UnknownFileType.INSTANCE, have to detect from content
  private FileType getOrDetectByFile(@Nonnull VirtualFile file) {
    Pair<VirtualFile, FileType> fixedType = FILE_TYPE_FIXED_TEMPORARILY.get();
    if (fixedType != null && fixedType.getFirst().equals(file)) {
      FileType fileType = fixedType.getSecond();
      if (toLog()) {
        log("F: getOrDetectByFile(" + file.getName() + ") was frozen to " + fileType.getId() + " in " + Thread.currentThread());
      }
      return fileType;
    }

    if (file instanceof LightVirtualFile) {
      FileType fileType = ((LightVirtualFile)file).getAssignedFileType();
      if (fileType != null) {
        return fileType;
      }
    }

    for (FileTypeIdentifiableByVirtualFile type : mySpecialFileTypes) {
      if (type.isMyFileType(file)) {
        if (toLog()) {
          log("F: getOrDetectByFile(" + file.getName() + "): Special file type: " + type.getId());
        }
        return type;
      }
    }

    FileType fileType = getFileTypeByFileName(file.getNameSequence());
    if (fileType == UnknownFileType.INSTANCE) {
      fileType = null;
    }
    if (toLog()) {
      log("F: getOrDetectByFile(" + file.getName() + ") By name file type: " + (fileType == null ? null : fileType.getId()));
    }
    return fileType;
  }

  @Nonnull
  private FileType getOrDetectFromContent(@Nonnull VirtualFile file) {
    if (!isDetectable(file)) return UnknownFileType.INSTANCE;
    if (file instanceof VirtualFileWithId) {
      int id = ((VirtualFileWithId)file).getId();
      if (id < 0) return UnknownFileType.INSTANCE;

      long flags = packedFlags.get(id);
      if (!BitUtil.isSet(flags, ATTRIBUTES_WERE_LOADED_MASK)) {
        flags = readFlagsFromCache(file);
        flags = BitUtil.set(flags, ATTRIBUTES_WERE_LOADED_MASK, true);

        packedFlags.set(id, flags);
        if (toLog()) {
          log("F: getOrDetectFromContent(" + file.getName() + "): readFlagsFromCache() = " + readableFlags(flags));
        }
      }
      boolean autoDetectWasRun = BitUtil.isSet(flags, AUTO_DETECT_WAS_RUN_MASK);
      if (autoDetectWasRun) {
        FileType type = textOrBinaryFromCachedFlags(flags);
        if (toLog()) {
          log("F: getOrDetectFromContent(" +
              file.getName() +
              "):" +
              " cached type = " +
              (type == null ? null : type.getId()) +
              "; packedFlags.get(id):" +
              readableFlags(flags) +
              "; getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY): " +
              file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY));
        }
        if (type != null) {
          return type;
        }
      }
    }
    FileType fileType = file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY);
    if (toLog()) {
      log("F: getOrDetectFromContent(" +
          file.getName() +
          "): " +
          "getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY) = " +
          (fileType == null ? null : fileType.getId()));
    }
    if (fileType == null) {
      // run autodetection
      fileType = detectFromContentAndCache(file);
    }

    if (toLog()) {
      log("F: getOrDetectFromContent(" + file.getName() + "): getFileType after detect run = " + fileType.getId());
    }

    return fileType;
  }

  private static String readableFlags(long flags) {
    String result = "";
    if (BitUtil.isSet(flags, ATTRIBUTES_WERE_LOADED_MASK)) result += (result.isEmpty() ? "" : " | ") + "ATTRIBUTES_WERE_LOADED_MASK";
    if (BitUtil.isSet(flags, AUTO_DETECT_WAS_RUN_MASK)) result += (result.isEmpty() ? "" : " | ") + "AUTO_DETECT_WAS_RUN_MASK";
    if (BitUtil.isSet(flags, AUTO_DETECTED_AS_BINARY_MASK)) result += (result.isEmpty() ? "" : " | ") + "AUTO_DETECTED_AS_BINARY_MASK";
    if (BitUtil.isSet(flags, AUTO_DETECTED_AS_TEXT_MASK)) result += (result.isEmpty() ? "" : " | ") + "AUTO_DETECTED_AS_TEXT_MASK";
    return result;
  }

  private volatile FileAttribute autoDetectedAttribute;

  // read auto-detection flags from the persistent FS file attributes. If file attributes are absent, return 0 for flags
  // returns three bits value for AUTO_DETECTED_AS_TEXT_MASK, AUTO_DETECTED_AS_BINARY_MASK and AUTO_DETECT_WAS_RUN_MASK bits
  // protected for Upsource
  protected byte readFlagsFromCache(@Nonnull VirtualFile file) {
    DataInputStream stream = autoDetectedAttribute.readAttribute(file);
    boolean wasAutoDetectRun = false;
    byte status = 0;
    try {
      try {
        status = stream == null ? 0 : stream.readByte();
        wasAutoDetectRun = stream != null;
      }
      finally {
        if (stream != null) {
          stream.close();
        }
      }
    }
    catch (IOException ignored) {
    }
    status = BitUtil.set(status, AUTO_DETECT_WAS_RUN_MASK, wasAutoDetectRun);

    return (byte)(status & (AUTO_DETECTED_AS_TEXT_MASK | AUTO_DETECTED_AS_BINARY_MASK | AUTO_DETECT_WAS_RUN_MASK));
  }

  // store auto-detection flags to the persistent FS file attributes
  // writes AUTO_DETECTED_AS_TEXT_MASK, AUTO_DETECTED_AS_BINARY_MASK bits only
  // protected for Upsource
  protected void writeFlagsToCache(@Nonnull VirtualFile file, int flags) {
    DataOutputStream stream = autoDetectedAttribute.writeAttribute(file);
    try {
      try {
        stream.writeByte(flags & (AUTO_DETECTED_AS_TEXT_MASK | AUTO_DETECTED_AS_BINARY_MASK));
      }
      finally {
        stream.close();
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  void clearCaches() {
    packedFlags.clear();
    if (toLog()) {
      log("F: clearCaches()");
    }
  }

  private void clearPersistentAttributes() {
    int count = fileTypeChangedCount.incrementAndGet();
    autoDetectedAttribute = autoDetectedAttribute.newVersion(count + getVersionFromDetectors());
    PropertiesComponent.getInstance().setValue("fileTypeChangedCounter", Integer.toString(count));
    if (toLog()) {
      log("F: clearPersistentAttributes()");
    }
  }

  @Nullable //null means the file was not auto-detected as text/binary
  private static FileType textOrBinaryFromCachedFlags(long flags) {
    return BitUtil.isSet(flags, AUTO_DETECTED_AS_TEXT_MASK)
           ? PlainTextFileType.INSTANCE
           : BitUtil.isSet(flags, AUTO_DETECTED_AS_BINARY_MASK) ? UnknownFileType.INSTANCE : null;
  }

  @Nonnull
  @Override
  @Deprecated
  public FileType detectFileTypeFromContent(@Nonnull VirtualFile file) {
    return file.getFileType();
  }

  private void cacheAutoDetectedFileType(@Nonnull VirtualFile file, @Nonnull FileType fileType) {
    boolean wasAutodetectedAsText = fileType == PlainTextFileType.INSTANCE;
    boolean wasAutodetectedAsBinary = fileType == UnknownFileType.INSTANCE;

    int flags = BitUtil.set(0, AUTO_DETECTED_AS_TEXT_MASK, wasAutodetectedAsText);
    flags = BitUtil.set(flags, AUTO_DETECTED_AS_BINARY_MASK, wasAutodetectedAsBinary);
    writeFlagsToCache(file, flags);
    if (file instanceof VirtualFileWithId) {
      int id = Math.abs(((VirtualFileWithId)file).getId());
      flags = BitUtil.set(flags, AUTO_DETECT_WAS_RUN_MASK, true);
      flags = BitUtil.set(flags, ATTRIBUTES_WERE_LOADED_MASK, true);
      packedFlags.set(id, flags);

      if (wasAutodetectedAsText || wasAutodetectedAsBinary) {
        file.putUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY, null);
        if (toLog()) {
          log("F: cacheAutoDetectedFileType(" +
              file.getName() +
              ") " +
              "cached to " +
              fileType.getId() +
              " flags = " +
              readableFlags(flags) +
              "; getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY): " +
              file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY));
        }
        return;
      }
    }
    file.putUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY, fileType);
    if (toLog()) {
      log("F: cacheAutoDetectedFileType(" +
          file.getName() +
          ") " +
          "cached to " +
          fileType.getId() +
          " flags = " +
          readableFlags(flags) +
          "; getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY): " +
          file.getUserData(DETECTED_FROM_CONTENT_FILE_TYPE_KEY));
    }
  }

  @Override
  public FileType findFileTypeByName(@Nonnull String fileTypeName) {
    FileType type = getStdFileType(fileTypeName);
    // TODO: Abstract file types are not std one, so need to be restored specially,
    // currently there are 6 of them and restoration does not happen very often so just iteration is enough
    if (type == PlainTextFileType.INSTANCE && !fileTypeName.equals(type.getId())) {
      for (FileType fileType : mySchemesManager.getAllSchemes()) {
        if (fileTypeName.equals(fileType.getId())) {
          return fileType;
        }
      }
    }
    return type;
  }

  private static boolean isDetectable(@Nonnull final VirtualFile file) {
    if (file.isDirectory() || !file.isValid() || file.is(VFileProperty.SPECIAL) || file.getLength() == 0) {
      // for empty file there is still hope its type will change
      return false;
    }
    return file.getFileSystem() instanceof FileSystemInterface && !SingleRootFileViewProvider.isTooLargeForContentLoading(file);
  }

  private boolean processFirstBytes(@Nonnull final InputStream stream, final int length, @Nonnull Processor<ByteSequence> processor) throws IOException {
    final byte[] bytes = FileUtilRt.getThreadLocalBuffer();
    assert bytes.length >= length : "Cannot process more than " + bytes.length + " in one call, requested:" + length;

    int n = stream.read(bytes, 0, length);
    if (n <= 0) {
      // maybe locked because someone else is writing to it
      // repeat inside read action to guarantee all writes are finished
      if (toLog()) {
        log("F: processFirstBytes(): inputStream.read() returned " + n + "; retrying with read action. stream=" + streamInfo(stream));
      }
      n = ApplicationManager.getApplication().runReadAction((ThrowableComputable<Integer, IOException>)() -> stream.read(bytes, 0, length));
      if (toLog()) {
        log("F: processFirstBytes(): under read action inputStream.read() returned " + n + "; stream=" + streamInfo(stream));
      }
      if (n <= 0) {
        return false;
      }
    }

    return processor.process(new ByteSequence(bytes, 0, n));
  }

  @Nonnull
  private FileType detectFromContentAndCache(@Nonnull final VirtualFile file) {
    long start = System.currentTimeMillis();
    try {
      final Ref<FileType> result = new Ref<>(UnknownFileType.INSTANCE);

      boolean excluded = false;
      /*
        Disable check - it's provide stackoverflow exception when file inside library order
        https://github.com/consulo/consulo/issues/275
      */
      /*Project project = ProjectLocator.getInstance().guessProjectForFile(file);
      if (project != null) {
        ProjectFileIndex fileIndex = ProjectFileIndex.SERVICE.getInstance(project);
        if (fileIndex.isExcluded(file)) {
          excluded = true;
        }
      } */

      if(!excluded) {
        final InputStream inputStream = ((FileSystemInterface)file.getFileSystem()).getInputStream(file);
        if (toLog()) {
          log("F: detectFromContentAndCache(" + file.getName() + "):" + " inputStream=" + streamInfo(inputStream));
        }

        boolean r = false;
        try {
          r = processFirstBytes(inputStream, DETECT_BUFFER_SIZE, byteSequence -> {
            boolean isText = guessIfText(file, byteSequence);
            CharSequence text;
            if (isText) {
              byte[] bytes = Arrays.copyOf(byteSequence.getBytes(), byteSequence.getLength());
              text = LoadTextUtil.getTextByBinaryPresentation(bytes, file, true, true, UnknownFileType.INSTANCE);
            }
            else {
              text = null;
            }

            List<FileTypeDetector> detectors = FileTypeDetector.EP_NAME.getExtensionList();
            if (FileTypeManagerImpl.this.toLog()) {
              log("F: detectFromContentAndCache.processFirstBytes(" +
                  file.getName() +
                  "): " +
                  "byteSequence.length=" +
                  byteSequence.getLength() +
                  "; isText=" +
                  isText +
                  "; text='" +
                  (text == null ? null : StringUtil.first(text, 100, true)) +
                  "', detectors=" + detectors);
            }
            FileType detected = null;
            for (FileTypeDetector detector : detectors) {
              try {
                detected = detector.detect(file, byteSequence, text);
              }
              catch (Exception e) {
                LOG.error("Detector " + detector + " (" + detector.getClass() + ") exception occurred:", e);
              }
              if (detected != null) {
                if (FileTypeManagerImpl.this.toLog()) {
                  log("F: detectFromContentAndCache.processFirstBytes(" + file.getName() + "): " + "detector " + detector + " type as " + detected.getId());
                }
                break;
              }
            }

            if (detected == null) {
              detected = isText ? PlainTextFileType.INSTANCE : UnknownFileType.INSTANCE;
              if (FileTypeManagerImpl.this.toLog()) {
                log("F: detectFromContentAndCache.processFirstBytes(" +
                    file.getName() +
                    "): " +
                    "no detector was able to detect. assigned " +
                    detected.getId());
              }
            }
            result.set(detected);
            return true;
          });
        }
        finally {
          if (toLog()) {
            byte[] buffer = new byte[50];
            InputStream newStream = ((FileSystemInterface)file.getFileSystem()).getInputStream(file);
            int n = newStream.read(buffer, 0, buffer.length);
            log("F: detectFromContentAndCache(" +
                file.getName() +
                "): " +
                "; result: " +
                result.get().getId() +
                "; processor ret: " +
                r +
                "; stream: " +
                streamInfo(inputStream) +
                "; newStream: " +
                streamInfo(newStream) +
                "; read: " +
                n +
                "; buffer: " +
                Arrays.toString(buffer));
            newStream.close();
          }
          inputStream.close();
        }
      }

      FileType fileType = result.get();

      if (LOG.isDebugEnabled()) {
        LOG.debug(file + "; type=" + fileType.getDescription() + "; " + counterAutoDetect);
      }

      cacheAutoDetectedFileType(file, fileType);
      counterAutoDetect.incrementAndGet();
      long elapsed = System.currentTimeMillis() - start;
      elapsedAutoDetect.addAndGet(elapsed);

      return fileType;
    }
    catch (IOException ignored) {
      return UnknownFileType.INSTANCE; // return unknown, do not cache
    }
  }

  // for diagnostics
  private static Object streamInfo(InputStream stream) throws IOException {
    if (stream instanceof BufferedInputStream) {
      InputStream in = ReflectionUtil.getField(stream.getClass(), stream, InputStream.class, "in");
      byte[] buf = ReflectionUtil.getField(stream.getClass(), stream, byte[].class, "buf");
      int count = ReflectionUtil.getField(stream.getClass(), stream, int.class, "count");
      int pos = ReflectionUtil.getField(stream.getClass(), stream, int.class, "pos");

      return "BufferedInputStream(buf=" +
             (buf == null ? null : Arrays.toString(Arrays.copyOf(buf, count))) +
             ", count=" +
             count +
             ", pos=" +
             pos +
             ", in=" +
             streamInfo(in) +
             ")";
    }
    if (stream instanceof FileInputStream) {
      String path = ReflectionUtil.getField(stream.getClass(), stream, String.class, "path");
      FileChannel channel = ReflectionUtil.getField(stream.getClass(), stream, FileChannel.class, "channel");
      boolean closed = ReflectionUtil.getField(stream.getClass(), stream, boolean.class, "closed");
      int available = stream.available();
      File file = new File(path);
      return "FileInputStream(path=" +
             path +
             ", available=" +
             available +
             ", closed=" +
             closed +
             ", channel=" +
             channel +
             ", channel.size=" +
             (channel == null ? null : channel.size()) +
             ", file.exists=" +
             file.exists() +
             ", file.content='" +
             FileUtil.loadFile(file) +
             "')";
    }
    return stream;
  }

  private static boolean guessIfText(@Nonnull VirtualFile file, @Nonnull ByteSequence byteSequence) {
    byte[] bytes = byteSequence.getBytes();
    Trinity<Charset, CharsetToolkit.GuessedEncoding, byte[]> guessed = LoadTextUtil.guessFromContent(file, bytes, byteSequence.getLength());
    if (guessed == null) return false;
    file.setBOM(guessed.third);
    if (guessed.first != null) {
      // charset was detected unambiguously
      return true;
    }
    // use wild guess
    CharsetToolkit.GuessedEncoding guess = guessed.second;
    return guess != null && (guess == CharsetToolkit.GuessedEncoding.VALID_UTF8 || guess == CharsetToolkit.GuessedEncoding.SEVEN_BIT);
  }

  @Override
  public boolean isFileOfType(@Nonnull VirtualFile file, @Nonnull FileType type) {
    if (type instanceof FileTypeIdentifiableByVirtualFile) {
      return ((FileTypeIdentifiableByVirtualFile)type).isMyFileType(file);
    }

    return getFileTypeByFileName(file.getNameSequence()) == type;
  }

  @Override
  @Nonnull
  public FileType getFileTypeByExtension(@Nonnull String extension) {
    return getFileTypeByFileName("IntelliJ_IDEA_RULES." + extension);
  }

  @Override
  public void registerFileType(@Nonnull FileType fileType) {
    //noinspection deprecation
    registerFileType(fileType, ArrayUtil.EMPTY_STRING_ARRAY);
  }

  @Override
  public void registerFileType(@Nonnull final FileType type, @Nonnull final List<FileNameMatcher> defaultAssociations) {
    ApplicationManager.getApplication().runWriteAction(() -> {
      FileTypeManagerImpl.this.fireBeforeFileTypesChanged();
      FileTypeManagerImpl.this.registerFileTypeWithoutNotification(type, defaultAssociations, true);
      FileTypeManagerImpl.this.fireFileTypesChanged();
    });
  }

  @Override
  public void unregisterFileType(@Nonnull final FileType fileType) {
    ApplicationManager.getApplication().runWriteAction(() -> {
      FileTypeManagerImpl.this.fireBeforeFileTypesChanged();
      FileTypeManagerImpl.this.unregisterFileTypeWithoutNotification(fileType);
      FileTypeManagerImpl.this.fireFileTypesChanged();
    });
  }

  private void unregisterFileTypeWithoutNotification(@Nonnull FileType fileType) {
    myPatternsTable.removeAllAssociations(fileType);
    mySchemesManager.removeScheme(fileType);
    if (fileType instanceof FileTypeIdentifiableByVirtualFile) {
      final FileTypeIdentifiableByVirtualFile fakeFileType = (FileTypeIdentifiableByVirtualFile)fileType;
      mySpecialFileTypes = ArrayUtil.remove(mySpecialFileTypes, fakeFileType, FileTypeIdentifiableByVirtualFile.ARRAY_FACTORY);
    }
  }

  @Override
  @Nonnull
  public FileType[] getRegisteredFileTypes() {
    Collection<FileType> fileTypes = mySchemesManager.getAllSchemes();
    return fileTypes.toArray(new FileType[fileTypes.size()]);
  }

  @Override
  @Nonnull
  public String getExtension(@Nonnull String fileName) {
    int index = fileName.lastIndexOf('.');
    if (index < 0) return "";
    return fileName.substring(index + 1);
  }

  @Override
  @Nonnull
  public String getIgnoredFilesList() {
    Set<String> masks = myIgnoredPatterns.getIgnoreMasks();
    return masks.isEmpty() ? "" : StringUtil.join(masks, ";") + ";";
  }

  @Override
  public void setIgnoredFilesList(@Nonnull String list) {
    fireBeforeFileTypesChanged();
    myIgnoredFileCache.clearCache();
    myIgnoredPatterns.setIgnoreMasks(list);
    fireFileTypesChanged();
  }

  @Override
  public boolean isIgnoredFilesListEqualToCurrent(@Nonnull String list) {
    Set<String> tempSet = new THashSet<>();
    StringTokenizer tokenizer = new StringTokenizer(list, ";");
    while (tokenizer.hasMoreTokens()) {
      tempSet.add(tokenizer.nextToken());
    }
    return tempSet.equals(myIgnoredPatterns.getIgnoreMasks());
  }

  @Override
  public boolean isFileIgnored(@Nonnull CharSequence name) {
    return myIgnoredPatterns.isIgnored(name);
  }

  @Override
  public boolean isFileIgnored(@Nonnull VirtualFile file) {
    return myIgnoredFileCache.isFileIgnored(file);
  }

  @Override
  @Nonnull
  public String[] getAssociatedExtensions(@Nonnull FileType type) {
    //noinspection deprecation
    return myPatternsTable.getAssociatedExtensions(type);
  }

  @Override
  @Nonnull
  public List<FileNameMatcher> getAssociations(@Nonnull FileType type) {
    return myPatternsTable.getAssociations(type);
  }

  @Override
  public void associate(@Nonnull FileType type, @Nonnull FileNameMatcher matcher) {
    associate(type, matcher, true);
  }

  @Override
  public void removeAssociation(@Nonnull FileType type, @Nonnull FileNameMatcher matcher) {
    removeAssociation(type, matcher, true);
  }

  @Override
  public void fireBeforeFileTypesChanged() {
    FileTypeEvent event = new FileTypeEvent(this);
    myMessageBus.syncPublisher(TOPIC).beforeFileTypesChanged(event);
  }

  private final AtomicInteger fileTypeChangedCount;

  @Override
  public void fireFileTypesChanged() {
    clearCaches();
    clearPersistentAttributes();
    myMessageBus.syncPublisher(TOPIC).fileTypesChanged(new FileTypeEvent(this));
  }

  private final Map<FileTypeListener, MessageBusConnection> myAdapters = new HashMap<>();

  @Override
  public void addFileTypeListener(@Nonnull FileTypeListener listener) {
    final MessageBusConnection connection = myMessageBus.connect();
    connection.subscribe(TOPIC, listener);
    myAdapters.put(listener, connection);
  }

  @Override
  public void removeFileTypeListener(@Nonnull FileTypeListener listener) {
    final MessageBusConnection connection = myAdapters.remove(listener);
    if (connection != null) {
      connection.disconnect();
    }
  }

  @Override
  public void loadState(Element state) {
    int savedVersion = StringUtilRt.parseInt(state.getAttributeValue(ATTRIBUTE_VERSION), 0);

    for (Element element : state.getChildren()) {
      if (element.getName().equals(ELEMENT_IGNORE_FILES)) {
        myIgnoredPatterns.setIgnoreMasks(element.getAttributeValue(ATTRIBUTE_LIST));
      }
      else if (AbstractFileType.ELEMENT_EXTENSION_MAP.equals(element.getName())) {
        readGlobalMappings(element);
      }
    }

    if (savedVersion < 4) {
      if (savedVersion == 0) {
        addIgnore(".svn");
      }

      if (savedVersion < 2) {
        restoreStandardFileExtensions();
      }

      addIgnore("*.pyc");
      addIgnore("*.pyo");
      addIgnore(".git");
    }

    if (savedVersion < 5) {
      addIgnore("*.hprof");
    }

    if (savedVersion < 6) {
      addIgnore("_svn");
    }

    if (savedVersion < 7) {
      addIgnore(".hg");
    }

    if (savedVersion < 8) {
      addIgnore("*~");
    }

    if (savedVersion < 9) {
      addIgnore("__pycache__");
    }

    if (savedVersion < 11) {
      addIgnore("*.rbc");
    }

    if (savedVersion < 13) {
      // we want *.lib back since it's an important user artifact for CLion, also for IDEA project itself, since we have some libs.
      unignoreMask("*.lib");
    }

    if (savedVersion < 15) {
      // we want .bundle back, bundler keeps useful data there
      unignoreMask(".bundle");
    }

    if (savedVersion < 16) {
      // we want .tox back to allow users selecting interpreters from it
      unignoreMask(".tox");
    }

    if (savedVersion < 17) {
      addIgnore("*.rbc");
    }

    myIgnoredFileCache.clearCache();

    String counter = JDOMExternalizer.readString(state, "fileTypeChangedCounter");
    if (counter != null) {
      fileTypeChangedCount.set(StringUtilRt.parseInt(counter, 0));
      autoDetectedAttribute = autoDetectedAttribute.newVersion(fileTypeChangedCount.get() + getVersionFromDetectors());
    }
  }

  private void unignoreMask(@Nonnull final String maskToRemove) {
    final Set<String> masks = new LinkedHashSet<>(myIgnoredPatterns.getIgnoreMasks());
    masks.remove(maskToRemove);

    myIgnoredPatterns.clearPatterns();
    for (final String each : masks) {
      myIgnoredPatterns.addIgnoreMask(each);
    }
  }

  private void readGlobalMappings(@Nonnull Element e) {
    for (Pair<FileNameMatcher, String> association : AbstractFileType.readAssociations(e)) {
      FileType type = getFileTypeByName(association.getSecond());
      FileNameMatcher matcher = association.getFirst();
      if (type != null) {
        if (PlainTextFileType.INSTANCE == type) {
          FileType newFileType = myPatternsTable.findAssociatedFileType(matcher);
          if (newFileType != null && newFileType != PlainTextFileType.INSTANCE && newFileType != UnknownFileType.INSTANCE) {
            myRemovedMappings.put(matcher, Pair.create(newFileType, false));
          }
        }
        associate(type, matcher, false);
      }
      else {
        myUnresolvedMappings.put(matcher, association.getSecond());
      }
    }

    List<Trinity<FileNameMatcher, String, Boolean>> removedAssociations = AbstractFileType.readRemovedAssociations(e);
    for (Trinity<FileNameMatcher, String, Boolean> trinity : removedAssociations) {
      FileType type = getFileTypeByName(trinity.getSecond());
      FileNameMatcher matcher = trinity.getFirst();
      if (type != null) {
        removeAssociation(type, matcher, false);
      }
      else {
        myUnresolvedRemovedMappings.put(matcher, Trinity.create(trinity.getSecond(), myUnresolvedMappings.get(matcher), trinity.getThird()));
      }
    }
  }

  private void addIgnore(@NonNls @Nonnull String ignoreMask) {
    myIgnoredPatterns.addIgnoreMask(ignoreMask);
  }

  private void restoreStandardFileExtensions() {
    for (final String name : FILE_TYPES_WITH_PREDEFINED_EXTENSIONS) {
      final StandardFileType stdFileType = myStandardFileTypes.get(name);
      if (stdFileType != null) {
        FileType fileType = stdFileType.fileType;
        for (FileNameMatcher matcher : myPatternsTable.getAssociations(fileType)) {
          FileType defaultFileType = myInitialAssociations.findAssociatedFileType(matcher);
          if (defaultFileType != null && defaultFileType != fileType) {
            removeAssociation(fileType, matcher, false);
            associate(defaultFileType, matcher, false);
          }
        }

        for (FileNameMatcher matcher : myInitialAssociations.getAssociations(fileType)) {
          associate(fileType, matcher, false);
        }
      }
    }
  }

  @Nonnull
  @Override
  public Element getState() {
    Element state = new Element("state");

    Set<String> masks = myIgnoredPatterns.getIgnoreMasks();
    String ignoreFiles;
    if (masks.isEmpty()) {
      ignoreFiles = "";
    }
    else {
      String[] strings = ArrayUtil.toStringArray(masks);
      Arrays.sort(strings);
      ignoreFiles = StringUtil.join(strings, ";") + ";";
    }

    if (!ignoreFiles.equalsIgnoreCase(DEFAULT_IGNORED)) {
      // empty means empty list - we need to distinguish null and empty to apply or not to apply default value
      state.addContent(new Element(ELEMENT_IGNORE_FILES).setAttribute(ATTRIBUTE_LIST, ignoreFiles));
    }

    Element map = new Element(AbstractFileType.ELEMENT_EXTENSION_MAP);

    List<FileType> notExternalizableFileTypes = new ArrayList<>();
    for (FileType type : mySchemesManager.getAllSchemes()) {
      if (!(type instanceof AbstractFileType) || myDefaultTypes.contains(type)) {
        notExternalizableFileTypes.add(type);
      }
    }
    if (!notExternalizableFileTypes.isEmpty()) {
      Collections.sort(notExternalizableFileTypes, (o1, o2) -> o1.getId().compareTo(o2.getId()));
      for (FileType type : notExternalizableFileTypes) {
        writeExtensionsMap(map, type, true);
      }
    }

    if (!myUnresolvedMappings.isEmpty()) {
      FileNameMatcher[] unresolvedMappingKeys = myUnresolvedMappings.keySet().toArray(new FileNameMatcher[myUnresolvedMappings.size()]);
      Arrays.sort(unresolvedMappingKeys, (o1, o2) -> o1.getPresentableString().compareTo(o2.getPresentableString()));

      for (FileNameMatcher fileNameMatcher : unresolvedMappingKeys) {
        Element content = AbstractFileType.writeMapping(myUnresolvedMappings.get(fileNameMatcher), fileNameMatcher, true);
        if (content != null) {
          map.addContent(content);
        }
      }
    }

    if (!map.getChildren().isEmpty()) {
      state.addContent(map);
    }

    if (!state.getChildren().isEmpty()) {
      state.setAttribute(ATTRIBUTE_VERSION, String.valueOf(VERSION));
    }
    return state;
  }

  private void writeExtensionsMap(@Nonnull Element map, @Nonnull FileType type, boolean specifyTypeName) {
    List<FileNameMatcher> associations = myPatternsTable.getAssociations(type);
    Set<FileNameMatcher> defaultAssociations = new THashSet<>(myInitialAssociations.getAssociations(type));

    for (FileNameMatcher matcher : associations) {
      if (defaultAssociations.contains(matcher)) {
        defaultAssociations.remove(matcher);
      }
      else if (shouldSave(type)) {
        Element content = AbstractFileType.writeMapping(type.getId(), matcher, specifyTypeName);
        if (content != null) {
          map.addContent(content);
        }
      }
    }

    for (FileNameMatcher matcher : defaultAssociations) {
      Element content = AbstractFileType.writeRemovedMapping(type, matcher, specifyTypeName, isApproved(matcher));
      if (content != null) {
        map.addContent(content);
      }
    }
  }

  private boolean isApproved(@Nonnull FileNameMatcher matcher) {
    Pair<FileType, Boolean> pair = myRemovedMappings.get(matcher);
    return pair != null && pair.getSecond();
  }

  // -------------------------------------------------------------------------
  // Helper methods
  // -------------------------------------------------------------------------

  @Nullable
  private FileType getFileTypeByName(@Nonnull String name) {
    return mySchemesManager.findSchemeByName(name);
  }

  @Nonnull
  private static List<FileNameMatcher> parse(@Nullable String semicolonDelimited) {
    if (semicolonDelimited == null) {
      return Collections.emptyList();
    }

    StringTokenizer tokenizer = new StringTokenizer(semicolonDelimited, FileTypeConsumer.EXTENSION_DELIMITER, false);
    ArrayList<FileNameMatcher> list = new ArrayList<>();
    while (tokenizer.hasMoreTokens()) {
      list.add(new ExtensionFileNameMatcher(tokenizer.nextToken().trim()));
    }
    return list;
  }

  /**
   * Registers a standard file type. Doesn't notifyListeners any change events.
   */
  private void registerFileTypeWithoutNotification(@Nonnull FileType fileType, @Nonnull List<FileNameMatcher> matchers, boolean addScheme) {
    if (addScheme) {
      mySchemesManager.addNewScheme(fileType, true);
    }
    for (FileNameMatcher matcher : matchers) {
      myPatternsTable.addAssociation(matcher, fileType);
      myInitialAssociations.addAssociation(matcher, fileType);
    }

    if (fileType instanceof FileTypeIdentifiableByVirtualFile) {
      mySpecialFileTypes = ArrayUtil.append(mySpecialFileTypes, (FileTypeIdentifiableByVirtualFile)fileType, FileTypeIdentifiableByVirtualFile.ARRAY_FACTORY);
    }
  }

  private void bindUnresolvedMappings(@Nonnull FileType fileType) {
    for (FileNameMatcher matcher : new THashSet<>(myUnresolvedMappings.keySet())) {
      String name = myUnresolvedMappings.get(matcher);
      if (Comparing.equal(name, fileType.getId())) {
        myPatternsTable.addAssociation(matcher, fileType);
        myUnresolvedMappings.remove(matcher);
      }
    }

    for (FileNameMatcher matcher : new THashSet<>(myUnresolvedRemovedMappings.keySet())) {
      Trinity<String, String, Boolean> trinity = myUnresolvedRemovedMappings.get(matcher);
      if (Comparing.equal(trinity.getFirst(), fileType.getId())) {
        removeAssociation(fileType, matcher, false);
        myUnresolvedRemovedMappings.remove(matcher);
      }
    }
  }

  @Nonnull
  private FileType loadFileType(@Nonnull Element typeElement, boolean isDefault) {
    String fileTypeName = typeElement.getAttributeValue(ATTRIBUTE_NAME);
    String fileTypeDescr = typeElement.getAttributeValue(ATTRIBUTE_DESCRIPTION);
    String iconPath = typeElement.getAttributeValue("icon");

    String extensionsStr = StringUtil.nullize(typeElement.getAttributeValue("extensions"));
    if (isDefault && extensionsStr != null) {
      // todo support wildcards
      extensionsStr = filterAlreadyRegisteredExtensions(extensionsStr);
    }

    FileType type = isDefault ? getFileTypeByName(fileTypeName) : null;
    if (type != null) {
      return type;
    }

    Element element = typeElement.getChild(AbstractFileType.ELEMENT_HIGHLIGHTING);
    if (element == null) {
      for (CustomFileTypeFactory factory : CustomFileTypeFactory.EP_NAME.getExtensions()) {
        type = factory.createFileType(typeElement);
        if (type != null) {
          break;
        }
      }

      if (type == null) {
        type = new UserBinaryFileType();
      }
    }
    else {
      SyntaxTable table = AbstractFileType.readSyntaxTable(element);
      type = new AbstractFileType(table);
      ((AbstractFileType)type).initSupport();
    }

    setFileTypeAttributes((UserFileType)type, fileTypeName, fileTypeDescr, iconPath);
    registerFileTypeWithoutNotification(type, parse(extensionsStr), isDefault);

    if (isDefault) {
      myDefaultTypes.add(type);
      if (type instanceof ExternalizableFileType) {
        ((ExternalizableFileType)type).markDefaultSettings();
      }
    }
    else {
      Element extensions = typeElement.getChild(AbstractFileType.ELEMENT_EXTENSION_MAP);
      if (extensions != null) {
        for (Pair<FileNameMatcher, String> association : AbstractFileType.readAssociations(extensions)) {
          associate(type, association.getFirst(), false);
        }

        for (Trinity<FileNameMatcher, String, Boolean> removedAssociation : AbstractFileType.readRemovedAssociations(extensions)) {
          removeAssociation(type, removedAssociation.getFirst(), false);
        }
      }
    }
    return type;
  }

  @Nullable
  private String filterAlreadyRegisteredExtensions(@Nonnull String semicolonDelimited) {
    StringTokenizer tokenizer = new StringTokenizer(semicolonDelimited, FileTypeConsumer.EXTENSION_DELIMITER, false);
    StringBuilder builder = null;
    while (tokenizer.hasMoreTokens()) {
      String extension = tokenizer.nextToken().trim();
      if (getFileTypeByExtension(extension) == UnknownFileType.INSTANCE) {
        if (builder == null) {
          builder = new StringBuilder();
        }
        else if (builder.length() > 0) {
          builder.append(FileTypeConsumer.EXTENSION_DELIMITER);
        }
        builder.append(extension);
      }
    }
    return builder == null ? null : builder.toString();
  }

  private static void setFileTypeAttributes(@Nonnull UserFileType fileType, @Nullable String name, @Nullable String description, @Nullable String iconPath) {
    if (!StringUtil.isEmptyOrSpaces(iconPath)) {
      fileType.setIcon(IconLoader.getIcon(iconPath));
    }
    if (description != null) {
      fileType.setDescription(description);
    }
    if (name != null) {
      fileType.setName(name);
    }
  }

  private static boolean shouldSave(@Nonnull FileType fileType) {
    return fileType != UnknownFileType.INSTANCE && !fileType.isReadOnly();
  }

  @Nonnull
  FileTypeAssocTable getExtensionMap() {
    return myPatternsTable;
  }

  void setPatternsTable(@Nonnull Set<FileType> fileTypes, @Nonnull FileTypeAssocTable<FileType> assocTable) {
    fireBeforeFileTypesChanged();
    for (FileType existing : getRegisteredFileTypes()) {
      if (!fileTypes.contains(existing)) {
        mySchemesManager.removeScheme(existing);
      }
    }
    for (FileType fileType : fileTypes) {
      mySchemesManager.addNewScheme(fileType, true);
      if (fileType instanceof AbstractFileType) {
        ((AbstractFileType)fileType).initSupport();
      }
    }
    myPatternsTable = assocTable.copy();
    fireFileTypesChanged();
  }

  public void associate(@Nonnull FileType fileType, @Nonnull FileNameMatcher matcher, boolean fireChange) {
    if (!myPatternsTable.isAssociatedWith(fileType, matcher)) {
      if (fireChange) {
        fireBeforeFileTypesChanged();
      }
      myPatternsTable.addAssociation(matcher, fileType);
      if (fireChange) {
        fireFileTypesChanged();
      }
    }
  }

  public void removeAssociation(@Nonnull FileType fileType, @Nonnull FileNameMatcher matcher, boolean fireChange) {
    if (myPatternsTable.isAssociatedWith(fileType, matcher)) {
      if (fireChange) {
        fireBeforeFileTypesChanged();
      }
      myPatternsTable.removeAssociation(matcher, fileType);
      if (fireChange) {
        fireFileTypesChanged();
      }
    }
  }

  @Override
  @Nullable
  public FileType getKnownFileTypeOrAssociate(@Nonnull VirtualFile file) {
    FileType type = file.getFileType();
    if (type == UnknownFileType.INSTANCE) {
      type = FileTypeChooser.associateFileType(file.getName());
    }

    return type;
  }

  @Override
  public FileType getKnownFileTypeOrAssociate(@Nonnull VirtualFile file, @Nonnull Project project) {
    return FileTypeChooser.getKnownFileTypeOrAssociate(file, project);
  }

  private void registerReDetectedMappings(@Nonnull StandardFileType pair) {
    FileType fileType = pair.fileType;
    if (fileType == PlainTextFileType.INSTANCE) return;
    for (FileNameMatcher matcher : pair.matchers) {
      registerReDetectedMapping(fileType, matcher);
      if (matcher instanceof ExtensionFileNameMatcher) {
        // also check exact file name matcher
        ExtensionFileNameMatcher extMatcher = (ExtensionFileNameMatcher)matcher;
        registerReDetectedMapping(fileType, new ExactFileNameMatcher("." + extMatcher.getExtension()));
      }
    }
  }

  private void registerReDetectedMapping(@Nonnull FileType fileType, @Nonnull FileNameMatcher matcher) {
    String typeName = myUnresolvedMappings.get(matcher);
    if (typeName != null && !typeName.equals(fileType.getId())) {
      Trinity<String, String, Boolean> trinity = myUnresolvedRemovedMappings.get(matcher);
      myRemovedMappings.put(matcher, Pair.create(fileType, trinity != null && trinity.third));
      myUnresolvedMappings.remove(matcher);
    }
  }

  @Nonnull
  Map<FileNameMatcher, Pair<FileType, Boolean>> getRemovedMappings() {
    return myRemovedMappings;
  }

  @TestOnly
  void clearForTests() {
    for (StandardFileType fileType : myStandardFileTypes.values()) {
      myPatternsTable.removeAllAssociations(fileType.fileType);
    }
    myStandardFileTypes.clear();
    myUnresolvedMappings.clear();
    mySchemesManager.clearAllSchemes();

  }

  @Override
  public void dispose() {
    LOG.info("FileTypeManager: " + counterAutoDetect + " auto-detected files. Elapsed time on auto-detect: " + elapsedAutoDetect + " ms");
  }

  @TestOnly
  public static int getVersionFromDetectors() {
    int version = 0;
    for (FileTypeDetector detector : FileTypeDetector.EP_NAME.getExtensions()) {
      version += detector.getVersion();
    }
    return version;
  }
}
