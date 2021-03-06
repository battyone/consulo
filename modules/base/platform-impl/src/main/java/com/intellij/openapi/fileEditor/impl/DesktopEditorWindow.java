/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.openapi.fileEditor.impl;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.CloseAction;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import consulo.logging.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.GraphicsConfig;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.ui.ThreeComponentsSplitter;
import consulo.disposer.Disposer;
import com.intellij.openapi.util.Iconable;
import consulo.util.dataholder.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;
import consulo.annotation.DeprecationInfo;
import consulo.desktop.util.awt.migration.AWTComponentProviderUtil;
import consulo.fileEditor.impl.*;
import consulo.fileTypes.impl.VfsIconUtil;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.UIAccess;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance;

/**
 * Author: msk
 */
@Deprecated
@DeprecationInfo("Desktop only")
@SuppressWarnings("deprecation")
public class DesktopEditorWindow extends EditorWindowBase implements EditorWindow {
  private static final Logger LOG = Logger.getInstance(DesktopEditorWindow.class);

  protected JPanel myPanel;
  private EditorTabbedContainer myTabbedPane;
  private final DesktopEditorsSplitters myOwner;
  private static final Icon MODIFIED_ICON = !UISettings.getInstance().HIDE_TABS_IF_NEED ? new Icon() {
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      GraphicsConfig config = GraphicsUtil.setupAAPainting(g);
      Font oldFont = g.getFont();
      try {
        g.setFont(UIUtil.getLabelFont());
        g.setColor(JBColor.foreground());
        g.drawString("*", 0, 10);
      }
      finally {
        config.restore();
        g.setFont(oldFont);
      }
    }

    @Override
    public int getIconWidth() {
      return 9;
    }

