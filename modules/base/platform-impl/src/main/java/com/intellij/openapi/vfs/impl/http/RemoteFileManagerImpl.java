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
package com.intellij.openapi.vfs.impl.http;

import consulo.disposer.Disposable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.ex.http.HttpVirtualFileListener;
import com.intellij.util.EventDispatcher;
import consulo.disposer.Disposer;
import gnu.trove.THashMap;
import javax.annotation.Nonnull;
import javax.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author nik
 */
@Singleton
public class RemoteFileManagerImpl extends RemoteFileManager implements Disposable {
  private final LocalFileStorage myStorage;
  private final Map<Pair<Boolean, String>, VirtualFileImpl> myRemoteFiles;
  private final EventDispatcher<HttpVirtualFileListener> myDispatcher = EventDispatcher.create(HttpVirtualFileListener.class);
  private final List<RemoteContentProvider> myProviders = new ArrayList<RemoteContentProvider>();
  private final DefaultRemoteContentProvider myDefaultRemoteContentProvider;

  public RemoteFileManagerImpl() {
    myStorage = new LocalFileStorage();
    myRemoteFiles = new THashMap<Pair<Boolean, String>, VirtualFileImpl>();
    myDefaultRemoteContentProvider = new DefaultRemoteContentProvider();
  }

  @Nonnull
  public RemoteContentProvider findContentProvider(final @Nonnull String url) {
    for (RemoteContentProvider provider : myProviders) {
      if (provider.canProvideContent(url)) {
        return provider;
      }
    }
    return myDefaultRemoteContentProvider;
  }

  public synchronized VirtualFileImpl getOrCreateFile(final @Nonnull String url, final @Nonnull String path, final boolean directory) throws IOException {
    Pair<Boolean, String> key = Pair.create(directory, url);
    VirtualFileImpl file = myRemoteFiles.get(key);

    if (file == null) {
      if (!directory) {
        RemoteFileInfo fileInfo = new RemoteFileInfo(url, this);
        file = new VirtualFileImpl(getHttpFileSystem(url), path, fileInfo);
        fileInfo.addDownloadingListener(new MyDownloadingListener(file));
      }
      else {
        file = new VirtualFileImpl(getHttpFileSystem(url), path, null);
      }
      myRemoteFiles.put(key, file);
    }
    return file;
  }

  private static HttpFileSystemBase getHttpFileSystem(@Nonnull String url) {
    return url.startsWith(HttpsFileSystem.HTTPS_PROTOCOL) ? HttpsFileSystem.getHttpsInstance() : HttpFileSystemImpl.getInstanceImpl();
  }

  @Override
  public void addRemoteContentProvider(@Nonnull final RemoteContentProvider provider, @Nonnull Disposable parentDisposable) {
    addRemoteContentProvider(provider);
    Disposer.register(parentDisposable, new Disposable() {
      @Override
      public void dispose() {
        removeRemoteContentProvider(provider);
      }
    });
  }

  @Override
  public void addRemoteContentProvider(@Nonnull RemoteContentProvider provider) {
    myProviders.add(provider);
  }

  @Override
  public void removeRemoteContentProvider(@Nonnull RemoteContentProvider provider) {
    myProviders.remove(provider);
  }

  @Override
  public void addFileListener(@Nonnull final HttpVirtualFileListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void addFileListener(@Nonnull final HttpVirtualFileListener listener, @Nonnull final Disposable parentDisposable) {
    addFileListener(listener);
    Disposer.register(parentDisposable, new Disposable() {
      @Override
      public void dispose() {
        removeFileListener(listener);
      }
    });
  }

  @Override
  public void removeFileListener(@Nonnull final HttpVirtualFileListener listener) {
    myDispatcher.removeListener(listener);
  }

  public void fireFileDownloaded(@Nonnull VirtualFile file) {
    myDispatcher.getMulticaster().fileDownloaded(file);
  }

  public LocalFileStorage getStorage() {
    return myStorage;
  }

  @Override
  public void dispose() {
    myStorage.deleteDownloadedFiles();
  }

  private class MyDownloadingListener extends FileDownloadingAdapter {
    private final VirtualFileImpl myFile;

    public MyDownloadingListener(final VirtualFileImpl file) {
      myFile = file;
    }

    @Override
    public void fileDownloaded(final VirtualFile localFile) {
      fireFileDownloaded(myFile);
    }
  }
}
