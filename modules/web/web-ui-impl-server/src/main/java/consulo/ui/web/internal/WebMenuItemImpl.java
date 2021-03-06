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
package consulo.ui.web.internal;

import consulo.ui.MenuItem;
import consulo.ui.image.Image;
import consulo.ui.web.internal.base.UIComponentWithVaadinComponent;
import consulo.ui.web.internal.base.VaadinComponent;
import consulo.ui.web.servlet.WebImageUrlCache;
import consulo.web.gwt.shared.ui.state.menu.MenuItemState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author VISTALL
 * @since 2019-02-18
 */
public class WebMenuItemImpl extends UIComponentWithVaadinComponent<WebMenuItemImpl.Vaadin> implements MenuItem {
  public static class Vaadin extends VaadinComponent {
    @Override
    public MenuItemState getState() {
      return (MenuItemState)super.getState();
    }
  }

  public WebMenuItemImpl(String text) {
    getVaadinComponent().getState().caption = text;
  }

  @Nonnull
  @Override
  public String getText() {
    return getVaadinComponent().getCaption();
  }

  @Override
  public void setIcon(@Nullable Image icon) {
    Vaadin vaadinComponent = getVaadinComponent();
    vaadinComponent.getState().myImageState = icon == null ? null : WebImageUrlCache.map(icon).getState();
    vaadinComponent.markAsDirty();
  }

  @Nonnull
  @Override
  public WebMenuItemImpl.Vaadin createVaadinComponent() {
    return new Vaadin();
  }
}
