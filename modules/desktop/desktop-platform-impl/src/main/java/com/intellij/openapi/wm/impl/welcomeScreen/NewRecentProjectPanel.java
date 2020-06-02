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
package com.intellij.openapi.wm.impl.welcomeScreen;

import com.intellij.ide.ProjectGroup;
import com.intellij.ide.ProjectGroupActionGroup;
import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.ReopenProjectAction;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.util.io.UniqueNameBuilder;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.speedSearch.NameFilteringListModel;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.accessibility.AccessibleContextUtil;
import consulo.awt.TargetAWT;
import consulo.disposer.Disposable;
import consulo.ui.SwingUIDecorator;
import consulo.ui.image.Image;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

/**
 * @author Konstantin Bulenkov
 */
public class NewRecentProjectPanel extends RecentProjectPanel {
  public NewRecentProjectPanel(Disposable parentDisposable, boolean welcomeScreen) {
    super(parentDisposable);

    myRootPanel.setBorder(JBUI.Borders.empty());
    if(welcomeScreen) {
      myRootPanel.setBackground(SwingUIDecorator.get(SwingUIDecorator::getSidebarColor));

      myScrollPane.setOpaque(false);
      myScrollPane.getViewport().setOpaque(false);
      myTargetComponent.setOpaque(false);
      myList.setOpaque(false);

      JBDimension size = JBUI.size(300, 460);
      myScrollPane.setSize(size);
      myScrollPane.setMinimumSize(size);
      myScrollPane.setPreferredSize(size);
    }
  }

  @Override
  protected Dimension getPreferredScrollableViewportSize() {
    return null;
  }

