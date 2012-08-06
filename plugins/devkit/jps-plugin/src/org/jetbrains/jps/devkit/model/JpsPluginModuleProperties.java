/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package org.jetbrains.jps.devkit.model;

import org.jetbrains.jps.model.JpsElementProperties;

/**
 * @author nik
 */
public class JpsPluginModuleProperties extends JpsElementProperties {
  private final String myPluginXmlUrl;

  public JpsPluginModuleProperties(String pluginXmlUrl) {
    myPluginXmlUrl = pluginXmlUrl;
  }

  public JpsPluginModuleProperties(JpsPluginModuleProperties properties) {
    myPluginXmlUrl = properties.getPluginXmlUrl();
  }

  public String getPluginXmlUrl() {
    return myPluginXmlUrl;
  }
}