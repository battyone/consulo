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

package com.intellij.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.INativeFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.vfs.PersistentFSConstants;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.pom.Navigatable;
import com.intellij.util.Alarm;
import com.intellij.util.OpenSourceUtil;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

public abstract class AutoScrollToSourceHandler {
  private Alarm myAutoScrollAlarm;

  protected AutoScrollToSourceHandler() {
  }

  public void install(final JTree tree) {
    myAutoScrollAlarm = new Alarm();
    new ClickListener() {
      @Override
      public boolean onClick(MouseEvent e, int clickCount) {
        if (clickCount > 1) return false;

        TreePath location = tree.getPathForLocation(e.getPoint().x, e.getPoint().y);
        if (location != null) {
          onMouseClicked(tree);
          return isAutoScrollMode();
        }

        return false;
      }
    }.installOn(tree);

    tree.addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseDragged(final MouseEvent e) {
        onSelectionChanged(tree);
      }
    });
    tree.addTreeSelectionListener(
      new TreeSelectionListener() {
        @Override
        public void valueChanged(TreeSelectionEvent e) {
          onSelectionChanged(tree);
        }
      }
    );
  }

  public void install(final JTable table) {
    myAutoScrollAlarm = new Alarm();
    new ClickListener() {
      @Override
      public boolean onClick(MouseEvent e, int clickCount) {
        if (clickCount >= 2) return false;

        Component location = table.getComponentAt(e.getPoint());
        if (location != null) {
          onMouseClicked(table);
          return isAutoScrollMode();
        }
        return false;
      }
    }.installOn(table);

    table.addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseDragged(final MouseEvent e) {
        onSelectionChanged(table);
      }
    });
    table.getSelectionModel().addListSelectionListener(
      new ListSelectionListener() {
        @Override
        public void valueChanged(ListSelectionEvent e) {
          onSelectionChanged(table);
        }
      }
    );
  }

  public void install(final JList jList) {
    myAutoScrollAlarm = new Alarm();
    new ClickListener() {
      @Override
      public boolean onClick(MouseEvent e, int clickCount) {
        if (clickCount >= 2) return false;
        final Object source = e.getSource();
        final int index = jList.locationToIndex(SwingUtilities.convertPoint(source instanceof Component ? (Component)source : null, e.getPoint(), jList));
        if (index >= 0 && index < jList.getModel().getSize()) {
          onMouseClicked(jList);
          return true;
        }
        return false;
      }
    }.installOn(jList);

    jList.addListSelectionListener(new ListSelectionListener() {
      @Override
      public void valueChanged(ListSelectionEvent e) {
        onSelectionChanged(jList);
      }
    });
  }

  public void cancelAllRequests(){
    if (myAutoScrollAlarm != null) {
      myAutoScrollAlarm.cancelAllRequests();
    }
  }

  public void onMouseClicked(final Component component) {
    myAutoScrollAlarm.cancelAllRequests();
    if (isAutoScrollMode()){
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          scrollToSource(component);
        }
      });
    }
  }

  private void onSelectionChanged(final Component component) {
    if (component != null && !component.isShowing()) return;

    if (!isAutoScrollMode()) {
      return;
    }
    if (needToCheckFocus() && !component.hasFocus()) {
      return;
    }

    myAutoScrollAlarm.cancelAllRequests();
    myAutoScrollAlarm.addRequest(
      new Runnable() {
        @Override
        public void run() {
          if (component.isShowing()) { //for tests
            scrollToSource(component);
          }
        }
      },
      500
    );
  }

  protected boolean needToCheckFocus(){
    return true;
  }

  protected abstract boolean isAutoScrollMode();
  protected abstract void setAutoScrollMode(boolean state);

  protected void scrollToSource(final Component tree) {
    DataContext dataContext=DataManager.getInstance().getDataContext(tree);
    getReady(dataContext).doWhenDone(new Runnable() {
      @Override
      public void run() {
        DataContext context = DataManager.getInstance().getDataContext(tree);
        final VirtualFile vFile = context.getData(PlatformDataKeys.VIRTUAL_FILE);
        if (vFile != null) {
          // Attempt to navigate to the virtual file with unknown file type will show a modal dialog
          // asking to register some file type for this file. This behaviour is undesirable when autoscrolling.
          if (vFile.getFileType() == UnknownFileType.INSTANCE || vFile.getFileType() instanceof INativeFileType) return;

          //IDEA-84881 Don't autoscroll to very large files
          if (vFile.getLength() > PersistentFSConstants.getMaxIntellisenseFileSize()) return;
        }
        Navigatable[] navigatables = context.getData(PlatformDataKeys.NAVIGATABLE_ARRAY);
        if (navigatables != null) {
          if (navigatables.length > 1) {
            return;
          }
          for (Navigatable navigatable : navigatables) {
            // we are not going to open modal dialog during autoscrolling
            if (!navigatable.canNavigateToSource()) return;
          }
        }
        OpenSourceUtil.openSourcesFrom(context, false);
      }
    });
  }

  public ToggleAction createToggleAction() {
    return new AutoscrollToSourceAction();
  }

  private class AutoscrollToSourceAction extends ToggleAction implements DumbAware {
    public AutoscrollToSourceAction() {
      super(UIBundle.message("autoscroll.to.source.action.name"), UIBundle.message("autoscroll.to.source.action.description"),
            AllIcons.General.AutoscrollToSource);
    }

    @Override
    public boolean isSelected(AnActionEvent event) {
      return isAutoScrollMode();
    }

    @Override
    public void setSelected(AnActionEvent event, boolean flag) {
      setAutoScrollMode(flag);
    }
  }

  private AsyncResult<Void> getReady(DataContext context) {
    ToolWindow toolWindow = context.getData(PlatformDataKeys.TOOL_WINDOW);
    return toolWindow != null ? toolWindow.getReady(this) : AsyncResult.done(null);
  }
}

