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
package com.intellij.openapi.vcs.changes;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DataManager;
import com.intellij.ide.TreeExpander;
import com.intellij.ide.util.treeView.TreeState;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import consulo.util.dataholder.Key;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserBase;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesListView;
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.EditSourceOnEnterKeyHandler;
import com.intellij.util.ui.tree.TreeUtil;
import javax.annotation.Nonnull;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.List;
import java.util.stream.Stream;

abstract class SpecificFilesViewDialog extends DialogWrapper {
  protected JPanel myPanel;
  protected final ChangesListView myView;
  protected final ChangeListManager myChangeListManager;
  protected boolean myInRefresh;
  protected final Project myProject;

  protected SpecificFilesViewDialog(@Nonnull Project project,
                                    @Nonnull String title,
                                    @Nonnull Key<Stream<VirtualFile>> shownDataKey,
                                    @Nonnull List<VirtualFile> initDataFiles) {
    super(project, true);
    setTitle(title);
    myProject = project;
    final Runnable closer = () -> this.close(0);
    myView = new ChangesListView(project) {
      @Override
      public void calcData(Key<?> key, DataSink sink) {
        super.calcData(key, sink);
        if (shownDataKey == key) {
          sink.put(shownDataKey, getSelectedFiles());
        }
      }

      @Override
      protected void editSourceRegistration() {
        EditSourceOnDoubleClickHandler.install(this, closer);
        EditSourceOnEnterKeyHandler.install(this, closer);
      }
    };
    myChangeListManager = ChangeListManager.getInstance(project);
    createPanel();
    setOKButtonText("Close");

    init();
    initData(initDataFiles);
    myView.setMinimumSize(new Dimension(100, 100));
  }


  @Nonnull
  @Override
  protected Action[] createActions() {
    return new Action[]{getOKAction()};
  }

  private void initData(@Nonnull final List<VirtualFile> files) {
    final TreeState state = TreeState.createOn(myView, (ChangesBrowserNode)myView.getModel().getRoot());

    final DefaultTreeModel model = TreeModelBuilder.buildFromVirtualFiles(myProject, myView.isShowFlatten(), files);
    myView.setModel(model);
    myView.expandPath(new TreePath(((ChangesBrowserNode)model.getRoot()).getPath()));

    state.applyTo(myView);
  }

  private void createPanel() {
    myPanel = new JPanel(new BorderLayout());

    final DefaultActionGroup group = new DefaultActionGroup();
    final ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar("SPECIFIC_FILES_DIALOG", group, true);

    addCustomActions(group, actionToolbar);

    final CommonActionsManager cam = CommonActionsManager.getInstance();
    final Expander expander = new Expander();
    group.addSeparator();
    group.add(new ToggleShowFlattenAction());
    group.add(cam.createExpandAllAction(expander, myView));
    group.add(cam.createCollapseAllAction(expander, myView));

    myPanel.add(actionToolbar.getComponent(), BorderLayout.NORTH);
    myPanel.add(ScrollPaneFactory.createScrollPane(myView), BorderLayout.CENTER);
    myView.setShowFlatten(false);
  }

  protected void addCustomActions(@Nonnull DefaultActionGroup group, @Nonnull ActionToolbar actionToolbar) {
  }

  @Override
  protected String getDimensionServiceKey() {
    return "com.intellij.openapi.vcs.changes.SpecificFilesViewDialog";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myView;
  }

  @Override
  protected JComponent createCenterPanel() {
    return myPanel;
  }

  private class Expander implements TreeExpander {
    public void expandAll() {
      TreeUtil.expandAll(myView);
    }

    public boolean canExpand() {
      return !myView.isShowFlatten();
    }

    public void collapseAll() {
      TreeUtil.collapseAll(myView, 1);
      TreeUtil.expand(myView, 0);
    }

    public boolean canCollapse() {
      return !myView.isShowFlatten();
    }
  }

  protected void refreshView() {
    ApplicationManager.getApplication().assertIsDispatchThread();

    if (myInRefresh) return;
    myInRefresh = true;

    myChangeListManager.invokeAfterUpdate(() -> {
      try {
        initData(getFiles());
      }
      finally {
        myInRefresh = false;
      }
    }, InvokeAfterUpdateMode.BACKGROUND_NOT_CANCELLABLE, "", ModalityState.current());
  }

  @Nonnull
  protected abstract List<VirtualFile> getFiles();

  protected static ChangesBrowserBase getBrowserBase(@Nonnull ChangesListView view) {
    return DataManager.getInstance().getDataContext(view).getData(ChangesBrowserBase.DATA_KEY);
  }

  public static void refreshChanges(@Nonnull Project project, @javax.annotation.Nullable ChangesBrowserBase browser) {
    if (browser != null) {
      ChangeListManager.getInstance(project)
              .invokeAfterUpdate(browser::rebuildList, InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE, "Delete files", null);
    }
  }

  public class ToggleShowFlattenAction extends ToggleAction implements DumbAware {
    public ToggleShowFlattenAction() {
      super(VcsBundle.message("changes.action.show.directories.text"),
            VcsBundle.message("changes.action.show.directories.description"),
            AllIcons.Actions.GroupByPackage);
    }

    public boolean isSelected(AnActionEvent e) {
      return !myView.isShowFlatten();
    }

    public void setSelected(AnActionEvent e, boolean state) {
      myView.setShowFlatten(!state);
      refreshView();
    }
  }
}
