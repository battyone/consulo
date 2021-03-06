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
package com.intellij.openapi.roots.impl.libraries;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.libraries.LibraryTablePresentation;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.util.SmartList;
import consulo.disposer.Disposable;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class LibraryTablesRegistrarImpl extends LibraryTablesRegistrar implements Disposable {
  private static final Map<String, LibraryTable> myLibraryTables = new HashMap<String, LibraryTable>();

  @Override
  @Nonnull
  public LibraryTable getLibraryTable() {
    return ApplicationLibraryTable.getApplicationTable();
  }

  @Override
  @Nonnull
  public LibraryTable getLibraryTable(@Nonnull Project project) {
    return ProjectLibraryTable.getInstance(project);
  }

  @Override
  public LibraryTable getLibraryTableByLevel(String level, @Nonnull Project project) {
    if (LibraryTablesRegistrar.PROJECT_LEVEL.equals(level)) return getLibraryTable(project);
    if (LibraryTablesRegistrar.APPLICATION_LEVEL.equals(level)) return getLibraryTable();
    return myLibraryTables.get(level);
  }

  @Override
  public void registerLibraryTable(@Nonnull LibraryTable libraryTable) {
    String tableLevel = libraryTable.getTableLevel();
    final LibraryTable oldTable = myLibraryTables.put(tableLevel, libraryTable);
    if (oldTable != null) {
      throw new IllegalArgumentException("Library table '" + tableLevel + "' already registered.");
    }
  }

  @Override
  @Nonnull
  public LibraryTable registerLibraryTable(final String customLevel) {
    LibraryTable table = new LibraryTableBase() {
      @Override
      public String getTableLevel() {
        return customLevel;
      }

      @Override
      public LibraryTablePresentation getPresentation() {
        return new LibraryTablePresentation() {
          @Override
          public String getDisplayName(boolean plural) {
            return customLevel;
          }

          @Override
          public String getDescription() {
            throw new UnsupportedOperationException("Method getDescription is not yet implemented in " + getClass().getName());
          }

          @Override
          public String getLibraryTableEditorTitle() {
            throw new UnsupportedOperationException("Method getLibraryTableEditorTitle is not yet implemented in " + getClass().getName());
          }
        };
      }

      @Override
      public boolean isEditable() {
        return false;
      }
    };

    registerLibraryTable(table);
    return table;
  }

  @Override
  public List<LibraryTable> getCustomLibraryTables() {
    return new SmartList<LibraryTable>(myLibraryTables.values());
  }

  @Override
  public void dispose() {
    myLibraryTables.clear();
  }
}