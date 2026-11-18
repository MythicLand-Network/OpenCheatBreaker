package com.cheatbreaker.client.diagnostics;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.timeout.TimeoutException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S40PacketDisconnect;
import org.apache.logging.log4j.LogManager;

public final class ConnectionDiagnostics implements AutoCloseable {
   private static volatile ConnectionDiagnostics instance;
   private final DiagnosticLog log;
   private final String run = UUID.randomUUID().toString();
   private final long startedNanos = System.nanoTime();
   private final ConcurrentHashMap<Channel, Session> sessions = new ConcurrentHashMap<>();
   private final AtomicLong nextSession = new AtomicLong();
   private final AtomicLong maxFrameNanos = new AtomicLong();
   private final ScheduledExecutorService sampler;
   private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
   private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
   private final Thread clientThread;
   private volatile boolean enabled;
   private volatile boolean closed;
   private volatile long capture;
   private volatile long lastFrameNanos;
   private volatile int fpsLimit;
   private volatile boolean focused;
   private long lastSampleNanos;
   private long lastDropWarningNanos;
   private long lastReportedDrops;

   ConnectionDiagnostics(Path logPath, Thread clientThread) {
      this.log = new DiagnosticLog(logPath);
      this.clientThread = clientThread;
      this.sampler = Executors.newSingleThreadScheduledExecutor(task -> {
         Thread thread = new Thread(task, "connection-diagnostics-sampler");
         thread.setDaemon(true);
         return thread;
      });
      this.sampler.scheduleWithFixedDelay(this::sampleSafely, 1, 1, TimeUnit.SECONDS);
   }

   public static synchronized void start(Path gameDirectory, Thread clientThread) {
      if (instance == null) {
         instance = new ConnectionDiagnostics(gameDirectory.resolve("logs/connection-diagnostics.jsonl"), clientThread);
         Runtime.getRuntime().addShutdownHook(new Thread(ConnectionDiagnostics::shutdown, "connection-diagnostics-shutdown"));
      }
      instance.enable();
   }

   public static void stop() {
      ConnectionDiagnostics current = instance;
      if (current != null) current.disable();
   }

   public static void shutdown() {
      ConnectionDiagnostics current = instance;
      if (current != null) current.close();
   }

   public static void frame(int fpsLimit, boolean focused) {
      ConnectionDiagnostics current = instance;
      if (current != null && current.enabled) current.frameObserved(fpsLimit, focused);
   }

   public static void attach(NetworkManager manager, Channel channel) {
      ConnectionDiagnostics current = instance;
      if (current != null && current.enabled) current.observe(manager, channel);
   }

   synchronized void enable() {
      if (this.enabled || this.closed) return;
      this.capture++;
      this.lastFrameNanos = System.nanoTime();
      this.lastSampleNanos = this.lastFrameNanos;
      this.maxFrameNanos.set(0);
      this.enabled = true;
      this.record(0, "capture_started", fields(
         "schema", 1, "java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
         "systemZone", ZoneId.systemDefault().getId(), "sampleMillis", 1000, "readTimeoutSeconds", 30,
         "fileBytes", DiagnosticLog.FILE_BYTES, "fileCount", DiagnosticLog.FILE_COUNT,
         "writeCompletionMeaning", "local Netty completion, not peer receipt or TCP acknowledgement"
      ));
   }

   synchronized void disable() {
      if (!this.enabled) return;
      this.enabled = false;
      this.record(0, "capture_stopped", fields("droppedRecords", this.log.dropped.get(), "writeErrors", this.log.failures.get()));
      for (Session session : this.sessions.values()) session.detach();
      this.sessions.clear();
   }

