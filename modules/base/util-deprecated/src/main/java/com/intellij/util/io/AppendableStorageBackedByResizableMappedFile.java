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
package com.intellij.util.io;

import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.util.Processor;
import javax.annotation.Nonnull;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;

public class AppendableStorageBackedByResizableMappedFile extends ResizeableMappedFile {
  private final MyDataIS myReadStream;
  private byte[] myAppendBuffer;
  private volatile int myFileLength;
  private volatile int myBufferPosition;
  private static final int ourAppendBufferLength = 4096;

  public AppendableStorageBackedByResizableMappedFile(final File file, int initialSize, @Nullable PagedFileStorage.StorageLockContext lockContext, int pageSize, boolean valuesAreBufferAligned)
          throws IOException {
    super(file, initialSize, lockContext, pageSize, valuesAreBufferAligned);
    myReadStream = new MyDataIS(this);
    myFileLength = (int)length();
    myCompressedAppendableFile = /*CompressedAppendableFile.ENABLED &&*/ false ? new MyCompressedAppendableFile(file) : null;
  }

  private void flushKeyStoreBuffer() {
    if (myBufferPosition > 0) {
      put(myFileLength, myAppendBuffer, 0, myBufferPosition);
      myFileLength += myBufferPosition;
      myBufferPosition = 0;
    }
  }

  @Override
  public void force() {
    flushKeyStoreBuffer();
    if (myCompressedAppendableFile != null) myCompressedAppendableFile.force();
    super.force();
  }

  @Override
  public void close() {
    flushKeyStoreBuffer();
    if (myCompressedAppendableFile != null) myCompressedAppendableFile.dispose();
    super.close();
  }

  private final CompressedAppendableFile myCompressedAppendableFile;
  private static final boolean testMode = false;

  public <Data> Data read(final int addr, KeyDescriptor<Data> descriptor) throws IOException {
    Data tempData = null;

    if (myCompressedAppendableFile != null) {
      tempData = myCompressedAppendableFile.read(addr, descriptor);
      if (!testMode) return tempData;
    }

    if (myFileLength <= addr) {
      Data data = descriptor.read(new DataInputStream(new UnsyncByteArrayInputStream(myAppendBuffer, addr - myFileLength, myBufferPosition)));
      assert tempData == null || descriptor.isEqual(data, tempData);
      return data;
    }
    // we do not need to flushKeyBuffer since we store complete records
    myReadStream.setup(addr, myFileLength);
    Data data = descriptor.read(myReadStream);
    assert tempData == null || descriptor.isEqual(data, tempData);
    return data;
  }

  public <Data> boolean processAll(@Nonnull Processor<? super Data> processor, @Nonnull KeyDescriptor<Data> descriptor) throws IOException {
    assert !isDirty();
    DataInputStream keysStream2 = myCompressedAppendableFile != null ? myCompressedAppendableFile.getStream(0) : null;
    if (!testMode && keysStream2 != null) {
      getPagedFileStorage().lock(); // todo support it inside myCompressedAppendableFile to avoid filling the cache
      try {
        try {
          while (true) {
            Data key = descriptor.read(keysStream2);
            if (!processor.process(key)) return false;
          }
        }
        catch (EOFException e) {
          // Done
        }
        return true;
      }
      finally {
        getPagedFileStorage().unlock();
        keysStream2.close();
      }
    }

    if (myFileLength == 0) return true;

    try (DataInputStream keysStream = new DataInputStream(new BufferedInputStream(new LimitedInputStream(new FileInputStream(getPagedFileStorage().getFile()), myFileLength) {
      @Override
      public int available() {
        return remainingLimit();
      }
    }, 32768))) {
      try {
        while (true) {
          Data key = descriptor.read(keysStream);
          if (keysStream2 != null) {
            Data tempKey = descriptor.read(keysStream2);
            assert descriptor.isEqual(key, tempKey);
          }
          if (!processor.process(key)) return false;
        }
      }
      catch (EOFException e) {
        // Done
      }
      return true;
    }
  }

  public int getCurrentLength() {
    int currentLength;
    if (myCompressedAppendableFile != null) {
      currentLength = (int)myCompressedAppendableFile.length();
      if (testMode) {
        assert currentLength == myBufferPosition + myFileLength;
      }
    }
    else {
      currentLength = myBufferPosition + myFileLength;
    }

    return currentLength;
  }

  public <Data> int append(Data value, KeyDescriptor<Data> descriptor) throws IOException {
    final BufferExposingByteArrayOutputStream bos = new BufferExposingByteArrayOutputStream();
    DataOutput out = new DataOutputStream(bos);
    descriptor.save(out, value);
    final int size = bos.size();
    final byte[] buffer = bos.getInternalBuffer();

    int currentLength = getCurrentLength();

    if (myCompressedAppendableFile != null) {
      //myCompressedAppendableFile.append(value, descriptor);
      myCompressedAppendableFile.append(buffer, size);
      if (!testMode) return currentLength;
    }

    if (size > ourAppendBufferLength) {
      flushKeyStoreBuffer();
      put(currentLength, buffer, 0, size);
      myFileLength += size;
    }
    else {
      if (size > ourAppendBufferLength - myBufferPosition) {
        flushKeyStoreBuffer();
      }
      // myAppendBuffer will contain complete records
      if (myAppendBuffer == null) {
        myAppendBuffer = new byte[ourAppendBufferLength];
      }
      System.arraycopy(buffer, 0, myAppendBuffer, myBufferPosition, size);
      myBufferPosition += size;
    }
    return currentLength;
  }

