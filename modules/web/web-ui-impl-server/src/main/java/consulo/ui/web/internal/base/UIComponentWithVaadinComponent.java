/*
 * Copyright 2013-2019 consulo.io
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
package consulo.ui.web.internal.base;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Key;
import com.vaadin.ui.AbstractComponent;
import consulo.ui.Component;
import consulo.ui.RequiredUIAccess;
import consulo.ui.impl.UIDataObject;
import consulo.ui.shared.Size;
import consulo.ui.shared.border.BorderPosition;
import consulo.ui.shared.border.BorderStyle;
import consulo.ui.style.ColorKey;
import consulo.ui.web.internal.TargetVaddin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EventListener;
import java.util.function.Function;

/**
 * @author VISTALL
 * @since 2019-02-17
 */
public abstract class UIComponentWithVaadinComponent<T extends AbstractComponent> implements Component, DataObjectHolder {
  private T myVaadinComponent;

  public UIComponentWithVaadinComponent() {
    myVaadinComponent = create();

    ((ComponentHolder)myVaadinComponent).setComponent(this);
  }

  @Nonnull
  public abstract T create();

  @Nonnull
  protected T getVaadinComponent() {
    return myVaadinComponent;
  }

  @Override
  @Nonnull
  public UIDataObject dataObject() {
    UIDataObject data = (UIDataObject)myVaadinComponent.getData();
    if (data == null) {
      myVaadinComponent.setData(data = new UIDataObject());
    }
    return data;
  }

  @Nullable
  @Override
  public Component getParentComponent() {
    return TargetVaddin.from(myVaadinComponent.getParent());
  }

  @RequiredUIAccess
  @Override
  public void setSize(@Nonnull Size size) {

  }

  @Override
  public <T> void putUserData(@Nonnull Key<T> key, @Nullable T value) {
    dataObject().putUserData(key, value);
  }

  @Nullable
  @Override
  public <T> T getUserData(@Nonnull Key<T> key) {
    return dataObject().getUserData(key);
  }

  @Override
  @Nonnull
  public Disposable addUserDataProvider(@Nonnull Function<Key<?>, Object> function) {
    return dataObject().addUserDataProvider(function);
  }

  @Nonnull
  @Override
  public <T extends EventListener> Disposable addListener(@Nonnull Class<T> eventClass, @Nonnull T listener) {
    return dataObject().addListener(eventClass, listener);
  }

  @Nonnull
  @Override
  public <T extends EventListener> T getListenerDispatcher(@Nonnull Class<T> eventClass) {
    return dataObject().getDispatcher(eventClass);
  }

  @RequiredUIAccess
  @Override
  public void addBorder(@Nonnull BorderPosition borderPosition, BorderStyle borderStyle, ColorKey colorKey, int width) {
    dataObject().addBorder(borderPosition, borderStyle, colorKey, width);

    bordersChanged();
  }

  @RequiredUIAccess
  @Override
  public void removeBorder(@Nonnull BorderPosition borderPosition) {
    dataObject().removeBorder(borderPosition);

    bordersChanged();
  }

  @Override
  public boolean isVisible() {
    return myVaadinComponent.isVisible();
  }

  @RequiredUIAccess
  @Override
  public void setVisible(boolean value) {
    myVaadinComponent.setVisible(value);
  }

  @Override
  public boolean isEnabled() {
    return myVaadinComponent.isEnabled();
  }

  @RequiredUIAccess
  @Override
  public void setEnabled(boolean value) {
    myVaadinComponent.setEnabled(value);
  }

  public void bordersChanged() {
  }
}
