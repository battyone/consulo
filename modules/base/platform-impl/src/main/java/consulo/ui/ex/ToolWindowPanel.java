/*
 * Copyright 2013-2017 consulo.io
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
package consulo.ui.ex;

import com.intellij.openapi.wm.impl.WindowInfoImpl;
import com.intellij.openapi.wm.impl.commands.FinalizableCommand;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * @author VISTALL
 * @since 12-Oct-17
 * <p>
 * TODO [VISTALL] create method getComponent() with type {@link consulo.ui.Component}
 */
public interface ToolWindowPanel {
  @Nonnull
  FinalizableCommand createAddButtonCmd(final ToolWindowStripeButton button, @Nonnull WindowInfoImpl info, @Nonnull Comparator<ToolWindowStripeButton> comparator, @Nonnull Runnable finishCallBack);

  @Nonnull
  FinalizableCommand createUpdateButtonPositionCmd(@Nonnull String id, @Nonnull Runnable finishCallback);

  @Nonnull
  FinalizableCommand createSetEditorComponentCmd(Object component, @Nonnull Runnable finishCallBack);

  @Nullable
  FinalizableCommand createRemoveButtonCmd(@Nonnull String id, @Nonnull Runnable finishCallBack);

  @Nonnull
  FinalizableCommand createRemoveDecoratorCmd(@Nonnull String id, final boolean dirtyMode, @Nonnull Runnable finishCallBack);

  @Nonnull
  FinalizableCommand createAddDecoratorCmd(@Nonnull ToolWindowInternalDecorator decorator, @Nonnull WindowInfoImpl info, final boolean dirtyMode, @Nonnull Runnable finishCallBack);
}