   void observe(NetworkManager manager, Channel channel) {
      long capture = this.capture;
      Runnable attach = () -> {
         synchronized (this) {
            if (!this.enabled || this.capture != capture || !channel.isOpen() || channel instanceof LocalChannel
                  || this.sessions.containsKey(channel)) return;
            if (this.sessions.size() >= 32) {
               this.record(0, "connection_capture_limit", fields("limit", 32));
               return;
            }
            Session previous = manager.connectionDiagnostics;
            if (previous != null) previous.detach();
            // A previous disable may have queued removal behind this attachment.
            if (channel.pipeline().get("connection_diagnostics_raw") != null) channel.pipeline().remove("connection_diagnostics_raw");
            if (channel.pipeline().get("connection_diagnostics_packets") != null) channel.pipeline().remove("connection_diagnostics_packets");
            Session session = new Session(this, manager, channel, this.nextSession.incrementAndGet(), capture);
            this.sessions.put(channel, session);
            manager.connectionDiagnostics = session;
            channel.pipeline().addFirst("connection_diagnostics_raw", session.raw);
            channel.pipeline().addBefore("packet_handler", "connection_diagnostics_packets", session.packets);
            session.event("connection_observed", fields("attachedWhileActive", channel.isActive()));
            if (channel.isActive()) session.connected();
            channel.closeFuture().addListener(future -> session.finished());
         }
      };
      if (channel.eventLoop().inEventLoop()) attach.run();
      else channel.eventLoop().execute(attach);
   }

   private void frameObserved(int fpsLimit, boolean focused) {
      long now = System.nanoTime();
      long interval = now - this.lastFrameNanos;
      this.lastFrameNanos = now;
      this.fpsLimit = fpsLimit;
      this.focused = focused;
      maximum(this.maxFrameNanos, interval);
      if (interval >= TimeUnit.MILLISECONDS.toNanos(250)) {
         this.record(0, "frame_interval", fields("intervalMs", millis(interval), "fpsLimit", fpsLimit, "focused", focused));
      }
   }

   private void sampleSafely() {
      if (!this.enabled) return;
      try {
         this.sample();
      } catch (RuntimeException error) {
         this.record(0, "sampler_error", failure(error));
         LogManager.getLogger().warn("Connection diagnostics sampling failed ({})", error.getClass().getName());
      }
   }

   void sample() {
      long now = System.nanoTime();
      long collections = 0;
      long collectionMillis = 0;
      for (GarbageCollectorMXBean collector : this.collectors) {
         collections += Math.max(0, collector.getCollectionCount());
         collectionMillis += Math.max(0, collector.getCollectionTime());
      }
      Map<String, Object> record = fields(
         "sampleIntervalMs", millis(now - this.lastSampleNanos), "frameAgeMs", age(now, this.lastFrameNanos),
         "maxFrameIntervalMs", millis(this.maxFrameNanos.getAndSet(0)), "fpsLimit", this.fpsLimit, "focused", this.focused,
         "gcCountTotal", collections, "gcMillisTotal", collectionMillis,
         "heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
         "logQueue", this.log.pending(), "droppedRecords", this.log.dropped.get(), "writeErrors", this.log.failures.get(),
         "recordsBuffered", this.log.written.get(), "logWriteAgeMs", age(now, this.log.lastWriteNanos)
      );
      this.lastSampleNanos = now;
      long drops = this.log.dropped.get();
      if (drops > this.lastReportedDrops && now - this.lastDropWarningNanos >= TimeUnit.SECONDS.toNanos(30)) {
         LogManager.getLogger().warn("Connection diagnostics dropped {} records; log backlog={}, write errors={}", drops, this.log.pending(), this.log.failures.get());
         this.lastReportedDrops = drops;
         this.lastDropWarningNanos = now;
      }
      if (this.threads.isThreadCpuTimeSupported() && this.threads.isThreadCpuTimeEnabled()) {
         record.put("clientCpuNanosTotal", this.threads.getThreadCpuTime(this.clientThread.getId()));
      }
      if (now - this.lastFrameNanos >= TimeUnit.MILLISECONDS.toNanos(500)) record.put("clientStack", stack(this.clientThread));
      this.record(0, "client_sample", record);
      for (Session session : this.sessions.values()) {
         session.event("connection_sample", session.snapshot(now));
         if (!session.channel.isOpen()) {
            session.detach();
            this.sessions.remove(session.channel, session);
         } else {
            session.probe();
         }
      }
   }

