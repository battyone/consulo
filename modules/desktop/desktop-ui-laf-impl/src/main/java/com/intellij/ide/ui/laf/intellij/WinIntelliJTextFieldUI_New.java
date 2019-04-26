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
package com.intellij.ide.ui.laf.intellij;

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil_New;
import com.intellij.ide.ui.laf.darcula.ui.TextFieldWithPopupHandlerUI_New;
import com.intellij.ui.components.panels.Wrapper;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseListener;

/**
 * @author Konstantin Bulenkov
 */
public class WinIntelliJTextFieldUI_New extends TextFieldWithPopupHandlerUI_New {
  public static final String HOVER_PROPERTY = "JTextField.hover";

  private MouseListener hoverListener;

  @SuppressWarnings({"MethodOverridesStaticMethodOfSuperclass", "UnusedDeclaration"})
  public static ComponentUI createUI(JComponent c) {
    return new WinIntelliJTextFieldUI_New();
  }

  @Override
  public void installListeners() {
    super.installListeners();
    hoverListener = new DarculaUIUtil_New.MouseHoverPropertyTrigger(getComponent(), HOVER_PROPERTY);
    getComponent().addMouseListener(hoverListener);
  }

  @Override
  public void uninstallListeners() {
    super.uninstallListeners();
    if (hoverListener != null) {
      getComponent().removeMouseListener(hoverListener);
    }
  }

  @Override
  protected void paintBackground(Graphics g) {
    JTextComponent c = getComponent();
    if (UIUtil.getParentOfType(JComboBox.class, c) != null) return;

    Graphics2D g2 = (Graphics2D)g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);

      Container parent = c.getParent();
      if (c.isOpaque() && parent != null) {
        g2.setColor(parent.getBackground());
        g2.fillRect(0, 0, c.getWidth(), c.getHeight());
      }

      if (c.getBorder() instanceof WinIntelliJTextBorder_New) {
        paintTextFieldBackground(c, g2);
      }
      else if (c.isOpaque()) {
        super.paintBackground(g);
      }
    }
    finally {
      g2.dispose();
    }
  }

  static void paintTextFieldBackground(JComponent c, Graphics2D g2) {
    g2.setColor(c.isEnabled() ? c.getBackground() : UIManager.getColor("Button.background"));

    if (!c.isEnabled()) {
      g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
    }

    Rectangle r = new Rectangle(c.getSize());
    JBInsets.removeFrom(r, JBUI.insets(2));
    adjustInWrapperRect(r, c);

    g2.fill(r);
  }

  static void adjustInWrapperRect(Rectangle r, Component c) {
    if (UIUtil.getParentOfType(Wrapper.class, c) != null && TextFieldWithPopupHandlerUI_New.isSearchFieldWithHistoryPopup(c)) {
      int delta = c.getHeight() - c.getPreferredSize().height;
      if (delta > 0) {
        delta -= delta % 2 == 0 ? 0 : 1;
        JBInsets.removeFrom(r, JBUI.insets(delta / 2, 0));
      }
    }
  }

  @Override
  protected int getMinimumHeight(int textHeight) {
    JComponent c = getComponent();
    Insets i = c.getInsets();
    return isComboBoxEditor(c) || UIUtil.getParentOfType(JSpinner.class, c) != null ? textHeight : WinIntelliJTextBorder_New.MINIMUM_HEIGHT.get() + i.top + i.bottom;
  }

  public static boolean isComboBoxEditor(Component c) {
    return UIUtil.getParentOfType(JComboBox.class, c) != null;
  }

  @Override
  protected int getSearchIconGap() {
    return 0;
  }

  @Override
  protected Insets getDefaultMargins() {
    Component c = getComponent();
    return DarculaUIUtil_New.isCompact(c) || DarculaUIUtil_New.isTableCellEditor(c) ? JBUI.insets(0, 3) : JBUI.insets(2, 6);
  }

  @Override
  protected int getClearIconGap() {
    return JBUI.scale(3);
  }
}
