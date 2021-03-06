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
package com.intellij.vcs.log.util;

import com.intellij.openapi.util.Ref;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TroveUtil {
  @Nonnull
  public static <T> Stream<T> streamValues(@Nonnull TIntObjectHashMap<T> map) {
    TIntObjectIterator<T> it = map.iterator();
    return Stream.generate(() -> {
      it.advance();
      return it.value();
    }).limit(map.size());
  }

  @Nonnull
  public static IntStream streamKeys(@Nonnull TIntObjectHashMap<?> map) {
    TIntObjectIterator<?> it = map.iterator();
    return IntStream.generate(() -> {
      it.advance();
      return it.key();
    }).limit(map.size());
  }

  @Nonnull
  public static IntStream stream(@Nonnull TIntArrayList list) {
    if (list.isEmpty()) return IntStream.empty();
    return IntStream.range(0, list.size()).map(list::get);
  }

  @Nonnull
  public static Set<Integer> intersect(@Nonnull TIntHashSet... sets) {
    TIntHashSet result = null;

    Arrays.sort(sets, (set1, set2) -> {
      if (set1 == null) return -1;
      if (set2 == null) return 1;
      return set1.size() - set2.size();
    });
    for (TIntHashSet set : sets) {
      result = intersect(result, set);
    }

    if (result == null) return ContainerUtil.newHashSet();
    return createJavaSet(result);
  }

  @Nullable
  private static TIntHashSet intersect(@Nullable TIntHashSet set1, @Nullable TIntHashSet set2) {
    if (set1 == null) return set2;
    if (set2 == null) return set1;

    TIntHashSet result = new TIntHashSet();

    if (set1.size() < set2.size()) {
      set1.forEach(value -> {
        if (set2.contains(value)) {
          result.add(value);
        }
        return true;
      });
    }
    else {
      set2.forEach(value -> {
        if (set1.contains(value)) {
          result.add(value);
        }
        return true;
      });
    }

    return result;
  }

  @Nonnull
  private static Set<Integer> createJavaSet(@Nonnull TIntHashSet set) {
    Set<Integer> result = ContainerUtil.newHashSet(set.size());
    set.forEach(value -> {
      result.add(value);
      return true;
    });
    return result;
  }

  public static void addAll(@Nonnull TIntHashSet where, @Nonnull TIntHashSet what) {
    what.forEach(value -> {
      where.add(value);
      return true;
    });
  }

  @Nonnull
  public static IntStream stream(@Nonnull TIntHashSet set) {
    TIntIterator it = set.iterator();
    return IntStream.generate(it::next).limit(set.size());
  }

  @Nonnull
  public static <T> List<T> map(@Nonnull TIntHashSet set, @Nonnull IntFunction<T> function) {
    return stream(set).mapToObj(function).collect(Collectors.toList());
  }

  public static void processBatches(@Nonnull IntStream stream, int batchSize, @Nonnull Consumer<TIntHashSet> consumer) {
    Ref<TIntHashSet> batch = new Ref<>(new TIntHashSet());
    stream.forEach(commit -> {
      batch.get().add(commit);
      if (batch.get().size() >= batchSize) {
        try {
          consumer.consume(batch.get());
        }
        finally {
          batch.set(new TIntHashSet());
        }
      }
    });

    if (!batch.get().isEmpty()) {
      consumer.consume(batch.get());
    }
  }
}
