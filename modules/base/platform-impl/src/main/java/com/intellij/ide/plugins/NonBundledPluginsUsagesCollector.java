/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.ide.plugins;

import com.intellij.internal.statistic.UsagesCollector;
import com.intellij.internal.statistic.beans.GroupDescriptor;
import com.intellij.internal.statistic.beans.UsageDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import consulo.container.plugin.PluginIds;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;
import java.util.Set;

public class NonBundledPluginsUsagesCollector extends UsagesCollector {
  private static final String GROUP_ID = "non-bundled-plugins";

  @Override
  @Nonnull
  public GroupDescriptor getGroupId() {
    return GroupDescriptor.create(GROUP_ID, GroupDescriptor.HIGHER_PRIORITY);
  }

  @Override
  @Nonnull
  public Set<UsageDescriptor> getUsages(@Nullable Project project) {
    final IdeaPluginDescriptor[] plugins = PluginManagerCore.getPlugins();
    final List<IdeaPluginDescriptor> nonBundledEnabledPlugins = ContainerUtil.filter(plugins, new Condition<IdeaPluginDescriptor>() {
      @Override
      public boolean value(final IdeaPluginDescriptor d) {
        return d.isEnabled() && !PluginIds.isPlatformPlugin(d.getPluginId()) && d.getPluginId() != null;
      }
    });

    return ContainerUtil.map2Set(nonBundledEnabledPlugins, new Function<IdeaPluginDescriptor, UsageDescriptor>() {
      @Override
      public UsageDescriptor fun(IdeaPluginDescriptor descriptor) {
        return new UsageDescriptor(descriptor.getPluginId().getIdString(), 1);
      }
    });
  }

}
