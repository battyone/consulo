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
package com.intellij.openapi.roots.ui.configuration.artifacts.sourceItems;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.elements.PackagingElement;
import com.intellij.packaging.impl.elements.ArtifactElementType;
import com.intellij.packaging.ui.*;
import com.intellij.ui.SimpleTextAttributes;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author nik
 */
public class ArtifactsSourceItemsProvider extends PackagingSourceItemsProvider {
  @Override
  @Nonnull
  public Collection<? extends PackagingSourceItem> getSourceItems(@Nonnull ArtifactEditorContext editorContext,
                                                                  @Nonnull Artifact artifact,
                                                                  @Nullable PackagingSourceItem parent) {
    if (parent == null) {
      if (!ArtifactElementType.getAvailableArtifacts(editorContext, artifact, true).isEmpty()) {
        return Collections.singletonList(new ArtifactsGroupSourceItem());
      }
    }
    else if (parent instanceof ArtifactsGroupSourceItem) {
      List<PackagingSourceItem> items = new ArrayList<PackagingSourceItem>();
      for (Artifact another : ArtifactElementType.getAvailableArtifacts(editorContext, artifact, true)) {
        items.add(new ArtifactSourceItem(another));
      }
      return items;
    }
    return Collections.emptyList();
  }

  private static class ArtifactsGroupSourceItem extends PackagingSourceItem {
    private ArtifactsGroupSourceItem() {
      super(false);
    }

    public boolean equals(Object obj) {
      return obj instanceof ArtifactsGroupSourceItem;
    }

    public int hashCode() {
      return 0;
    }

    @Override
    public SourceItemPresentation createPresentation(@Nonnull ArtifactEditorContext context) {
      return new ArtifactsGroupPresentation();
    }

    @Override
    @Nonnull
    public List<? extends PackagingElement<?>> createElements(@Nonnull ArtifactEditorContext context) {
      return Collections.emptyList();
    }

    private static class ArtifactsGroupPresentation extends SourceItemPresentation {
      @Override
      public String getPresentableName() {
        return "Artifacts";
      }

      @Override
      public void render(@Nonnull PresentationData presentationData, SimpleTextAttributes mainAttributes,
                         SimpleTextAttributes commentAttributes) {
        presentationData.setIcon(AllIcons.Nodes.Artifact);
        presentationData.addText("Artifacts", mainAttributes);
      }

      @Override
      public int getWeight() {
        return SourceItemWeights.ARTIFACTS_GROUP_WEIGHT;
      }
    }
  }
}
