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
package com.intellij.codeInsight.lookup;

import com.intellij.openapi.util.Iconable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.meta.PsiMetaData;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.IconUtil;
import consulo.annotation.access.RequiredReadAction;
import consulo.ide.IconDescriptorUpdaters;
import consulo.ui.image.Image;
import consulo.ui.image.ImageEffects;

import javax.annotation.Nullable;

/**
 * @author peter
 */
public class DefaultLookupItemRenderer extends LookupElementRenderer<LookupItem>{
  public static final DefaultLookupItemRenderer INSTANCE = new DefaultLookupItemRenderer();

  @Override
  public void renderElement(final LookupItem item, final LookupElementPresentation presentation) {
    presentation.setIcon(getRawIcon(item, presentation.isReal()));

    presentation.setItemText(getName(item));
    presentation.setItemTextBold(item.getAttribute(LookupItem.HIGHLIGHTED_ATTR) != null);
    presentation.setTailText(getText2(item), item.getAttribute(LookupItem.TAIL_TEXT_SMALL_ATTR) != null);
    presentation.setTypeText(getText3(item), null);
  }

  @Nullable
  @RequiredReadAction
  public static Image getRawIcon(final LookupElement item, boolean real) {
    final Image icon = _getRawIcon(item, real);
    if (icon != null && icon.getHeight() > IconUtil.getDefaultNodeIconSize()) {
      return ImageEffects.resize(icon, icon.getWidth(), IconUtil.getDefaultNodeIconSize());
    }
    return icon;
  }

  @Nullable
  @RequiredReadAction
  private static Image _getRawIcon(LookupElement item, boolean real) {
    if (item instanceof LookupItem) {
      Image icon = (Image)((LookupItem)item).getAttribute(LookupItem.ICON_ATTR);
      if (icon != null) return icon;
    }

    Object o = item.getObject();

    if (!real) {
      if (item.getObject() instanceof String) {
        return Image.empty();
      }

      return Image.empty(IconUtil.getDefaultNodeIconSize() * 2, IconUtil.getDefaultNodeIconSize());
    }

    if (o instanceof Iconable && !(o instanceof PsiElement)) {
      return ((Iconable)o).getIcon(Iconable.ICON_FLAG_VISIBILITY);
    }

    final PsiElement element = item.getPsiElement();
    if (element != null && element.isValid()) {
      return IconDescriptorUpdaters.getIcon(element, Iconable.ICON_FLAG_VISIBILITY);
    }
    return null;
  }


  @Nullable
  private static String getText3(final LookupItem item) {
    Object o = item.getObject();
    String text;
    if (o instanceof LookupValueWithUIHint) {
      text = ((LookupValueWithUIHint)o).getTypeHint();
    }
    else {
      text = (String)item.getAttribute(LookupItem.TYPE_TEXT_ATTR);
    }
    return text;
  }

  private static String getText2(final LookupItem item) {
    return (String)item.getAttribute(LookupItem.TAIL_TEXT_ATTR);
  }

  private static String getName(final LookupItem item){
    final String presentableText = item.getPresentableText();
    if (presentableText != null) return presentableText;
    final Object o = item.getObject();
    String name = null;
    if (o instanceof PsiElement) {
      final PsiElement element = (PsiElement)o;
      if (element.isValid()) {
        name = PsiUtilCore.getName(element);
      }
    }
    else if (o instanceof PsiMetaData) {
      name = ((PsiMetaData)o).getName();
    }
    else if (o instanceof PresentableLookupValue ) {
      name = ((PresentableLookupValue)o).getPresentation();
    }
    else {
      name = String.valueOf(o);
    }
    if (name == null){
      name = "";
    }

    return name;
  }

}
