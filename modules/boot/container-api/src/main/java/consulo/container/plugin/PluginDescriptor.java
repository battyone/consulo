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
package consulo.container.plugin;

import consulo.annotation.DeprecationInfo;
import consulo.util.nodep.xml.node.SimpleXmlElement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * @author VISTALL
 * @since 2019-07-17
 */
public interface PluginDescriptor {
  PluginDescriptor[] EMPTY_ARRAY = new PluginDescriptor[0];

  @Nonnull
  PluginId getPluginId();

  @Nonnull
  ClassLoader getPluginClassLoader();

  @Nullable
  File getPath();

  @Nullable
  String getDescription();

  String getChangeNotes();

  String getName();

  @Nonnull
  PluginId[] getDependentPluginIds();

  @Nonnull
  PluginId[] getOptionalDependentPluginIds();

  String getVendor();

  @Nullable
  String getVersion();

  @Nullable
  String getPlatformVersion();

  @Nullable
  String getResourceBundleBaseName();

  @Nullable
  String getLocalize();

  String getCategory();

  @Nonnull
  List<SimpleXmlElement> getActionsDescriptionElements();

  @Nonnull
  List<ComponentConfig> getAppComponents();

  @Nonnull
  List<ComponentConfig> getProjectComponents();

  String getVendorEmail();

  String getVendorUrl();

  String getUrl();

  @Nonnull
  Collection<HelpSetPath> getHelpSets();

  String getDownloads();

  @Nonnull
  List<SimpleExtension> getSimpleExtensions();

  @Nonnull
  List<PluginListenerDescriptor> getApplicationListeners();

  @Nonnull
  List<PluginListenerDescriptor> getProjectListeners();

  @Nonnull
  List<PluginListenerDescriptor> getModuleListeners();

  @Deprecated
  @DeprecationInfo("This method is obsolete now. Bundled plugin is always platform modules - it can't load plugins")
  boolean isBundled();

  boolean isEnabled();

  void setEnabled(boolean enabled);

  boolean isLoaded();

  boolean isDeleted();

  boolean isExperimental();
}
