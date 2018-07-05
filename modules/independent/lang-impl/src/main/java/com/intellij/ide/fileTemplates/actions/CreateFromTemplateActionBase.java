/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.ide.fileTemplates.actions;

import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.ide.IdeView;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.ui.CreateFromTemplateDialog;
import com.intellij.ide.util.DirectoryChooserUtil;
import com.intellij.ide.util.EditorHelper;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import consulo.annotations.RequiredDispatchThread;
import consulo.ui.image.Image;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.intellij.util.ObjectUtils.notNull;

public abstract class CreateFromTemplateActionBase extends AnAction {
  public CreateFromTemplateActionBase(final String title, final String description, final Image icon) {
    super(title, description, icon);
  }

  @RequiredDispatchThread
  @Override
  public final void actionPerformed(@Nonnull AnActionEvent e) {
    DataContext dataContext = e.getDataContext();
    IdeView view = dataContext.getData(LangDataKeys.IDE_VIEW);
    if (view == null) return;
    PsiDirectory dir = getTargetDirectory(dataContext, view);
    if (dir == null) return;
    Project project = dir.getProject();

    FileTemplate selectedTemplate = getTemplate(project, dir);
    if (selectedTemplate != null) {
      AnAction action = getReplacedAction(selectedTemplate);
      if (action != null) {
        action.actionPerformed(e);
      }
      else {
        FileTemplateManager.getInstance(project).addRecentName(selectedTemplate.getName());
        AttributesDefaults defaults = getAttributesDefaults(dataContext);
        Map<String, Object> properties = defaults != null ? defaults.getDefaultProperties() : null;
        CreateFromTemplateDialog dialog = new CreateFromTemplateDialog(dir, selectedTemplate, defaults, properties);
        PsiElement createdElement = dialog.create();
        if (createdElement != null) {
          elementCreated(dialog, createdElement);
          view.selectElement(createdElement);
          if (selectedTemplate.isLiveTemplateEnabled() && createdElement instanceof PsiFile) {
            Map<String, String> defaultValues = getLiveTemplateDefaults(dataContext, ((PsiFile)createdElement));
            startLiveTemplate((PsiFile)createdElement, notNull(defaultValues, Collections.emptyMap()));
          }
        }
      }
    }
  }

  public static void startLiveTemplate(@Nonnull PsiFile file) {
    startLiveTemplate(file, Collections.emptyMap());
  }

  public static void startLiveTemplate(@Nonnull PsiFile file, @Nonnull Map<String, String> defaultValues) {
    Editor editor = EditorHelper.openInEditor(file);
    if (editor == null) return;

    TemplateImpl template = new TemplateImpl("", file.getText(), "");
    template.setInline(true);
    int count = template.getSegmentsCount();
    if (count == 0) return;

    Set<String> variables = new HashSet<>();
    for (int i = 0; i < count; i++) {
      variables.add(template.getSegmentName(i));
    }
    variables.removeAll(TemplateImpl.INTERNAL_VARS_SET);
    for (String variable : variables) {
      String defaultValue = defaultValues.getOrDefault(variable, variable);
      template.addVariable(variable, null, '"' + defaultValue + '"', true);
    }

    Project project = file.getProject();
    WriteCommandAction.runWriteCommandAction(project, () -> editor.getDocument().setText(template.getTemplateText()));

    editor.getCaretModel().moveToOffset(0);  // ensures caret at the start of the template
    TemplateManager.getInstance(project).startTemplate(editor, template);
  }

  @Nullable
  protected PsiDirectory getTargetDirectory(DataContext dataContext, IdeView view) {
    return DirectoryChooserUtil.getOrChooseDirectory(view);
  }

  protected abstract FileTemplate getTemplate(Project project, PsiDirectory dir);

  @Nullable
  protected AnAction getReplacedAction(FileTemplate selectedTemplate) {
    return null;
  }

  @Nullable
  protected AttributesDefaults getAttributesDefaults(DataContext dataContext) {
    return null;
  }

  protected void elementCreated(CreateFromTemplateDialog dialog, PsiElement createdElement) { }

  @Nullable
  protected Map<String, String> getLiveTemplateDefaults(DataContext dataContext, @Nonnull PsiFile file) {
    return null;
  }
}