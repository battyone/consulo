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
package consulo.test.light.impl;

import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import com.intellij.openapi.extensions.impl.UndefinedPluginDescriptor;
import consulo.injecting.InjectingContainerBuilder;

import javax.annotation.Nonnull;
import java.lang.reflect.Modifier;

/**
 * @author VISTALL
 * @since 3/12/19
 */
public abstract class LightExtensionRegistrator {
  protected <T> void registerExtension(ExtensionsAreaImpl area, ExtensionPointName<T> extensionPointName, T value) {
    ExtensionPointImpl<T> point = (ExtensionPointImpl<T>)area.getExtensionPoint(extensionPointName);
    point.registerExtensionAdapter(new SimpleInstanceComponentAdapter<>(value));
  }

  protected void registerExtensionPoint(ExtensionsAreaImpl area, ExtensionPointName name, Class aClass) {
    ExtensionPoint.Kind kind = aClass.isInterface() || (aClass.getModifiers() & Modifier.ABSTRACT) != 0 ? ExtensionPoint.Kind.INTERFACE : ExtensionPoint.Kind.BEAN_CLASS;
    area.registerExtensionPoint(name.getName(), aClass.getName(), new UndefinedPluginDescriptor(), kind);
  }

  public abstract void registerExtensionPointsAndExtensions(@Nonnull ExtensionsAreaImpl area);

  public abstract void registerServices(@Nonnull InjectingContainerBuilder builder);
}
