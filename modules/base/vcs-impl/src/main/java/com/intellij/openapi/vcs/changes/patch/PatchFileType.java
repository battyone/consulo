/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 17.11.2006
 * Time: 17:36:42
 */
package com.intellij.openapi.vcs.changes.patch;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import consulo.ui.image.Image;
import org.jetbrains.annotations.NonNls;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;

public class PatchFileType implements FileType {
  public static final PatchFileType INSTANCE = new PatchFileType();
  public static final String NAME = "PATCH";

  @Override
  @Nonnull
  @NonNls
  public String getId() {
    return NAME;
  }

  @Override
  @Nonnull
  public String getDescription() {
    return VcsBundle.message("patch.file.type.description");
  }

  @Override
  @Nonnull
  @NonNls
  public String getDefaultExtension() {
    return "patch";
  }

  @Override
  @Nullable
  public Image getIcon() {
    return AllIcons.Nodes.Pointcut;
  }

  public static boolean isPatchFile(@javax.annotation.Nullable VirtualFile vFile) {
    return vFile != null && vFile.getFileType() == PatchFileType.INSTANCE;
  }

  public static boolean isPatchFile(@Nonnull File file) {
    return isPatchFile(VfsUtil.findFileByIoFile(file, true));
  }
}