  @Override
  protected JBList<AnAction> createList(AnAction[] recentProjectActions, Dimension size) {
    final JBList<AnAction> list = super.createList(recentProjectActions, size);
    list.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        Object selected = list.getSelectedValue();
        final ProjectGroup group;
        if (selected instanceof ProjectGroupActionGroup) {
          group = ((ProjectGroupActionGroup)selected).getGroup();
        }
        else {
          group = null;
        }

        int keyCode = e.getKeyCode();
        if (keyCode == KeyEvent.VK_RIGHT) {
          if (group != null) {
            if (!group.isExpanded()) {
              group.setExpanded(true);
              ListModel model = ((NameFilteringListModel)list.getModel()).getOriginalModel();
              int index = list.getSelectedIndex();
              RecentProjectsWelcomeScreenActionBase.rebuildRecentProjectDataModel((DefaultListModel)model);
              list.setSelectedIndex(group.getProjects().isEmpty() ? index : index + 1);
            }
          }
          else {
            JFrame frame = UIUtil.getParentOfType(JFrame.class, list);
            if (frame != null) {
              FocusTraversalPolicy policy = frame.getFocusTraversalPolicy();
              if (policy != null) {
                Component next = policy.getComponentAfter(frame, list);
                if (next != null) {
                  IdeFocusManager.getGlobalInstance().doForceFocusWhenFocusSettlesDown(next);
                }
              }
            }
          }
        }
        else if (keyCode == KeyEvent.VK_LEFT) {
          if (group != null && group.isExpanded()) {
            group.setExpanded(false);
            int index = list.getSelectedIndex();
            ListModel model = ((NameFilteringListModel)list.getModel()).getOriginalModel();
            RecentProjectsWelcomeScreenActionBase.rebuildRecentProjectDataModel((DefaultListModel)model);
            list.setSelectedIndex(index);
          }
        }
      }
    });
    list.addMouseListener(new PopupHandler() {
      @Override
      public void invokePopup(Component comp, int x, int y) {
        final int index = list.locationToIndex(new Point(x, y));
        if (index != -1 && Arrays.binarySearch(list.getSelectedIndices(), index) < 0) {
          list.setSelectedIndex(index);
        }
        final ActionGroup group = (ActionGroup)ActionManager.getInstance().getAction("WelcomeScreenRecentProjectActionGroup");
        if (group != null) {
          ActionManager.getInstance().createActionPopupMenu(ActionPlaces.WELCOME_SCREEN, group).getComponent().show(comp, x, y);
        }
      }
    });
    return list;
  }

  @Override
  protected boolean isUseGroups() {
    return true;
  }

  @Override
  protected ListCellRenderer<AnAction> createRenderer(UniqueNameBuilder<ReopenProjectAction> pathShortener) {
    return new ListCellRenderer<AnAction> () {

      JComponent spacer = new NonOpaquePanel() {
        @Override
        public Dimension getPreferredSize() {
          return new Dimension(JBUI.scale(22), super.getPreferredSize().height);
        }
      };

      @Override
      public Component getListCellRendererComponent(JList list, final AnAction value, int index, final boolean isSelected, boolean cellHasFocus) {
        boolean selectedAndFocused = isSelected && list.hasFocus();
        final Color fore = UIUtil.getListForeground(selectedAndFocused);
        final Color back = UIUtil.getListBackground(selectedAndFocused);
        final JLabel name = new JLabel();
        final JLabel path = new JLabel();
        name.setForeground(fore);
        path.setForeground(selectedAndFocused ? fore : UIUtil.getInactiveTextColor());

        return new JPanel() {
          {
            setLayout(new BorderLayout());
            setOpaque(selectedAndFocused);
            setBackground(back);

            boolean isGroup = value instanceof ProjectGroupActionGroup;
            boolean isInsideGroup = false;
            if (value instanceof ReopenProjectAction) {
              final String path = ((ReopenProjectAction)value).getProjectPath();
              for (ProjectGroup group : RecentProjectsManager.getInstance().getGroups()) {
                final List<String> projects = group.getProjects();
                if (projects.contains(path)) {
                  isInsideGroup = true;
                  break;
                }
              }
            }

            setBorder(JBUI.Borders.empty(5, 7));
            if (isInsideGroup) {
              add(spacer, BorderLayout.WEST);
            }
            if (isGroup) {
              final ProjectGroup group = ((ProjectGroupActionGroup)value).getGroup();
              name.setText(" " + group.getName());
              name.setIcon(IconUtil.toSize(group.isExpanded() ? UIUtil.getTreeExpandedIcon() : UIUtil.getTreeCollapsedIcon(), JBUI.scale(16), JBUI.scale(16)));
              name.setFont(name.getFont().deriveFont(Font.BOLD));
              add(name);
            }
            else if (value instanceof ReopenProjectAction) {
              final NonOpaquePanel p = new NonOpaquePanel(new BorderLayout());
              name.setText(getTitle2Text(((ReopenProjectAction)value).getTemplatePresentation().getText(), name, JBUI.scale(55)));
              path.setText(getTitle2Text(((ReopenProjectAction)value).getProjectPath(), path, JBUI.scale(isInsideGroup ? 80 : 60)));

              if (!isPathValid((((ReopenProjectAction)value).getProjectPath()))) {
                path.setForeground(ColorUtil.mix(path.getForeground(), JBColor.red, .5));
              }

              p.add(name, BorderLayout.NORTH);
              p.add(path, BorderLayout.SOUTH);

              Image moduleMainIcon = ((ReopenProjectAction)value).getExtensionIcon();
              final JLabel projectIcon = new JLabel("", TargetAWT.to(moduleMainIcon), SwingConstants.LEFT) {
                @Override
                protected void paintComponent(Graphics g) {
                  getIcon().paintIcon(this, g, 0, (getHeight() - getIcon().getIconHeight()) / 2);
                }
              };
              projectIcon.setBorder(JBUI.Borders.emptyRight(8));
              projectIcon.setVerticalAlignment(SwingConstants.CENTER);
              final NonOpaquePanel panel = new NonOpaquePanel(new BorderLayout());
              panel.add(p);
              panel.add(projectIcon, BorderLayout.WEST);
              add(panel);
            }
            AccessibleContextUtil.setCombinedName(this, name, " - ", path);
            AccessibleContextUtil.setCombinedDescription(this, name, " - ", path);
          }

          @Override
          public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, JBUI.scale(44));
          }
        };
      }
    };
  }
}