   private void record(long session, String event, Map<String, Object> values) {
      long wallMillis = System.currentTimeMillis();
      Map<String, Object> record = fields(
         "utc", Instant.ofEpochMilli(wallMillis).toString(), "wallMillis", wallMillis, "monoNanos", System.nanoTime(),
         "run", this.run, "capture", this.capture, "session", session, "event", event, "thread", Thread.currentThread().getName()
      );
      record.put("runElapsedMs", millis(System.nanoTime() - this.startedNanos));
      record.putAll(values);
      this.log.offer(record);
   }

   static Map<String, Object> fields(Object... pairs) {
      Map<String, Object> result = new LinkedHashMap<>();
      for (int index = 0; index < pairs.length; index += 2) result.put((String)pairs[index], pairs[index + 1]);
      return result;
   }

   private static Map<String, Object> failure(Throwable cause) {
      List<String> types = new ArrayList<>();
      Throwable current = cause;
      for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) types.add(current.getClass().getName());
      Map<String, Object> result = fields("exceptionTypes", types);
      result.put("transportError", transportError(cause));
      if (cause != null) {
         List<String> frames = new ArrayList<>();
         StackTraceElement[] trace = cause.getStackTrace();
         for (int index = 0; index < Math.min(12, trace.length); index++) frames.add(trace[index].toString());
         result.put("exceptionStack", frames);
      }
      return result;
   }

   private static String transportError(Throwable cause) {
      for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause()) {
         if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) return "timeout";
         String message = cause.getMessage();
         if (message == null) continue;
         String lower = message.toLowerCase(Locale.ROOT);
         if (lower.contains("reset") || lower.contains("forcibly closed") || message.contains("强迫关闭") || message.contains("重置")) return "reset";
         if (lower.contains("timed out") || lower.contains("timeout") || message.contains("超时")) return "timeout";
         if (lower.contains("abort") || message.contains("中止")) return "aborted";
         if (lower.contains("refused") || message.contains("拒绝")) return "refused";
         if (lower.contains("broken pipe")) return "broken_pipe";
         if (lower.contains("unreachable") || message.contains("无法访问")) return "unreachable";
      }
      return "unclassified";
   }

   private static List<String> stack(Thread thread) {
      List<String> result = new ArrayList<>();
      if (thread == null) return result;
      StackTraceElement[] trace = thread.getStackTrace();
      for (int index = 0; index < Math.min(20, trace.length); index++) result.add(trace[index].toString());
      return result;
   }

   private static boolean maximum(AtomicLong maximum, long value) {
      long previous = maximum.get();
      while (value > previous) {
         if (maximum.compareAndSet(previous, value)) return true;
         previous = maximum.get();
      }
      return false;
   }

   private static double millis(long nanos) {
      return nanos / 1000000.0;
   }

   private static double age(long now, long then) {
      return then == 0 ? -1 : millis(Math.max(0, now - then));
   }

   @Override
   public synchronized void close() {
      if (this.closed) return;
      this.disable();
      this.closed = true;
      this.sampler.shutdownNow();
      this.log.close();
   }

   public static final class Session {
      private final ConnectionDiagnostics owner;
      private final NetworkManager manager;
      private final Channel channel;
      private final long id;
      private final long capture;
      private final AtomicBoolean ended = new AtomicBoolean();
      private final AtomicBoolean probePending = new AtomicBoolean();
      private final AtomicLong dispatchPending = new AtomicLong();
      private final AtomicLong mainPending = new AtomicLong();
      private final AtomicLong maxDispatchNanos = new AtomicLong();
      private final AtomicLong maxMainQueueNanos = new AtomicLong();
      private final AtomicLong maxMainWorkNanos = new AtomicLong();
      private final AtomicLong maxWriteNanos = new AtomicLong();
      private final AtomicLong maxLoopNanos = new AtomicLong();
      private final AtomicLong maxRawReadGapNanos = new AtomicLong();
      private final AtomicLong maxDecodedGapNanos = new AtomicLong();
      private final AtomicLong maxOutboundGapNanos = new AtomicLong();
      private final RawObserver raw = new RawObserver();
      private final PacketObserver packets = new PacketObserver();
      private volatile Thread loopThread;
      private volatile long probeNanos;
      private volatile long loopAckNanos;
      private volatile long readNanos;
      private volatile long decodedNanos;
      private volatile long writeNanos;
      private volatile long keepAliveNanos;
      private volatile long rawReadBytes;
      private volatile long rawWriteBytes;
      private volatile long readPackets;
      private volatile long writePackets;
      private volatile long completedWrites;
      private volatile long failedWrites;
      private volatile long pendingWrites;
      private volatile long voidWrites;
      private volatile String lastReadPacket = "";
      private volatile String lastWritePacket = "";
      private volatile String slowestQueuedPacket = "";
      private volatile String local = "";
      private volatile String remote = "";
      private volatile boolean detached;

      Session(ConnectionDiagnostics owner, NetworkManager manager, Channel channel, long id, long capture) {
         this.owner = owner;
         this.manager = manager;
         this.channel = channel;
         this.id = id;
         this.capture = capture;
         this.loopThread = Thread.currentThread();
         this.loopAckNanos = System.nanoTime();
      }

      private boolean recording() {
         return !this.detached && this.owner.enabled && this.owner.capture == this.capture;
      }

      private void event(String event, Map<String, Object> values) {
         if (this.recording()) {
            values.put("capture", this.capture);
            this.owner.record(this.id, event, values);
         }
      }

      private void connected() {
         this.loopThread = Thread.currentThread();
         this.local = String.valueOf(this.channel.localAddress());
         this.remote = String.valueOf(this.channel.remoteAddress());
         this.event("connection_active", fields("local", this.local, "remote", this.remote, "transport", this.channel.getClass().getName()));
      }

      public void exception(Throwable cause) {
         this.event("network_exception", failure(cause));
      }

      public void closeRequested(String reasonKey) {
         if (reasonKey.length() > 128) reasonKey = reasonKey.substring(0, 128);
         this.event("close_requested", fields("reasonKey", reasonKey, "open", this.channel.isOpen(),
            "active", this.channel.isActive(), "callerStack", stack(Thread.currentThread())));
      }

      public void stateChanged(Object state) {
         this.event("protocol_state", fields("state", String.valueOf(state)));
      }

      public long dispatchRequested(Packet packet) {
         if (!this.recording()) return 0;
         this.dispatchPending.incrementAndGet();
         long now = System.nanoTime();
         if (packet instanceof C00PacketKeepAlive) {
            this.event("keepalive_send_requested", fields("key", ((C00PacketKeepAlive)packet).getKey()));
         }
         return now;
      }

      public void dispatched(long requestedNanos) {
         if (requestedNanos == 0) return;
         this.dispatchPending.decrementAndGet();
         maximum(this.maxDispatchNanos, System.nanoTime() - requestedNanos);
      }

      public long taskQueued() {
         if (!this.recording()) return 0;
         this.mainPending.incrementAndGet();
         return System.nanoTime();
      }

      public long taskStarted(long queuedNanos, Packet packet) {
         if (queuedNanos == 0) return 0;
         this.mainPending.decrementAndGet();
         long now = System.nanoTime();
         if (maximum(this.maxMainQueueNanos, now - queuedNanos)) this.slowestQueuedPacket = packet.getClass().getSimpleName();
         return now;
      }

      public void taskFinished(long startedNanos, Packet packet) {
         if (startedNanos == 0) return;
         long duration = System.nanoTime() - startedNanos;
         maximum(this.maxMainWorkNanos, duration);
         if (duration >= TimeUnit.MILLISECONDS.toNanos(100)) {
            this.event("main_packet_slow", fields("packet", packet.getClass().getSimpleName(), "durationMs", millis(duration)));
         }
      }

      public void keepAliveHandled(int key) {
         this.event("keepalive_handled", fields("key", key));
      }

      private void finished() {
         if (this.ended.compareAndSet(false, true)) this.event("connection_closed", this.snapshot(System.nanoTime()));
      }

      private void probe() {
         if (!this.probePending.compareAndSet(false, true)) return;
         this.probeNanos = System.nanoTime();
         try {
            this.channel.eventLoop().execute(() -> {
               long now = System.nanoTime();
               maximum(this.maxLoopNanos, now - this.probeNanos);
               this.loopThread = Thread.currentThread();
               this.loopAckNanos = now;
               this.probePending.set(false);
            });
         } catch (RejectedExecutionException closing) {
            this.probePending.set(false);
            this.event("event_loop_probe_rejected", failure(closing));
         }
      }

      Map<String, Object> snapshot(long now) {
         Map<String, Object> record = fields(
            "local", this.local, "remote", this.remote, "open", this.channel.isOpen(), "active", this.channel.isActive(),
            "state", String.valueOf(this.channel.attr(NetworkManager.attrKeyConnectionState).get()),
            "autoRead", this.channel.config().isAutoRead(), "writable", this.channel.isWritable(),
            "bytesBeforeUnwritable", this.channel.bytesBeforeUnwritable(),
            "rawReadBytesTotal", this.rawReadBytes, "rawWriteBytesSubmittedTotal", this.rawWriteBytes,
            "decodedPacketsTotal", this.readPackets, "outboundPacketsTotal", this.writePackets,
            "writeCompletedTotal", this.completedWrites, "writeFailedTotal", this.failedWrites,
            "writePending", this.pendingWrites, "unobservedVoidWritesTotal", this.voidWrites,
            "lastDecodedPacket", this.lastReadPacket, "lastOutboundPacket", this.lastWritePacket,
            "rawReadAgeMs", age(now, this.readNanos), "decodedPacketAgeMs", age(now, this.decodedNanos),
            "outboundPacketAgeMs", age(now, this.writeNanos), "keepaliveAgeMs", age(now, this.keepAliveNanos),
            "dispatchPending", this.dispatchPending.get(), "mainTasksPending", this.mainPending.get(),
            "maxDispatchDelayMs", millis(this.maxDispatchNanos.getAndSet(0)),
            "maxMainQueueDelayMs", millis(this.maxMainQueueNanos.getAndSet(0)),
            "slowestQueuedPacket", this.slowestQueuedPacket,
            "maxMainPacketWorkMs", millis(this.maxMainWorkNanos.getAndSet(0)),
            "maxWriteCompletionMs", millis(this.maxWriteNanos.getAndSet(0)),
            "maxEventLoopDelayMs", millis(this.maxLoopNanos.getAndSet(0)),
            "maxRawReadGapMs", millis(this.maxRawReadGapNanos.getAndSet(0)),
            "maxDecodedGapMs", millis(this.maxDecodedGapNanos.getAndSet(0)),
            "maxOutboundGapMs", millis(this.maxOutboundGapNanos.getAndSet(0)),
            "eventLoopAckAgeMs", age(now, this.loopAckNanos),
            "eventLoopProbeAgeMs", this.probePending.get() ? age(now, this.probeNanos) : 0
         );
         if (this.probePending.get() && now - this.probeNanos >= TimeUnit.MILLISECONDS.toNanos(500)) {
            record.put("eventLoopStack", stack(this.loopThread));
         }
         return record;
      }

      private void detach() {
         this.detached = true;
         if (this.manager.connectionDiagnostics == this) this.manager.connectionDiagnostics = null;
         Runnable remove = () -> {
            if (this.channel.pipeline().context(this.raw) != null) this.channel.pipeline().remove(this.raw);
            if (this.channel.pipeline().context(this.packets) != null) this.channel.pipeline().remove(this.packets);
         };
         if (this.channel.eventLoop().inEventLoop()) remove.run();
         else {
            try {
               this.channel.eventLoop().execute(remove);
            } catch (RejectedExecutionException closing) {
               // A terminated event loop no longer delivers events to its handlers.
            }
         }
      }

      private final class RawObserver extends ChannelDuplexHandler {
         @Override
         public void connect(ChannelHandlerContext context, SocketAddress remote, SocketAddress local, ChannelPromise promise) throws Exception {
            Session.this.remote = String.valueOf(remote);
            Session.this.event("connect_attempt", fields("remote", String.valueOf(remote), "local", String.valueOf(local)));
            promise.addListener(future -> {
               if (!future.isSuccess()) Session.this.event("connect_failed", failure(future.cause()));
            });
            super.connect(context, remote, local, promise);
         }

         @Override
         public void channelActive(ChannelHandlerContext context) throws Exception {
            Session.this.connected();
            super.channelActive(context);
         }

         @Override
         public void channelInactive(ChannelHandlerContext context) throws Exception {
            Session.this.event("channel_inactive", Session.this.snapshot(System.nanoTime()));
            super.channelInactive(context);
         }

         @Override
         public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof ByteBuf) {
               long now = System.nanoTime();
               if (Session.this.readNanos != 0) maximum(Session.this.maxRawReadGapNanos, now - Session.this.readNanos);
               Session.this.rawReadBytes += ((ByteBuf)message).readableBytes();
               Session.this.readNanos = now;
            }
            super.channelRead(context, message);
         }

         @Override
         public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof ByteBuf) Session.this.rawWriteBytes += ((ByteBuf)message).readableBytes();
            super.write(context, message, promise);
         }

         @Override
         public void close(ChannelHandlerContext context, ChannelPromise promise) throws Exception {
            Session.this.event("pipeline_close", fields("open", context.channel().isOpen(), "callerStack", stack(Thread.currentThread())));
            super.close(context, promise);
         }

         @Override
         public void channelWritabilityChanged(ChannelHandlerContext context) throws Exception {
            Session.this.event("writability_changed", fields("writable", context.channel().isWritable()));
            super.channelWritabilityChanged(context);
         }
      }

      private final class PacketObserver extends ChannelDuplexHandler {
         @Override
         public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof Packet) {
               long now = System.nanoTime();
               if (Session.this.decodedNanos != 0) maximum(Session.this.maxDecodedGapNanos, now - Session.this.decodedNanos);
               Session.this.readPackets++;
               Session.this.decodedNanos = now;
               Session.this.lastReadPacket = message.getClass().getSimpleName();
               if (message instanceof S00PacketKeepAlive) {
                  Session.this.keepAliveNanos = Session.this.decodedNanos;
                  Session.this.event("keepalive_received", fields("key", ((S00PacketKeepAlive)message).getId()));
               }
               if (message instanceof S40PacketDisconnect) Session.this.event("server_disconnect_packet", fields());
            }
            super.channelRead(context, message);
         }

         @Override
         public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (message instanceof Packet) {
               String type = message.getClass().getSimpleName();
               long started = System.nanoTime();
               if (Session.this.writeNanos != 0) maximum(Session.this.maxOutboundGapNanos, started - Session.this.writeNanos);
               Session.this.writePackets++;
               Session.this.writeNanos = started;
               Session.this.lastWritePacket = type;
               if (promise.isVoid()) Session.this.voidWrites++;
               else {
                  Session.this.pendingWrites++;
                  boolean keepAlive = message instanceof C00PacketKeepAlive;
                  int key = keepAlive ? ((C00PacketKeepAlive)message).getKey() : 0;
                  if (keepAlive) Session.this.event("keepalive_write_started", fields("key", key));
                  promise.addListener(future -> {
                     Session.this.pendingWrites--;
                     long duration = System.nanoTime() - started;
                     maximum(Session.this.maxWriteNanos, duration);
                     if (future.isSuccess()) Session.this.completedWrites++;
                     else {
                        Session.this.failedWrites++;
                        Map<String, Object> record = failure(future.cause());
                        record.put("packet", type);
                        record.put("cancelled", future.isCancelled());
                        Session.this.event("write_failed", record);
                     }
                     if (keepAlive) {
                        Session.this.event("keepalive_write_complete", fields("key", key, "success", future.isSuccess(), "durationMs", millis(duration)));
                     }
                  });
               }
            }
            super.write(context, message, promise);
         }
      }
   }
}
