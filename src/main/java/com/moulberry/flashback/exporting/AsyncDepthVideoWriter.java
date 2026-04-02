package com.moulberry.flashback.exporting;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.SneakyThrow;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class AsyncDepthVideoWriter implements AutoCloseable {

    private final ArrayBlockingQueue<DepthFrame> encodeQueue;
    private final ArrayBlockingQueue<Long> reusePictureData;

    private final AtomicBoolean finishEncodeThread = new AtomicBoolean(false);
    private final AtomicBoolean finishedWriting = new AtomicBoolean(false);
    private final AtomicReference<Throwable> threadedError = new AtomicReference<>(null);

    private final File outputDir;

    private record DepthFrame(long pointer, int size, int width, int height, int frameIndex) implements AutoCloseable {
        public void close() {
            MemoryUtil.nmemFree(this.pointer);
        }
    }

    public AsyncDepthVideoWriter(String filename, int width, int height) {
        if (filename.contains(".")) {
            filename = filename.substring(0, filename.lastIndexOf('.'));
        }

        this.outputDir = new File(filename);
        if (!outputDir.exists()) {
            this.outputDir.mkdirs();
        }

        Flashback.LOGGER.info("Starting TIFF Sequence Export to: " + outputDir.getAbsolutePath());

        this.encodeQueue = new ArrayBlockingQueue<>(16);
        this.reusePictureData = new ArrayBlockingQueue<>(16);

        Thread encodeThread = createEncodeThread();
        encodeThread.start();
    }

    private Thread createEncodeThread() {
        Thread encodeThread = new Thread(() -> {
            ByteBuffer headerBuffer = MemoryUtil.memAlloc(1024); // Reusable header buffer

            while (true) {
                DepthFrame src;
                try {
                    src = this.encodeQueue.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    throw SneakyThrow.sneakyThrow(e);
                }

                try {
                    if (src == null) {
                        if (this.finishEncodeThread.get()) {
                            this.finishedWriting.set(true);
                            return;
                        } else {
                            continue;
                        }
                    }

                    String fileName = String.format("frame_%04d.tif", src.frameIndex);
                    File file = new File(this.outputDir, fileName);

                    try (FileOutputStream fos = new FileOutputStream(file);
                         FileChannel channel = fos.getChannel()) {

                        // 1. Prepare Data Buffer (Raw Float Data)
                        ByteBuffer dataBuffer = MemoryUtil.memByteBuffer(src.pointer, src.size);
                        dataBuffer.order(ByteOrder.LITTLE_ENDIAN); // TIFF standard usually LE

                        // 2. Build TIFF Header
                        headerBuffer.clear();
                        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

                        writeTiffHeader(headerBuffer, src.width, src.height, src.size);

                        headerBuffer.flip();

                        // 3. Write Header then Data
                        channel.write(headerBuffer);
                        channel.write(dataBuffer);
                    }

                    if (this.reusePictureData != null) {
                        if (this.reusePictureData.offer(src.pointer)) {
                            src = null;
                        }
                    }

                } catch (Throwable t) {
                    this.threadedError.set(t);
                    this.finishEncodeThread.set(true);
                    this.finishedWriting.set(true);
                    return;
                } finally {
                    if (src != null) src.close();
                }
            }
        });
        encodeThread.setName("Depth TIFF Write Thread");
        return encodeThread;
    }


    private void writeTiffHeader(ByteBuffer buf, int width, int height, int dataSize) {
        // TIFF Header
        buf.put((byte) 'I'); buf.put((byte) 'I'); // Byte Order: Little Endian
        buf.putShort((short) 42);                 // TIFF Magic Number
        buf.putInt(8);                            // Offset to first IFD (immediately after header)

        // IFD (Image File Directory)
        int numEntries = 10; // CRITICAL FIX: Changed from 9 to 10
        buf.putShort((short) numEntries);

        // Tags MUST be sorted by Tag ID ascending!
        writeTag(buf, 256, 4, 1, width);          // ImageWidth
        writeTag(buf, 257, 4, 1, height);         // ImageHeight
        writeTag(buf, 258, 3, 1, 32);             // BitsPerSample: 32
        writeTag(buf, 259, 3, 1, 1);              // Compression: 1 (None)
        writeTag(buf, 262, 3, 1, 1);              // PhotometricInterpretation: 1 (BlackIsZero)

        // StripOffsets: Header(8) + IFD Count(2) + Tags(10*12) + Next IFD offset(4) = 134
        int headerSize = 8 + 2 + (numEntries * 12) + 4;
        writeTag(buf, 273, 4, 1, headerSize);     // StripOffsets

        writeTag(buf, 277, 3, 1, 1);              // SamplesPerPixel: 1 (Grayscale)
        writeTag(buf, 278, 4, 1, height);         // RowsPerStrip (One big strip)
        writeTag(buf, 279, 4, 1, dataSize);       // StripByteCounts (Size of image data)

        // CRITICAL FIX: Use your helper method.
        // Type 3 (SHORT) works fine with putInt(3) because Little Endian
        // puts the valid data in the first 2 bytes automatically.
        writeTag(buf, 339, 3, 1, 3);              // SampleFormat: 3 (IEEE Float)

        // Offset to next IFD (0 = none)
        buf.putInt(0);
    }

    private void writeTag(ByteBuffer buf, int tag, int type, int count, int value) {
        buf.putShort((short) tag);   // Tag ID
        buf.putShort((short) type);  // Data Type (3=SHORT, 4=LONG)
        buf.putInt(count);           // Count
        buf.putInt(value);           // Value or Offset
    }
    // ---------------------------------

    public void encode(ByteBuffer src, int width, int height, int explicitFrameIndex) {
        Throwable t = this.threadedError.get();
        if (t != null) SneakyThrow.sneakyThrow(t);

        if (this.finishEncodeThread.get()) throw new IllegalStateException("Cannot encode after finish()");

        int sizeBytes = width * height * 4;

        Long ptr = this.reusePictureData.poll();
        if (ptr == null) {
            ptr = MemoryUtil.nmemAlloc(sizeBytes);
        }



        MemoryUtil.memByteBuffer(ptr, sizeBytes).put(src);

        src.rewind();


        while (true) {
            try {
                this.encodeQueue.put(new DepthFrame(ptr, sizeBytes, width, height, explicitFrameIndex));
                break;
            } catch (InterruptedException ignored) {}
        }
    }

    public void finish() {
        while (!this.encodeQueue.isEmpty()) {
            LockSupport.parkNanos("waiting for depth queue", 100000L);
        }
        this.finishEncodeThread.set(true);
        while (!this.finishedWriting.get()) {
            LockSupport.parkNanos("waiting for depth thread", 100000L);
        }
    }

    @Override
    public void close() {
        finish();
        for (Long ptr : this.reusePictureData) {
            if (ptr != null && ptr != 0) MemoryUtil.nmemFree(ptr);
        }
    }
}