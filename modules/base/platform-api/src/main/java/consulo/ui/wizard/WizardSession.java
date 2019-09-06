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
package consulo.ui.wizard;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * @author VISTALL
 * @since 2019-08-20
 */
public final class WizardSession<CONTEXT> {
  private final List<WizardStep<CONTEXT>> mySteps;
  private final CONTEXT myContext;

  private int myCurrentStepIndex = -1;

  private int myPreviusStepIndex = -1;

  public WizardSession(@Nonnull CONTEXT context, @Nonnull List<WizardStep<CONTEXT>> steps) {
    myContext = context;
    mySteps = new ArrayList<>(steps);
  }

  public boolean hasNext() {
    return findNextStepIndex() != -1;
  }

  @Nonnull
  public WizardStep<CONTEXT> next() {
    if (!hasNext()) {
      throw new IllegalArgumentException("There no visible next step");
    }

    int oldIndex = myCurrentStepIndex;

    if (oldIndex != -1) {
      WizardStep<CONTEXT> prevStep = mySteps.get(oldIndex);

      prevStep.onStepLeave(myContext);
    }

    int nextStepIndex = findNextStepIndex();

    WizardStep<CONTEXT> step = mySteps.get(nextStepIndex);

    myCurrentStepIndex = nextStepIndex;
    myPreviusStepIndex = oldIndex;

    step.onStepEnter(myContext);
    return step;
  }

  @Nonnull
  public WizardStep<CONTEXT> prev() {
    if (myPreviusStepIndex == -1) {
      throw new IllegalArgumentException("There no visible prev step");
    }

    WizardStep<CONTEXT> currentStep = mySteps.get(myCurrentStepIndex);

    currentStep.onStepLeave(myContext);

    myCurrentStepIndex = myPreviusStepIndex;

    WizardStep<CONTEXT> step = mySteps.get(myPreviusStepIndex);

    step.onStepEnter(myContext);

    myPreviusStepIndex = findPrevStepIndex();

    return step;
  }

  @Nonnull
  public WizardStep<CONTEXT> current() {
    if (myCurrentStepIndex == -1) {
      throw new IllegalArgumentException();
    }
    return mySteps.get(myCurrentStepIndex);
  }

  private int findNextStepIndex() {
    int from = myCurrentStepIndex + 1;

    for (int i = from; i < mySteps.size(); i++) {
      WizardStep<CONTEXT> step = mySteps.get(i);
      if (step.isVisible(myContext)) {
        return i;
      }
    }
    return -1;
  }

  private int findPrevStepIndex() {
    int from = myPreviusStepIndex - 1;
    for (int i = from; i != -1; i--) {
      WizardStep<CONTEXT> step = mySteps.get(i);
      if (step.isVisible(myContext)) {
        return i;
      }
    }

    return -1;
  }

  public void dispose() {
    for (WizardStep<CONTEXT> step : mySteps) {
      step.disposeUIResources();
    }

    mySteps.clear();
  }

  public int getCurrentStepIndex() {
    return myCurrentStepIndex;
  }
}
