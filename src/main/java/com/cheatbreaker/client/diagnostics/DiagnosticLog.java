package com.cheatbreaker.client.diagnostics;

import com.google.gson.Gson;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;

final class DiagnosticLog implements AutoCloseable {
   static final long FILE_BYTES = 16 * 1024 * 1024;
   static final int FILE_COUNT = 4;
   private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(1024);
   private final Path path;
   private final long fileBytes;
   private final Thread writer;
   final AtomicLong dropped = new AtomicLong();
   final AtomicLong failures = new AtomicLong();
   final AtomicLong written = new AtomicLong();
   volatile long lastWriteNanos;
   private volatile boolean running = true;

   DiagnosticLog(Path path) {
      this(path, FILE_BYTES);
   }

   DiagnosticLog(Path path, long fileBytes) {
      this.path = path;
      this.fileBytes = fileBytes;
      this.writer = new Thread(this::writeLoop, "connection-diagnostics-writer");
      this.writer.setDaemon(true);
      this.writer.start();
   }

   void offer(Map<String, Object> record) {
      if (!this.running || !this.queue.offer(record)) this.dropped.incrementAndGet();
   }

   int pending() {
      return this.queue.size();
   }

   private void writeLoop() {
      Gson json = new Gson();
      BufferedOutputStream output = null;
      long length = 0;
      long lastFlush = 0;
      long retryAt = 0;
      boolean failed = false;
      try {
         while (this.running || !this.queue.isEmpty()) {
            Map<String, Object> record;
            try {
               record = this.queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
               continue;
            }
            long now = System.nanoTime();
            if (record == null) {
               if (output != null) {
                  try {
                     output.flush();
                     lastFlush = now;
                  } catch (IOException error) {
                     this.failed(error, failed);
                     failed = true;
                     closeOutput(output);
                     output = null;
                     retryAt = now + TimeUnit.SECONDS.toNanos(5);
                  }
               }
               continue;
            }
            if (now < retryAt) {
               this.dropped.incrementAndGet();
               continue;
            }
            byte[] line = (json.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
            try {
               if (output == null) {
                  Files.createDirectories(this.path.getParent());
                  length = Files.exists(this.path) ? Files.size(this.path) : 0;
                  output = this.open();
               }
               if (length > 0 && length + line.length > this.fileBytes) {
                  output.close();
                  output = null;
                  this.rotate();
                  output = this.open();
                  length = 0;
               }
               output.write(line);
               length += line.length;
               if (now - lastFlush >= TimeUnit.SECONDS.toNanos(1)) {
                  output.flush();
                  lastFlush = now;
               }
               this.lastWriteNanos = now;
               this.written.incrementAndGet();
               if (failed) LogManager.getLogger().info("Connection diagnostics log writing recovered");
               failed = false;
            } catch (IOException error) {
               this.failed(error, failed);
               failed = true;
               this.dropped.incrementAndGet();
               closeOutput(output);
               output = null;
               retryAt = now + TimeUnit.SECONDS.toNanos(5);
            }
         }
      } finally {
         closeOutput(output);
      }
   }

   private BufferedOutputStream open() throws IOException {
      return new BufferedOutputStream(Files.newOutputStream(this.path, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
   }

   private void rotate() throws IOException {
      for (int index = FILE_COUNT - 1; index >= 1; index--) {
         Path source = this.path;
         if (index > 1) source = this.segment(index - 1);
         if (Files.exists(source)) Files.move(source, this.segment(index), StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private Path segment(int index) {
      return this.path.resolveSibling(this.path.getFileName() + "." + index);
   }

   private void failed(IOException error, boolean alreadyReported) {
      this.failures.incrementAndGet();
      if (!alreadyReported) {
         LogManager.getLogger().warn("Cannot write connection diagnostics to {} ({})", this.path, error.getClass().getName());
      }
   }

   private static void closeOutput(BufferedOutputStream output) {
      if (output == null) return;
      try {
         output.close();
      } catch (IOException error) {
         LogManager.getLogger().warn("Cannot flush connection diagnostics ({})", error.getClass().getName());
      }
   }

   @Override
   public void close() {
      this.running = false;
      try {
         this.writer.join(1500);
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
      }
   }
}