  <Data> boolean checkBytesAreTheSame(final int addr, Data value, KeyDescriptor<Data> descriptor) throws IOException {
    final boolean[] sameValue = new boolean[1];
    OutputStream comparer;

    if (myCompressedAppendableFile != null) {
      final DataInputStream compressedStream = myCompressedAppendableFile.getStream(addr);

      comparer = new OutputStream() {
        boolean same = true;

        @Override
        public void write(int b) throws IOException {
          if (same) {
            same = compressedStream.readByte() == (byte)b;
          }
        }

        @Override
        public void close() {
          sameValue[0] = same;
        }
      };
    }
    else {
      comparer = buildOldComparerStream(addr, sameValue);
    }

    DataOutput out = new DataOutputStream(comparer);
    descriptor.save(out, value);
    comparer.close();

    if (testMode) {
      final boolean[] sameValue2 = new boolean[1];
      OutputStream comparer2 = buildOldComparerStream(addr, sameValue2);
      out = new DataOutputStream(comparer2);
      descriptor.save(out, value);
      comparer2.close();
      assert sameValue[0] == sameValue2[0];
    }
    return sameValue[0];
  }

  @Nonnull
  private OutputStream buildOldComparerStream(final int addr, final boolean[] sameValue) {
    OutputStream comparer;
    final PagedFileStorage storage = getPagedFileStorage();

    if (myFileLength <= addr) {
      //noinspection IOResourceOpenedButNotSafelyClosed
      comparer = new OutputStream() {
        int address = addr - myFileLength;
        boolean same = true;

        @Override
        public void write(int b) {
          if (same) {
            same = address < myBufferPosition && myAppendBuffer[address++] == (byte)b;
          }
        }

        @Override
        public void close() {
          sameValue[0] = same;
        }
      };
    }
    else {
      //noinspection IOResourceOpenedButNotSafelyClosed
      comparer = new OutputStream() {
        int base = addr;
        int address = storage.getOffsetInPage(addr);
        boolean same = true;
        ByteBuffer buffer = storage.getByteBuffer(addr, false).getCachedBuffer();
        final int myPageSize = storage.myPageSize;

        @Override
        public void write(int b) {
          if (same) {
            if (myPageSize == address && address < myFileLength) {    // reached end of current byte buffer
              base += address;
              buffer = storage.getByteBuffer(base, false).getCachedBuffer();
              address = 0;
            }
            same = address < myFileLength && buffer.get(address++) == (byte)b;
          }
        }

        @Override
        public void close() {
          sameValue[0] = same;
        }
      };

    }
    return comparer;
  }

  private static class MyDataIS extends DataInputStream {
    private MyDataIS(ResizeableMappedFile raf) {
      super(new MyBufferedIS(new MappedFileInputStream(raf, 0, 0)));
    }

    public void setup(long pos, long limit) {
      ((MyBufferedIS)in).setup(pos, limit);
    }
  }

  private static class MyBufferedIS extends BufferedInputStream {
    MyBufferedIS(final InputStream in) {
      super(in, 512);
    }

    public void setup(long pos, long limit) {
      this.pos = 0;
      count = 0;
      ((MappedFileInputStream)in).setup(pos, limit);
    }
  }

  private class MyCompressedAppendableFile extends CompressedAppendableFile {
    private final File myFile;
    private DataOutputStream myChunkLengthTableStream;

    MyCompressedAppendableFile(File file) {
      super(getPagedFileStorage().getFile());
      myFile = file;
    }

    @Nonnull
    @Override
    protected InputStream getChunkInputStream(File appendFile, long offset, int pageSize) {
      byte[] buf = new byte[pageSize];
      get(offset, buf, 0, pageSize);

      return new ByteArrayInputStream(buf);
    }

    @Override
    protected void saveChunk(BufferExposingByteArrayOutputStream compressedChunk, long endOfFileOffset) throws IOException {
      AppendableStorageBackedByResizableMappedFile.super.put(endOfFileOffset, compressedChunk.getInternalBuffer(), 0, compressedChunk.size());

      if (myChunkLengthTableStream == null) {
        myChunkLengthTableStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(getChunkLengthFile(), true)));
      }

      DataInputOutputUtil.writeINT(myChunkLengthTableStream, compressedChunk.size());
    }

    @Override
    public synchronized void force() {
      super.force();

      try {
        if (myChunkLengthTableStream != null) myChunkLengthTableStream.flush();
      }
      catch (IOException ignore) {
      }
    }

    @Override
    public synchronized void dispose() {
      super.dispose();
      if (myChunkLengthTableStream != null) {
        try {
          myChunkLengthTableStream.close();
        }
        catch (IOException ignore) {
        }
      }
    }

    @Nonnull
    @Override
    protected File getChunksFile() {
      return myFile;
    }
  }
}