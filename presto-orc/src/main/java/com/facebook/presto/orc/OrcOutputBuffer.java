/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import com.facebook.presto.orc.checkpoint.InputStreamCheckpoint;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.writer.CompressionBufferPool;
import com.facebook.presto.orc.zlib.DeflateCompressor;
import com.facebook.presto.orc.zstd.ZstdJniCompressor;
import com.google.common.annotations.VisibleForTesting;
import io.airlift.compress.Compressor;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.snappy.SnappyCompressor;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.SizeOf.SIZE_OF_BYTE;
import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.airlift.slice.SizeOf.SIZE_OF_SHORT;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

public class OrcOutputBuffer
        extends SliceOutput
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(OrcOutputBuffer.class).instanceSize();
    private static final int PAGE_HEADER_SIZE = 3; // ORC spec 3 byte header
    private static final int INITIAL_BUFFER_SIZE = 256;
    private final int maxBufferSize;
    private final int minOutputBufferChunkSize;
    private final int maxOutputBufferChunkSize;
    private final int minCompressibleSize;
    private final boolean resetOutputBuffer;
    private final boolean lazyOutputBuffer;
    private final CompressionBufferPool compressionBufferPool;
    private final Optional<DwrfDataEncryptor> dwrfEncryptor;
    @Nullable
    private final Compressor compressor;

    private OrcChunkedOutputBuffer compressedOutputStream;
    private Slice slice;
    private byte[] buffer;

    /**
     * Offset of buffer within stream.
     */
    private long bufferOffset;
    /**
     * Current position for writing in buffer.
     */
    private int bufferPosition;

    public OrcOutputBuffer(ColumnWriterOptions columnWriterOptions, Optional<DwrfDataEncryptor> dwrfEncryptor)
    {
        requireNonNull(columnWriterOptions, "columnWriterOptions is null");
        requireNonNull(dwrfEncryptor, "dwrfEncryptor is null");
        int maxBufferSize = columnWriterOptions.getCompressionMaxBufferSize();
        checkArgument(maxBufferSize > PAGE_HEADER_SIZE, "maximum buffer size should be greater than page header size");

        CompressionKind compressionKind = columnWriterOptions.getCompressionKind();
        this.maxBufferSize = compressionKind == CompressionKind.NONE ? maxBufferSize : maxBufferSize - PAGE_HEADER_SIZE;
        this.minOutputBufferChunkSize = columnWriterOptions.getMinOutputBufferChunkSize();
        this.maxOutputBufferChunkSize = columnWriterOptions.getMaxOutputBufferChunkSize();
        this.resetOutputBuffer = columnWriterOptions.isResetOutputBuffer();
        this.lazyOutputBuffer = columnWriterOptions.isLazyOutputBuffer();
        this.minCompressibleSize = compressionKind.getMinCompressibleSize();
        if (!lazyOutputBuffer) {
            this.buffer = new byte[INITIAL_BUFFER_SIZE];
            this.slice = wrappedBuffer(buffer);
        }
        this.compressionBufferPool = columnWriterOptions.getCompressionBufferPool();
        this.dwrfEncryptor = requireNonNull(dwrfEncryptor, "dwrfEncryptor is null");

        if (compressionKind == CompressionKind.NONE) {
            this.compressor = null;
        }
        else if (compressionKind == CompressionKind.SNAPPY) {
            this.compressor = new SnappyCompressor();
        }
        else if (compressionKind == CompressionKind.ZLIB) {
            this.compressor = new DeflateCompressor(columnWriterOptions.getCompressionLevel());
        }
        else if (compressionKind == CompressionKind.LZ4) {
            this.compressor = new Lz4Compressor();
        }
        else if (compressionKind == CompressionKind.ZSTD) {
            this.compressor = new ZstdJniCompressor(columnWriterOptions.getCompressionLevel());
        }
        else {
            throw new IllegalArgumentException("Unsupported compression " + compressionKind);
        }
    }

    public long getOutputDataSize()
    {
        checkState(bufferPosition == 0, "Buffer must be flushed before getOutputDataSize can be called");
        return getCompressedOutputSize();
    }

    private int getCompressedOutputSize()
    {
        return compressedOutputStream != null ? compressedOutputStream.size() : 0;
    }

    public long estimateOutputDataSize()
    {
        return getCompressedOutputSize() + bufferPosition;
    }

    public int writeDataTo(SliceOutput outputStream)
    {
        checkState(bufferPosition == 0, "Buffer must be closed before writeDataTo can be called");
        if (compressedOutputStream == null) {
            return 0;
        }

        compressedOutputStream.writeTo(outputStream);
        return compressedOutputStream.size();
    }

    public long getCheckpoint()
    {
        if (compressor == null && !dwrfEncryptor.isPresent()) {
            return size();
        }
        return InputStreamCheckpoint.createInputStreamCheckpoint(getCompressedOutputSize(), bufferPosition);
    }

    @Override
    public void flush()
    {
        flushBufferToOutputStream();
    }

    @Override
    public void close()
    {
        flushBufferToOutputStream();
    }

    @Override
    public void reset()
    {
        if (compressedOutputStream != null) {
            compressedOutputStream.reset();
        }
        bufferOffset = 0;
        bufferPosition = 0;
    }

    @Override
    public void reset(int position)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size()
    {
        return toIntExact(bufferOffset + bufferPosition);
    }

    @Override
    public long getRetainedSize()
    {
        return INSTANCE_SIZE
                + (compressedOutputStream != null ? compressedOutputStream.getRetainedSize() : 0L)
                + (slice != null ? slice.getRetainedSize() : 0L);
    }

    @Override
    public int writableBytes()
    {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isWritable()
    {
        return true;
    }

    @Override
    public void writeByte(int value)
    {
        ensureWritableBytes(SIZE_OF_BYTE);
        slice.setByte(bufferPosition, value);
        bufferPosition += SIZE_OF_BYTE;
    }

    @Override
    public void writeShort(int value)
    {
        ensureWritableBytes(SIZE_OF_SHORT);
        slice.setShort(bufferPosition, value);
        bufferPosition += SIZE_OF_SHORT;
    }

    @Override
    public void writeInt(int value)
    {
        ensureWritableBytes(SIZE_OF_INT);
        slice.setInt(bufferPosition, value);
        bufferPosition += SIZE_OF_INT;
    }

    @Override
    public void writeLong(long value)
    {
        ensureWritableBytes(SIZE_OF_LONG);
        slice.setLong(bufferPosition, value);
        bufferPosition += SIZE_OF_LONG;
    }

    @Override
    public void writeFloat(float value)
    {
        // This normalizes NaN values like `java.io.DataOutputStream` does
        writeInt(Float.floatToIntBits(value));
    }

    @Override
    public void writeDouble(double value)
    {
        // This normalizes NaN values like `java.io.DataOutputStream` does
        writeLong(Double.doubleToLongBits(value));
    }

    @Override
    public void writeBytes(Slice source)
    {
        writeBytes(source, 0, source.length());
    }

    @Override
    public void writeBytes(Slice source, int sourceOffset, int length)
    {
        byte[] bytes = (byte[]) source.getBase();
        int bytesOffset = (int) (source.getAddress() - ARRAY_BYTE_BASE_OFFSET);
        writeBytes(bytes, sourceOffset + bytesOffset, length);
    }

    @Override
    public void writeBytes(byte[] source)
    {
        writeBytes(source, 0, source.length);
    }

    @Override
    public void writeBytes(byte[] bytes, int bytesOffset, int length)
    {
        if (length == 0) {
            return;
        }

        // finish filling the buffer
        if (bufferPosition != 0) {
            int chunkSize = min(length, maxBufferSize - bufferPosition);
            ensureWritableBytes(chunkSize);
            slice.setBytes(bufferPosition, bytes, bytesOffset, chunkSize);
            bufferPosition += chunkSize;
            length -= chunkSize;
            bytesOffset += chunkSize;
        }

        // write maxBufferSize chunks directly to output
        if (length >= maxBufferSize) {
            flushBufferToOutputStream();
            int bytesOffsetBefore = bytesOffset;
            while (length >= maxBufferSize) {
                writeChunkToOutputStream(bytes, bytesOffset, maxBufferSize);
                length -= maxBufferSize;
                bytesOffset += maxBufferSize;
            }
            bufferOffset += bytesOffset - bytesOffsetBefore;
        }

        // write the tail smaller than maxBufferSize to the buffer
        if (length > 0) {
            ensureWritableBytes(length);
            slice.setBytes(bufferPosition, bytes, bytesOffset, length);
            bufferPosition += length;
        }
    }

    @Override
    public void writeBytes(InputStream in, int length)
            throws IOException
    {
        while (length > 0) {
            int batch = ensureBatchSize(length);
            slice.setBytes(bufferPosition, in, batch);
            bufferPosition += batch;
            length -= batch;
        }
    }

    @Override
    public void writeZero(int length)
    {
        checkArgument(length >= 0, "length must be 0 or greater than 0.");

        while (length > 0) {
            int batch = ensureBatchSize(length);
            Arrays.fill(buffer, bufferPosition, bufferPosition + batch, (byte) 0);
            bufferPosition += batch;
            length -= batch;
        }
    }

    private int ensureBatchSize(int length)
    {
        if (buffer == null) {
            initBuffer(length);
        }
        ensureWritableBytes(min(length, maxBufferSize - bufferPosition));
        if (availableInBuffer() == 0) {
            flushBufferToOutputStream();
        }
        return min(length, availableInBuffer());
    }

    private int availableInBuffer()
    {
        return slice.length() - bufferPosition;
    }

    @Override
    public SliceOutput appendLong(long value)
    {
        writeLong(value);
        return this;
    }

    @Override
    public SliceOutput appendDouble(double value)
    {
        writeDouble(value);
        return this;
    }

    @Override
    public SliceOutput appendInt(int value)
    {
        writeInt(value);
        return this;
    }

    @Override
    public SliceOutput appendShort(int value)
    {
        writeShort(value);
        return this;
    }

    @Override
    public SliceOutput appendByte(int value)
    {
        writeByte(value);
        return this;
    }

    @Override
    public SliceOutput appendBytes(byte[] source, int sourceIndex, int length)
    {
        writeBytes(source, sourceIndex, length);
        return this;
    }

    @Override
    public SliceOutput appendBytes(byte[] source)
    {
        writeBytes(source);
        return this;
    }

    @Override
    public SliceOutput appendBytes(Slice slice)
    {
        writeBytes(slice);
        return this;
    }

    @Override
    public Slice slice()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Slice getUnderlyingSlice()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString(Charset charset)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder("OrcOutputBuffer{");
        builder.append("outputStream=").append(compressedOutputStream);
        builder.append(", bufferSize=").append(slice.length());
        builder.append('}');
        return builder.toString();
    }

    private void ensureWritableBytes(int minWritableBytes)
    {
        checkArgument(minWritableBytes <= maxBufferSize, "Min writable bytes must not exceed max buffer size");

        if (buffer == null) {
            initBuffer(minWritableBytes);
        }
        int neededBufferSize = bufferPosition + minWritableBytes;
        if (neededBufferSize <= slice.length()) {
            return;
        }

        if (slice.length() >= maxBufferSize) {
            flushBufferToOutputStream();
            return;
        }

        // grow the buffer size up to maxBufferSize
        int newBufferSize = min(max(slice.length() * 2, neededBufferSize), maxBufferSize);
        if (newBufferSize >= neededBufferSize) {
            // we have capacity in the new buffer; just copy the data to the new buffer
            byte[] previousBuffer = buffer;
            buffer = new byte[newBufferSize];
            slice = wrappedBuffer(buffer);
            System.arraycopy(previousBuffer, 0, buffer, 0, bufferPosition);
        }
        else {
            // there is no enough capacity in the new buffer; flush the data and allocate the new buffer
            flushBufferToOutputStream();
            buffer = new byte[newBufferSize];
            slice = wrappedBuffer(buffer);
        }
    }

    private void initBuffer(int length)
    {
        int initialSize = calculateBufferSize(length);
        buffer = new byte[initialSize];
        slice = wrappedBuffer(buffer);
    }

    private int calculateBufferSize(int length)
    {
        int initialSize = INITIAL_BUFFER_SIZE;
        while (initialSize < length && initialSize < maxBufferSize) {
            initialSize = min(initialSize * 2, maxBufferSize);
        }
        return initialSize;
    }

    private void flushBufferToOutputStream()
    {
        if (bufferPosition > 0) {
            writeChunkToOutputStream(buffer, 0, bufferPosition);
            bufferOffset += bufferPosition;
            bufferPosition = 0;
        }
    }

    private void initCompressedOutputStream()
    {
        checkState(compressedOutputStream == null, "compressedOutputStream is already initialized");
        if (!lazyOutputBuffer) {
            compressedOutputStream = new ChunkedSliceOutput(minOutputBufferChunkSize, maxOutputBufferChunkSize, resetOutputBuffer);
        }
        else {
            compressedOutputStream = new OrcLazyChunkedOutputBuffer();
        }
    }

    private void writeChunkToOutputStream(byte[] chunk, int offset, int length)
    {
        if (compressedOutputStream == null) {
            initCompressedOutputStream();
        }

        if (compressor == null && !dwrfEncryptor.isPresent()) {
            compressedOutputStream.ensureAvailable(1, length);
            compressedOutputStream.writeBytes(chunk, offset, length);
            return;
        }

        checkArgument(length <= maxBufferSize, "Write chunk length must be less than max compression buffer size");

        boolean isCompressed = false;
        byte[] compressionBuffer = null;
        try {
            if (compressor != null && length >= minCompressibleSize) {
                int minCompressionBufferSize = compressor.maxCompressedLength(length);
                compressionBuffer = compressionBufferPool.checkOut(minCompressionBufferSize);
                int compressedSize = compressor.compress(chunk, offset, length, compressionBuffer, 0, compressionBuffer.length);
                if (compressedSize < length) {
                    isCompressed = true;
                    chunk = compressionBuffer;
                    length = compressedSize;
                    offset = 0;
                }
            }
            if (dwrfEncryptor.isPresent()) {
                chunk = dwrfEncryptor.get().encrypt(chunk, offset, length);
                length = chunk.length;
                offset = 0;
                // size after encryption should not exceed what the 3 byte header can hold (2^23)
                if (length > 8388608) {
                    throw new OrcEncryptionException("Encrypted data size %s exceeds limit of 2^23", length);
                }
            }

            int header = isCompressed ? length << 1 : (length << 1) + 1;
            writeChunkedOutput(chunk, offset, length, header);
        }
        finally {
            if (compressionBuffer != null) {
                compressionBufferPool.checkIn(compressionBuffer);
            }
        }
    }

    private void writeChunkedOutput(byte[] chunk, int offset, int length, int header)
    {
        compressedOutputStream.ensureAvailable(3, length + 3);
        compressedOutputStream.writeHeader(header);
        compressedOutputStream.writeBytes(chunk, offset, length);
    }

    @VisibleForTesting
    int getBufferCapacity()
    {
        return slice.length();
    }
}
