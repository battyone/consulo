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

package consulo.container.impl.parser;

import consulo.container.plugin.ComponentConfig;
import org.jetbrains.annotations.NonNls;

import java.util.Collections;
import java.util.List;

public class ComponentManagerConfig {
  //@Tag(APPLICATION_COMPONENTS)
  //@AbstractCollection(surroundWithTag = false)
  public List<ComponentConfig> applicationComponents = Collections.emptyList();

  //@Tag(PROJECT_COMPONENTS)
  //@AbstractCollection(surroundWithTag = false)
  public List<ComponentConfig> projectComponents = Collections.emptyList();

  @NonNls public static final String APPLICATION_COMPONENTS = "application-components";
  @NonNls public static final String PROJECT_COMPONENTS = "project-components";
}
