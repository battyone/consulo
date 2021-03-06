/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package com.intellij.ide.todo;

import com.intellij.psi.search.IndexPattern;
import com.intellij.psi.search.IndexPatternProvider;
import javax.annotation.Nonnull;
import javax.inject.Inject;

/**
 * @author yole
 */
public class TodoIndexPatternProvider implements IndexPatternProvider {
  private final TodoConfiguration myConfiguration;

  public static TodoIndexPatternProvider getInstance() {
    for (IndexPatternProvider provider : EP_NAME.getExtensionList()) {
      if (provider instanceof TodoIndexPatternProvider) {
        return (TodoIndexPatternProvider) provider;
      }
    }
    assert false: "Couldn't find self in extensions list";
    return null;
  }

  @Inject
  public TodoIndexPatternProvider(TodoConfiguration configuration) {
    myConfiguration = configuration;
  }

  @Override
  @Nonnull
  public IndexPattern[] getIndexPatterns() {
    return myConfiguration.getIndexPatterns();
  }
}
