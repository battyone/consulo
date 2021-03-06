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
package com.intellij.openapi.diff.impl;

import com.intellij.openapi.diff.DiffRequest;
import javax.annotation.Nonnull;

import javax.swing.*;
import java.awt.*;

public class DiffToolbarComponent extends JPanel {
  private final JComponent myWholeComponent;
  private DiffToolbarImpl myToolbar;

  public DiffToolbarComponent(final JComponent wholeComponent) {
    super(new BorderLayout());
    myWholeComponent = wholeComponent;
  }

  public void resetToolbar(@Nonnull DiffRequest.ToolbarAddons toolBar) {
    if (myToolbar != null) remove(myToolbar.getComponent());
    myToolbar = new DiffToolbarImpl();
    myToolbar.setTargetComponent(myWholeComponent);
    myToolbar.reset(toolBar);
    myToolbar.registerKeyboardActions(myWholeComponent);
    add(myToolbar.getComponent(), BorderLayout.CENTER);
    revalidate();
    repaint();
  }

  public DiffToolbarImpl getToolbar() {
    return myToolbar;
  }
}