    @Override
    public int getIconHeight() {
      return 9;
    }
  } : AllIcons.General.Modified;
  private static final Icon GAP_ICON = EmptyIcon.create(MODIFIED_ICON);

  private boolean myIsDisposed;
  static final Key<Integer> INITIAL_INDEX_KEY = Key.create("initial editor index");
  private final Stack<Pair<String, Integer>> myRemovedTabs = new Stack<Pair<String, Integer>>() {
    @Override
    public void push(Pair<String, Integer> pair) {
      if (size() >= UISettings.getInstance().getEditorTabLimit()) {
        remove(0);
      }
      super.push(pair);
    }
  };
  private final AtomicBoolean myTabsHidingInProgress = new AtomicBoolean(false);
  private final Stack<Pair<String, Integer>> myHiddenTabs = new Stack<>();

  protected DesktopEditorWindow(final DesktopEditorsSplitters owner) {
    myOwner = owner;
    myPanel = new JPanel(new BorderLayout());
    myPanel.setOpaque(false);

    myTabbedPane = null;

    final int tabPlacement = UISettings.getInstance().getEditorTabPlacement();
    if (tabPlacement != UISettings.TABS_NONE && !UISettings.getInstance().getPresentationMode()) {
      createTabs();
    }

    // Tab layout policy
    if (UISettings.getInstance().getScrollTabLayoutInEditor()) {
      setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
    }
    else {
      setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
    }

    myOwner.addWindow(this);
    if (myOwner.getCurrentWindow() == null) {
      myOwner.setCurrentWindow(this, false);
    }
  }

  private void createTabs() {
    LOG.assertTrue(myTabbedPane == null);
    myTabbedPane = new EditorTabbedContainer(this, getManager().getProject());
    myPanel.add(myTabbedPane.getComponent(), BorderLayout.CENTER);
  }

  public boolean isShowing() {
    return myPanel.isShowing();
  }

  void dispose() {
    try {
      disposeTabs();
      myOwner.removeWindow(this);
    }
    finally {
      myIsDisposed = true;
    }
  }

  @Override
  public boolean isDisposed() {
    return myIsDisposed;
  }

  private void disposeTabs() {
    if (myTabbedPane != null) {
      Disposer.dispose(myTabbedPane);
      myTabbedPane = null;
    }
    myPanel.removeAll();
    myPanel.revalidate();
  }

  @Override
  public void closeFile(final VirtualFile file) {
    closeFile(file, true);
  }

  @Override
  public void closeFile(final VirtualFile file, final boolean disposeIfNeeded) {
    closeFile(file, disposeIfNeeded, true);
  }

  @Override
  public boolean hasClosedTabs() {
    return !myRemovedTabs.empty();
  }

  @Override
  public void restoreClosedTab() {
    assert hasClosedTabs() : "Nothing to restore";

    final Pair<String, Integer> info = myRemovedTabs.pop();
    final VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(info.getFirst());
    final Integer second = info.getSecond();
    if (file != null) {
      getManager().openFileImpl4(UIAccess.get(), this, file, null, true, true, null, second == null ? -1 : second.intValue());
    }
  }

  private void restoreHiddenTabs() {
    while (!myHiddenTabs.isEmpty()) {
      final Pair<String, Integer> info = myHiddenTabs.pop();
      myRemovedTabs.remove(info);
      final VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(info.getFirst());
      final Integer second = info.getSecond();
      if (file != null) {
        getManager().openFileImpl4(UIAccess.get(), this, file, null, true, true, null, second == null ? -1 : second.intValue());
      }
    }
  }

  @Override
  public void closeFile(@Nonnull final VirtualFile file, final boolean disposeIfNeeded, final boolean transferFocus) {
    final FileEditorManagerImpl editorManager = getManager();
    editorManager.runChange(splitters -> {
      final List<EditorWithProviderComposite> editors = splitters.findEditorComposites(file);
      if (editors.isEmpty()) return;
      try {
        final DesktopEditorWithProviderComposite editor = findFileComposite(file);

        final FileEditorManagerListener.Before beforePublisher = editorManager.getProject().getMessageBus().syncPublisher(FileEditorManagerListener.Before.FILE_EDITOR_MANAGER);

        beforePublisher.beforeFileClosed(editorManager, file);

        if (myTabbedPane != null && editor != null) {
          final int componentIndex = findComponentIndex(editor.getComponent());
          if (componentIndex >= 0) { // editor could close itself on decomposition
            final int indexToSelect = calcIndexToSelect(file, componentIndex);
            Pair<String, Integer> pair = Pair.create(file.getUrl(), componentIndex);
            myRemovedTabs.push(pair);
            if (myTabsHidingInProgress.get()) {
              myHiddenTabs.push(pair);
            }
            myTabbedPane.removeTabAt(componentIndex, indexToSelect, transferFocus);
            editorManager.disposeComposite(editor);
          }
        }
        else {

          if (inSplitter()) {
            Splitter splitter = (Splitter)myPanel.getParent();
            JComponent otherComponent = splitter.getOtherComponent(myPanel);

            if (otherComponent != null) {
              IdeFocusManager.findInstance().requestFocus(otherComponent, true);
            }
          }

          myPanel.removeAll();
          if (editor != null) {
            editorManager.disposeComposite(editor);
          }
        }

        if (disposeIfNeeded && getTabCount() == 0) {
          removeFromSplitter();
          if (UISettings.getInstance().getEditorTabPlacement() == UISettings.TABS_NONE) {
            final DesktopEditorsSplitters owner = getOwner();
            if (owner != null) {
              final ThreeComponentsSplitter splitter = UIUtil.getParentOfType(ThreeComponentsSplitter.class, owner.getComponent());
              if (splitter != null) {
                splitter.revalidate();
                splitter.repaint();
              }
            }
          }
        }
        else {
          myPanel.revalidate();
          if (myTabbedPane == null) {
            // in tabless mode
            myPanel.repaint();
          }
        }
      }
      finally {
        editorManager.removeSelectionRecord(file, this);

        editorManager.notifyPublisher(() -> {
          final Project project = editorManager.getProject();
          if (!project.isDisposed()) {
            final FileEditorManagerListener afterPublisher = project.getMessageBus().syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER);
            afterPublisher.fileClosed(editorManager, file);
          }
        });

        ((DesktopEditorsSplitters)splitters).afterFileClosed(file);
      }
    }, myOwner);
  }

  private void removeFromSplitter() {
    if (!inSplitter()) return;

    if (myOwner.getCurrentWindow() == this) {
      DesktopEditorWindow[] siblings = findSiblings();
      myOwner.setCurrentWindow(siblings[0], false);
    }

    Splitter splitter = (Splitter)myPanel.getParent();
    JComponent otherComponent = splitter.getOtherComponent(myPanel);

    Container parent = splitter.getParent().getParent();
    if (parent instanceof Splitter) {
      Splitter parentSplitter = (Splitter)parent;
      if (parentSplitter.getFirstComponent() == splitter.getParent()) {
        parentSplitter.setFirstComponent(otherComponent);
      }
      else {
        parentSplitter.setSecondComponent(otherComponent);
      }
    }
    else if (AWTComponentProviderUtil.getMark(parent) instanceof EditorsSplitters) {
      parent.removeAll();
      parent.add(otherComponent, BorderLayout.CENTER);
      parent.revalidate();
    }
    else {
      throw new IllegalStateException("Unknown container: " + parent);
    }

    dispose();
  }

  private int calcIndexToSelect(VirtualFile fileBeingClosed, final int fileIndex) {
    final int currentlySelectedIndex = myTabbedPane.getSelectedIndex();
    if (currentlySelectedIndex != fileIndex) {
      // if the file being closed is not currently selected, keep the currently selected file open
      return currentlySelectedIndex;
    }
    UISettings uiSettings = UISettings.getInstance();
    if (uiSettings.getActiveMruEditorOnClose()) {
      // try to open last visited file
      final VirtualFile[] histFiles = EditorHistoryManager.getInstance(getManager().getProject()).getFiles();
      for (int idx = histFiles.length - 1; idx >= 0; idx--) {
        final VirtualFile histFile = histFiles[idx];
        if (histFile.equals(fileBeingClosed)) {
          continue;
        }
        final DesktopEditorWithProviderComposite editor = findFileComposite(histFile);
        if (editor == null) {
          continue; // ????
        }
        final int histFileIndex = findComponentIndex(editor.getComponent());
        if (histFileIndex >= 0) {
          // if the file being closed is located before the hist file, then after closing the index of the histFile will be shifted by -1
          return histFileIndex;
        }
      }
    }
    else if (uiSettings.getActiveRigtEditorOnClose() && fileIndex + 1 < myTabbedPane.getTabCount()) {
      return fileIndex + 1;
    }

    // by default select previous neighbour
    if (fileIndex > 0) {
      return fileIndex - 1;
    }
    // do nothing
    return -1;
  }

  @Nonnull
  @Override
  public FileEditorManagerImpl getManager() {
    return myOwner.getManager();
  }

  @Override
  public int getTabCount() {
    if (myTabbedPane != null) {
      return myTabbedPane.getTabCount();
    }
    return myPanel.getComponentCount();
  }

  void setForegroundAt(final int index, final Color color) {
    if (myTabbedPane != null) {
      myTabbedPane.setForegroundAt(index, color);
    }
  }

  void setWaveColor(final int index, @Nullable final Color color) {
    if (myTabbedPane != null) {
      myTabbedPane.setWaveColor(index, color);
    }
  }

  private void setIconAt(final int index, final consulo.ui.image.Image icon) {
    if (myTabbedPane != null) {
      myTabbedPane.setIconAt(index, icon);
    }
  }

  @Override
  protected void setTitleAt(final int index, final String text) {
    if (myTabbedPane != null) {
      myTabbedPane.setTitleAt(index, text);
    }
  }

  @Override
  protected void setBackgroundColorAt(final int index, final Color color) {
    if (myTabbedPane != null) {
      myTabbedPane.setBackgroundColorAt(index, color);
    }
  }

  @Override
  protected void setToolTipTextAt(final int index, final String text) {
    if (myTabbedPane != null) {
      myTabbedPane.setToolTipTextAt(index, text);
    }
  }


  void setTabLayoutPolicy(final int policy) {
    if (myTabbedPane != null) {
      myTabbedPane.setTabLayoutPolicy(policy);
    }
  }

  @Override
  public void setTabsPlacement(final int tabPlacement) {
    if (tabPlacement != UISettings.TABS_NONE && !UISettings.getInstance().getPresentationMode()) {
      if (myTabbedPane == null) {
        final DesktopEditorWithProviderComposite editor = getSelectedEditor();
        myPanel.removeAll();
        createTabs();
        restoreHiddenTabs();
        setEditor(editor, true);
      }
      else {
        myTabbedPane.setTabPlacement(tabPlacement);
      }
    }
    else if (myTabbedPane != null) {
      final boolean focusEditor = ToolWindowManager.getInstance(getManager().getProject()).isEditorComponentActive();
      final VirtualFile currentFile = getSelectedFile();
      if (currentFile != null) {
        // do not close associated language console on tab placement change
        currentFile.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, Boolean.TRUE);
      }
      final VirtualFile[] files = getFiles();
      myHiddenTabs.clear();
      myTabsHidingInProgress.set(true);
      for (VirtualFile file : files) {
        closeFile(file, false);
      }
      //Add flag switching activity to the end of queue
      getManager().runChange(splitters -> myTabsHidingInProgress.set(false), myOwner);
      disposeTabs();
      if (currentFile != null) {
        currentFile.putUserData(FileEditorManagerImpl.CLOSING_TO_REOPEN, null);
        getManager().openFileImpl2(UIAccess.get(), this, currentFile, focusEditor && myOwner.getCurrentWindow() == this);
      }
      else {
        myPanel.repaint();
      }
    }
  }

  @Override
  public void setAsCurrentWindow(final boolean requestFocus) {
    myOwner.setCurrentWindow(this, requestFocus);
  }

  void updateFileBackgroundColor(@Nonnull VirtualFile file) {
    final int index = findEditorIndex(findFileComposite(file));
    if (index != -1) {
      final Color color = EditorTabbedContainer.calcTabColor(getManager().getProject(), file);
      setBackgroundColorAt(index, color);
    }
  }

  @Override
  public DesktopEditorsSplitters getOwner() {
    return myOwner;
  }

  boolean isEmptyVisible() {
    return myTabbedPane != null ? myTabbedPane.isEmptyVisible() : getFiles().length == 0;
  }

  public Dimension getSize() {
    return myPanel.getSize();
  }

  @Nullable
  public EditorTabbedContainer getTabbedPane() {
    return myTabbedPane;
  }

  @Override
  public void requestFocus(boolean forced) {
    if (myTabbedPane != null) {
      myTabbedPane.requestFocus(forced);
    }
    else {
      DesktopEditorWithProviderComposite editor = getSelectedEditor();
      JComponent preferred = editor == null ? null : editor.getPreferredFocusedComponent();
      IdeFocusManager.findInstanceByComponent(preferred == null ? myPanel : preferred).requestFocus(myPanel, forced);
    }
  }

  @Override
  public boolean isValid() {
    return myPanel.isShowing();
  }

  public void setPaintBlocked(boolean blocked) {
    if (myTabbedPane != null) {
      myTabbedPane.setPaintBlocked(blocked);
    }
  }

  protected static class TComp extends JPanel implements DataProvider, EditorWindowHolder {
    @Nonnull
    final DesktopEditorWithProviderComposite myEditor;
    protected final EditorWindow myWindow;

    TComp(@Nonnull DesktopEditorWindow window, @Nonnull DesktopEditorWithProviderComposite editor) {
      super(new BorderLayout());
      myEditor = editor;
      myWindow = window;
      add(editor.getComponent(), BorderLayout.CENTER);
      addFocusListener(new FocusAdapter() {
        @Override
        public void focusGained(FocusEvent e) {
          ApplicationManager.getApplication().invokeLater(() -> {
            if (!hasFocus()) return;
            final JComponent focus = myEditor.getSelectedEditorWithProvider().getFileEditor().getPreferredFocusedComponent();
            if (focus != null && !focus.hasFocus()) {
              IdeFocusManager.getGlobalInstance().requestFocus(focus, true);
            }
          });
        }
      });
    }

    @Nonnull
    @Override
    public EditorWindow getEditorWindow() {
      return myWindow;
    }

    @Override
    public Object getData(@Nonnull Key<?> dataId) {
      if (CommonDataKeys.VIRTUAL_FILE == dataId) {
        final VirtualFile virtualFile = myEditor.getFile();
        return virtualFile.isValid() ? virtualFile : null;
      }
      if (CommonDataKeys.PROJECT == dataId) {
        return myEditor.getFileEditorManager().getProject();
      }
      return null;
    }
  }

  protected static class TCompForTablessMode extends TComp implements CloseAction.CloseTarget {
    TCompForTablessMode(@Nonnull DesktopEditorWindow window, @Nonnull DesktopEditorWithProviderComposite editor) {
      super(window, editor);
    }

    @Override
    public Object getData(@Nonnull Key<?> dataId) {
      // this is essential for ability to close opened file
      if (DATA_KEY == dataId) {
        return myWindow;
      }
      if (CloseAction.CloseTarget.KEY == dataId) {
        return this;
      }
      return super.getData(dataId);
    }

    @Override
    public void close() {
      myWindow.closeFile(myEditor.getFile());
    }
  }

  private void checkConsistency() {
    LOG.assertTrue(myOwner.containsWindow(this), "EditorWindow not in collection");
  }

  @Override
  public DesktopEditorWithProviderComposite getSelectedEditor() {
    final TComp comp;
    if (myTabbedPane != null) {
      comp = (TComp)myTabbedPane.getSelectedComponent();
    }
    else if (myPanel.getComponentCount() != 0) {
      final Component component = myPanel.getComponent(0);
      comp = component instanceof TComp ? (TComp)component : null;
    }
    else {
      return null;
    }

    if (comp != null) {
      return comp.myEditor;
    }
    return null;
  }

  public void setSelectedEditor(final DesktopEditorComposite editor, final boolean focusEditor) {
    if (myTabbedPane == null) {
      return;
    }
    if (editor != null) {
      final int index = findFileIndex(editor.getFile());
      if (index != -1) {
        UIUtil.invokeLaterIfNeeded(() -> {
          if (myTabbedPane != null) {
            myTabbedPane.setSelectedIndex(index, focusEditor);
          }
        });
      }
    }
  }

  @RequiredUIAccess
  @Override
  public void setEditor(@Nullable final EditorWithProviderComposite editor, final boolean selectEditor, final boolean focusEditor) {
    if (editor != null) {
      onBeforeSetEditor(editor.getFile());
      if (myTabbedPane == null) {
        myPanel.removeAll();
        myPanel.add(new TCompForTablessMode(this, (DesktopEditorWithProviderComposite)editor), BorderLayout.CENTER);
        myOwner.getComponent().validate();
        return;
      }

      final int index = findEditorIndex(editor);
      if (index != -1) {
        if (selectEditor) {
          setSelectedEditor((DesktopEditorWithProviderComposite)editor, focusEditor);
        }
      }
      else {
        int indexToInsert;

        Integer initialIndex = editor.getFile().getUserData(INITIAL_INDEX_KEY);
        if (initialIndex != null) {
          indexToInsert = initialIndex;
        }
        else if (Registry.is("ide.editor.tabs.open.at.the.end")) {
          indexToInsert = myTabbedPane.getTabCount();
        }
        else {
          int selectedIndex = myTabbedPane.getSelectedIndex();
          if (selectedIndex >= 0) {
            indexToInsert = selectedIndex + 1;
          }
          else {
            indexToInsert = 0;
          }
        }

        final VirtualFile file = editor.getFile();
        final Icon template = AllIcons.FileTypes.Text;
        myTabbedPane.insertTab(file, Image.empty(template.getIconWidth(), template.getIconHeight()), new TComp(this, (DesktopEditorWithProviderComposite)editor), null, indexToInsert);
        trimToSize(UISettings.getInstance().getEditorTabLimit(), file, false);
        if (selectEditor) {
          setSelectedEditor((DesktopEditorWithProviderComposite)editor, focusEditor);
        }
        myOwner.updateFileIcon(file);
        myOwner.updateFileColor(file);
      }
      myOwner.setCurrentWindow(this, false);
    }
    myOwner.getComponent().validate();
  }

  protected void onBeforeSetEditor(VirtualFile file) {
  }

  private boolean splitAvailable() {
    return getTabCount() >= 1;
  }

  @Override
  @Nullable
  public DesktopEditorWindow split(final int orientation, boolean forceSplit, @Nullable VirtualFile virtualFile, boolean focusNew) {
    checkConsistency();
    final FileEditorManagerImpl fileEditorManager = myOwner.getManager();
    if (splitAvailable()) {
      if (!forceSplit && inSplitter()) {
        final DesktopEditorWindow[] siblings = findSiblings();
        final DesktopEditorWindow target = siblings[0];
        if (virtualFile != null) {
          final FileEditor[] editors = fileEditorManager.openFileImpl3(UIAccess.get(), target, virtualFile, focusNew, null, true).first;
          syncCaretIfPossible(editors);
        }
        return target;
      }
      final JPanel panel = myPanel;
      panel.setBorder(null);
      final int tabCount = getTabCount();
      if (tabCount != 0) {
        final DesktopEditorWithProviderComposite firstEC = getEditorAt(0);
        myPanel = new JPanel(new BorderLayout());
        myPanel.setOpaque(false);

        final Splitter splitter = new OnePixelSplitter(orientation == JSplitPane.VERTICAL_SPLIT, 0.5f, 0.1f, 0.9f);
        final DesktopEditorWindow res = new DesktopEditorWindow(myOwner);
        if (myTabbedPane != null) {
          final DesktopEditorWithProviderComposite selectedEditor = getSelectedEditor();
          panel.remove(myTabbedPane.getComponent());
          panel.add(splitter, BorderLayout.CENTER);
          splitter.setFirstComponent(myPanel);
          myPanel.add(myTabbedPane.getComponent(), BorderLayout.CENTER);
          splitter.setSecondComponent(res.myPanel);
          /*
          for (int i = 0; i != tabCount; ++i) {
            final EditorWithProviderComposite eC = getEditorAt(i);
            final VirtualFile file = eC.getFile();
            fileEditorManager.openFileImpl3(res, file, false, null);
            res.setFilePinned (file, isFilePinned (file));
          }
          */
          // open only selected file in the new splitter instead of opening all tabs
          final VirtualFile file = selectedEditor.getFile();

          if (virtualFile == null) {
            for (FileEditorAssociateFinder finder : FileEditorAssociateFinder.EP_NAME.getExtensionList()) {
              VirtualFile associatedFile = finder.getAssociatedFileToOpen(fileEditorManager.getProject(), file);

              if (associatedFile != null) {
                virtualFile = associatedFile;
                break;
              }
            }
          }

          final VirtualFile nextFile = virtualFile == null ? file : virtualFile;
          final FileEditor[] editors = fileEditorManager.openFileImpl3(UIAccess.get(), res, nextFile, focusNew, null, true).first;
          syncCaretIfPossible(editors);
          res.setFilePinned(nextFile, isFilePinned(file));
          if (!focusNew) {
            res.setSelectedEditor(selectedEditor, true);
            getGlobalInstance().doWhenFocusSettlesDown(() -> {
              getGlobalInstance().requestFocus(selectedEditor.getComponent(), true);
            });
          }
          panel.revalidate();
        }
        else {
          panel.removeAll();
          panel.add(splitter, BorderLayout.CENTER);
          splitter.setFirstComponent(myPanel);
          splitter.setSecondComponent(res.myPanel);
          panel.revalidate();
          final VirtualFile firstFile = firstEC.getFile();
          final VirtualFile nextFile = virtualFile == null ? firstFile : virtualFile;
          final FileEditor[] firstEditors = fileEditorManager.openFileImpl3(UIAccess.get(), this, firstFile, !focusNew, null, true).first;
          syncCaretIfPossible(firstEditors);
          final FileEditor[] secondEditors = fileEditorManager.openFileImpl3(UIAccess.get(), res, nextFile, focusNew, null, true).first;
          syncCaretIfPossible(secondEditors);
        }
        return res;
      }
    }
    return null;
  }

  /**
   * Tries to setup caret and viewport for the given editor from the selected one.
   *
   * @param toSync editor to setup caret and viewport for
   */
  private void syncCaretIfPossible(@Nullable FileEditor[] toSync) {
    if (toSync == null) {
      return;
    }

    final DesktopEditorWithProviderComposite from = getSelectedEditor();
    if (from == null) {
      return;
    }

    final FileEditor caretSource = from.getSelectedEditor();
    if (!(caretSource instanceof TextEditor)) {
      return;
    }

    final Editor editorFrom = ((TextEditor)caretSource).getEditor();
    final int offset = editorFrom.getCaretModel().getOffset();
    if (offset <= 0) {
      return;
    }

    final int scrollOffset = editorFrom.getScrollingModel().getVerticalScrollOffset();

    for (FileEditor fileEditor : toSync) {
      if (!(fileEditor instanceof TextEditor)) {
        continue;
      }
      final Editor editor = ((TextEditor)fileEditor).getEditor();
      if (editorFrom.getDocument() == editor.getDocument()) {
        editor.getCaretModel().moveToOffset(offset);
        final ScrollingModel scrollingModel = editor.getScrollingModel();
        scrollingModel.scrollVertically(scrollOffset);

        SwingUtilities.invokeLater(() -> {
          if (!editor.isDisposed()) {
            scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE);
          }
        });
      }
    }
  }

  @Nonnull
  @Override
  public DesktopEditorWindow[] findSiblings() {
    checkConsistency();
    final ArrayList<DesktopEditorWindow> res = new ArrayList<>();
    if (myPanel.getParent() instanceof Splitter) {
      final Splitter splitter = (Splitter)myPanel.getParent();
      for (final DesktopEditorWindow win : myOwner.getWindows()) {
        if (win != this && SwingUtilities.isDescendingFrom(win.myPanel, splitter)) {
          res.add(win);
        }
      }
    }
    return res.toArray(new DesktopEditorWindow[res.size()]);
  }

  @Override
  public void changeOrientation() {
    checkConsistency();
    final Container parent = myPanel.getParent();
    if (parent instanceof Splitter) {
      final Splitter splitter = (Splitter)parent;
      splitter.setOrientation(!splitter.getOrientation());
    }
  }

  void updateFileIcon(VirtualFile file) {
    final int index = findEditorIndex(findFileComposite(file));
    LOG.assertTrue(index != -1);
    setIconAt(index, getFileIcon(file));
  }

  @Override
  protected void updateFileName(VirtualFile file) {
    final int index = findEditorIndex(findFileComposite(file));
    if (index != -1) {
      setTitleAt(index, EditorTabbedContainer.calcTabTitle(getManager().getProject(), file));
      setToolTipTextAt(index, UISettings.getInstance().getShowTabsTooltips() ? getManager().getFileTooltipText(file) : null);
    }
  }

  /**
   * @return icon which represents file's type and modification status
   */
  @Nullable
  private consulo.ui.image.Image getFileIcon(@Nonnull final VirtualFile file) {
    if (!file.isValid()) {
      return UnknownFileType.INSTANCE.getIcon();
    }

    final Image baseIcon = VfsIconUtil.getIconNoDefer(file, Iconable.ICON_FLAG_READ_STATUS, getManager().getProject());
    int count = 1;

    final Image pinIcon;
    final DesktopEditorComposite composite = findFileComposite(file);
    if (composite != null && composite.isPinned()) {
      count++;
      pinIcon = AllIcons.Nodes.TabPin;
    }
    else {
      pinIcon = null;
    }

    // FIXME [VISTALL] not supported for now
    Icon modifiedIcon = null;
    //UISettings settings = UISettings.getInstance();
    //if (settings.getMarkModifiedTabsWithAsterisk() || !settings.getHideTabsIfNeed()) {
    //  modifiedIcon = settings.getMarkModifiedTabsWithAsterisk() && composite != null && composite.isModified() ? MODIFIED_ICON : GAP_ICON;
    //  count++;
    //}
    //else {
    //  modifiedIcon = null;
    //}
    //
    //if (count == 1) return baseIcon;

    if(pinIcon != null && modifiedIcon == null) {
      return ImageEffects.layered(baseIcon, pinIcon);
    }

    // FIXME [VISTALL] not supported for now
    //int i = 0;
    //final LayeredIcon result = new LayeredIcon(count);
    //int xShift = !settings.getHideTabsIfNeed() ? 4 : 0;
    //result.setIcon(baseIcon, i++, xShift, 0);
    //if (pinIcon != null) result.setIcon(pinIcon, i++, xShift, 0);
    //if (modifiedIcon != null) result.setIcon(modifiedIcon, i++);
    //
    //return JBUI.scale(result);
    return baseIcon;
  }

  @Override
  public void unsplit(boolean setCurrent) {
    checkConsistency();
    final Container splitter = myPanel.getParent();

    if (!(splitter instanceof Splitter)) return;

    EditorWithProviderComposite editorToSelect = getSelectedEditor();
    final DesktopEditorWindow[] siblings = findSiblings();
    final JPanel parent = (JPanel)splitter.getParent();

    for (DesktopEditorWindow eachSibling : siblings) {
      // selected editors will be added first
      final DesktopEditorWithProviderComposite selected = eachSibling.getSelectedEditor();
      if (editorToSelect == null) {
        editorToSelect = selected;
      }
    }

    for (final DesktopEditorWindow sibling : siblings) {
      final EditorWithProviderComposite[] siblingEditors = sibling.getEditors();
      for (final EditorWithProviderComposite siblingEditor : siblingEditors) {
        if (editorToSelect == null) {
          editorToSelect = siblingEditor;
        }
        processSiblingEditor((DesktopEditorWithProviderComposite)siblingEditor);
      }
      LOG.assertTrue(sibling != this);
      sibling.dispose();
    }
    parent.remove(splitter);
    if (myTabbedPane != null) {
      parent.add(myTabbedPane.getComponent(), BorderLayout.CENTER);
    }
    else {
      if (myPanel.getComponentCount() > 0) {
        parent.add(myPanel.getComponent(0), BorderLayout.CENTER);
      }
    }
    parent.revalidate();
    myPanel = parent;
    if (editorToSelect != null) {
      setSelectedEditor((DesktopEditorComposite)editorToSelect, true);
    }
    if (setCurrent) {
      myOwner.setCurrentWindow(this, false);
    }
  }

  private void processSiblingEditor(final DesktopEditorWithProviderComposite siblingEditor) {
    if (myTabbedPane != null && getTabCount() < UISettings.getInstance().getEditorTabLimit() && findFileComposite(siblingEditor.getFile()) == null || myTabbedPane == null && getTabCount() == 0) {
      setEditor(siblingEditor, true);
    }
    else {
      getManager().disposeComposite(siblingEditor);
    }
  }

  @Override
  public void unsplitAll() {
    checkConsistency();
    while (inSplitter()) {
      unsplit(true);
    }
  }

  @Override
  public boolean inSplitter() {
    checkConsistency();
    return myPanel.getParent() instanceof Splitter;
  }

  @Override
  public VirtualFile getSelectedFile() {
    checkConsistency();
    final DesktopEditorWithProviderComposite editor = getSelectedEditor();
    return editor == null ? null : editor.getFile();
  }

  private int findComponentIndex(final Component component) {
    for (int i = 0; i != getTabCount(); ++i) {
      final DesktopEditorWithProviderComposite editor = getEditorAt(i);
      if (editor.getComponent().equals(component)) {
        return i;
      }
    }
    return -1;
  }

  @Override
  protected DesktopEditorWithProviderComposite getEditorAt(final int i) {
    final TComp comp;
    if (myTabbedPane != null) {
      comp = (TComp)myTabbedPane.getComponentAt(i);
    }
    else {
      LOG.assertTrue(i <= 1);
      comp = (TComp)myPanel.getComponent(i);
    }
    return comp.myEditor;
  }

  @Override
  public boolean isFileOpen(final VirtualFile file) {
    return findFileComposite(file) != null;
  }

  @Override
  public boolean isFilePinned(final VirtualFile file) {
    final EditorComposite editorComposite = findFileComposite(file);
    if (editorComposite == null) {
      throw new IllegalArgumentException("file is not open: " + file.getPath());
    }
    return editorComposite.isPinned();
  }

  @Override
  public void setFilePinned(final VirtualFile file, final boolean pinned) {
    final DesktopEditorComposite editorComposite = findFileComposite(file);
    if (editorComposite == null) {
      throw new IllegalArgumentException("file is not open: " + file.getPath());
    }
    boolean wasPinned = editorComposite.isPinned();
    editorComposite.setPinned(pinned);
    if (wasPinned != pinned && ApplicationManager.getApplication().isDispatchThread()) {
      updateFileIcon(file);
    }
  }

  void trimToSize(final int limit, @Nullable final VirtualFile fileToIgnore, final boolean transferFocus) {
    if (myTabbedPane == null) return;

    FileEditorManagerEx.getInstanceEx(getManager().getProject()).getReady(this).doWhenDone(() -> {
      if (myTabbedPane == null) return;
      final boolean closeNonModifiedFilesFirst = UISettings.getInstance().getCloseNonModifiedFilesFirst();
      final DesktopEditorComposite selectedComposite = getSelectedEditor();
      try {
        doTrimSize(limit, fileToIgnore, closeNonModifiedFilesFirst, transferFocus);
      }
      finally {
        setSelectedEditor(selectedComposite, false);
      }
    });
  }

  private void doTrimSize(int limit, @Nullable VirtualFile fileToIgnore, boolean closeNonModifiedFilesFirst, boolean transferFocus) {
    LinkedHashSet<VirtualFile> closingOrder = getTabClosingOrder(closeNonModifiedFilesFirst);
    VirtualFile selectedFile = getSelectedFile();
    if (shouldCloseSelected()) {
      defaultCloseFile(selectedFile, transferFocus);
      closingOrder.remove(selectedFile);
    }

    for (VirtualFile file : closingOrder) {
      if (myTabbedPane.getTabCount() <= limit || myTabbedPane.getTabCount() == 0 || areAllTabsPinned(fileToIgnore)) {
        return;
      }
      if (fileCanBeClosed(file, fileToIgnore)) {
        defaultCloseFile(file, transferFocus);
      }
    }

  }

  private LinkedHashSet<VirtualFile> getTabClosingOrder(boolean closeNonModifiedFilesFirst) {
    final VirtualFile[] allFiles = getFiles();
    final Set<VirtualFile> histFiles = EditorHistoryManager.getInstance(getManager().getProject()).getFileSet();

    LinkedHashSet<VirtualFile> closingOrder = ContainerUtil.newLinkedHashSet();

    // first, we search for files not in history
    for (final VirtualFile file : allFiles) {
      if (!histFiles.contains(file)) {
        closingOrder.add(file);
      }
    }

    if (closeNonModifiedFilesFirst) {
      // Search in history
      for (final VirtualFile file : histFiles) {
        DesktopEditorWithProviderComposite composite = findFileComposite(file);
        if (composite != null && !myOwner.getManager().isChanged(composite)) {
          // we found non modified file
          closingOrder.add(file);
        }
      }

      // Search in tabbed pane
      for (int i = 0; i < myTabbedPane.getTabCount(); i++) {
        final VirtualFile file = getFileAt(i);
        if (!myOwner.getManager().isChanged(getEditorAt(i))) {
          // we found non modified file
          closingOrder.add(file);
        }
      }
    }

    // If it's not enough to close non-modified files only, try all other files.
    // Search in history from less frequently used.
    closingOrder.addAll(histFiles);

    // finally, close tabs by their order
    for (int i = 0; i < myTabbedPane.getTabCount(); i++) {
      closingOrder.add(getFileAt(i));
    }

    final VirtualFile selectedFile = getSelectedFile();
    closingOrder.remove(selectedFile);
    closingOrder.add(selectedFile); // selected should be closed last
    return closingOrder;
  }

  private boolean shouldCloseSelected() {
    if (!UISettings.getInstance().getReuseNotModifiedTabs() || !myOwner.getManager().getProject().isInitialized()) {
      return false;
    }

    VirtualFile file = getSelectedFile();
    if (file == null || !isFileOpen(file) || isFilePinned(file)) {
      return false;
    }
    DesktopEditorWithProviderComposite composite = findFileComposite(file);
    if (composite == null) return false;
    Component owner = IdeFocusManager.getInstance(myOwner.getManager().getProject()).getFocusOwner();
    if (owner == null || !SwingUtilities.isDescendingFrom(owner, composite.getSelectedEditor().getComponent())) return false;
    return !myOwner.getManager().isChanged(composite);
  }

  private boolean areAllTabsPinned(VirtualFile fileToIgnore) {
    for (int i = myTabbedPane.getTabCount() - 1; i >= 0; i--) {
      if (fileCanBeClosed(getFileAt(i), fileToIgnore)) {
        return false;
      }
    }
    return true;
  }

  private void defaultCloseFile(VirtualFile file, boolean transferFocus) {
    closeFile(file, true, transferFocus);
  }

  private boolean fileCanBeClosed(final VirtualFile file, @Nullable final VirtualFile fileToIgnore) {
    return isFileOpen(file) && !file.equals(fileToIgnore) && !isFilePinned(file);
  }

  @Override
  public void clear() {
    for (EditorWithProviderComposite composite : getEditors()) {
      Disposer.dispose(composite);
    }
    if (myTabbedPane == null) {
      myPanel.removeAll();
    }
  }

  @Nullable
  @Override
  public DesktopEditorWithProviderComposite findFileComposite(VirtualFile file) {
    return (DesktopEditorWithProviderComposite)super.findFileComposite(file);
  }

  @Override
  public String toString() {
    return "EditorWindow: files=" + Arrays.asList(getFiles());
  }
}
