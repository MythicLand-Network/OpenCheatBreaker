package com.cheatbreaker.client.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NettyCompressionDecoder;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.NettyEncryptingDecoder;
import net.minecraft.network.NettyEncryptingEncoder;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.util.CryptManager;
import net.minecraft.util.MessageDeserializer;
import net.minecraft.util.MessageDeserializer2;
import net.minecraft.util.MessageSerializer;
import net.minecraft.util.MessageSerializer2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDiagnosticsTest {
   @TempDir
   Path directory;

   @Test
   void preservesTrafficAndRecordsKeepaliveStagesWithoutPayloads() throws Exception {
      Path path = this.directory.resolve("connection.jsonl");
      EmbeddedChannel channel = channel();
      ConnectionDiagnostics recorder = new ConnectionDiagnostics(path, Thread.currentThread());
      Map<String, Object> snapshot;
      try {
         recorder.enable();
         NetworkManager manager = observe(recorder, channel);
         ConnectionDiagnostics.Session session = manager.connectionDiagnostics;
         ByteBuf bytes = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4});
         channel.writeInbound(bytes);
         assertSame(bytes, channel.readInbound());
         assertEquals(0, bytes.readerIndex(), "The raw observer must not consume socket data");
         assertEquals(1, bytes.refCnt());
         bytes.release();

         int key = 12345;
         S00PacketKeepAlive request = new S00PacketKeepAlive(key);
         channel.writeInbound(request);
         assertSame(request, channel.readInbound());
         session.keepAliveHandled(key);
         C00PacketKeepAlive reply = new C00PacketKeepAlive(key);
         long requested = session.dispatchRequested(reply);
         session.dispatched(requested);
         assertTrue(channel.writeAndFlush(reply).isSuccess());
         assertSame(reply, channel.readOutbound());

         C01PacketChatMessage chat = new C01PacketChatMessage("private-chat-content");
         assertTrue(channel.writeAndFlush(chat).isSuccess());
         assertSame(chat, channel.readOutbound());
         long queued = session.taskQueued();
         long started = session.taskStarted(queued, request);
         session.taskFinished(started, request);
         snapshot = session.snapshot(System.nanoTime());
         recorder.sample();
         Map<String, Object> pendingProbe = session.snapshot(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
         assertTrue(((Number)pendingProbe.get("eventLoopProbeAgeMs")).doubleValue() >= 1000);
         channel.runPendingTasks();
         assertEquals(0, ((Number)session.snapshot(System.nanoTime()).get("eventLoopProbeAgeMs")).intValue());
         session.closeRequested("disconnect.endOfStream");
         channel.close();
      } finally {
         channel.finishAndReleaseAll();
         recorder.close();
      }
      assertEquals(4, ((Number)snapshot.get("rawReadBytesTotal")).intValue());
      assertEquals(1, ((Number)snapshot.get("decodedPacketsTotal")).intValue());
      assertEquals(2, ((Number)snapshot.get("outboundPacketsTotal")).intValue());
      assertEquals(2, ((Number)snapshot.get("writeCompletedTotal")).intValue());
      assertEquals(0, ((Number)snapshot.get("writePending")).intValue());
      assertEquals(0L, snapshot.get("mainTasksPending"));
      assertEquals(0L, snapshot.get("dispatchPending"));
      List<String> stages = new ArrayList<>();
      for (JsonObject record : records(path)) {
         String event = record.get("event").getAsString();
         if (event.startsWith("keepalive_")) {
            stages.add(event);
            assertEquals(12345, record.get("key").getAsInt());
         }
      }
      assertEquals(java.util.Arrays.asList("keepalive_received", "keepalive_handled", "keepalive_send_requested",
         "keepalive_write_started", "keepalive_write_complete"), stages);
      String log = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertFalse(log.contains("private-chat-content"));
      assertTrue(log.contains("connection_closed"));
      assertTrue(log.contains("channel_inactive"));
   }

   @Test
   void keepsObservingBothSidesOfCompressionAndEncryptionAddedAfterAttachment() throws Exception {
      EmbeddedChannel client = codecChannel(EnumPacketDirection.CLIENTBOUND, EnumPacketDirection.SERVERBOUND);
      EmbeddedChannel peer = codecChannel(EnumPacketDirection.SERVERBOUND, EnumPacketDirection.CLIENTBOUND);
      ConnectionDiagnostics recorder = new ConnectionDiagnostics(this.directory.resolve("connection.jsonl"), Thread.currentThread());
      try {
         recorder.enable();
         NetworkManager manager = observe(recorder, client);
         SecretKey secret = CryptManager.createNewSharedKey();
         secure(client, secret);
         secure(peer, secret);
         client.writeOutbound(new C00PacketKeepAlive(5678));
         ByteBuf outbound = client.readOutbound();
         int sentBytes = outbound.readableBytes();
         peer.writeInbound(outbound);
         C00PacketKeepAlive decodedReply = peer.readInbound();
         assertEquals(5678, decodedReply.getKey());
         peer.writeOutbound(new S00PacketKeepAlive(6789));
         ByteBuf inbound = peer.readOutbound();
         int receivedBytes = inbound.readableBytes();
         client.writeInbound(inbound);
         S00PacketKeepAlive decodedRequest = client.readInbound();
         assertEquals(6789, decodedRequest.getId());
         Map<String, Object> snapshot = manager.connectionDiagnostics.snapshot(System.nanoTime());
         assertEquals(sentBytes, ((Number)snapshot.get("rawWriteBytesSubmittedTotal")).intValue());
         assertEquals(receivedBytes, ((Number)snapshot.get("rawReadBytesTotal")).intValue());
         assertEquals(1L, snapshot.get("decodedPacketsTotal"));
         assertEquals(1L, snapshot.get("outboundPacketsTotal"));
      } finally {
         client.finishAndReleaseAll();
         peer.finishAndReleaseAll();
         recorder.close();
      }
   }

   @Test
   void togglingDetachesObserversWithoutClosingConnectionsOrReusingSessions() throws Exception {
      Path path = this.directory.resolve("connection.jsonl");
      EmbeddedChannel channel = channel();
      ConnectionDiagnostics recorder = new ConnectionDiagnostics(path, Thread.currentThread());
      try {
         recorder.enable();
         NetworkManager manager = observe(recorder, channel);
         ConnectionDiagnostics.Session original = manager.connectionDiagnostics;
         recorder.disable();
         assertTrue(channel.isOpen(), "Disabling diagnostics must not close a game connection");
         assertNull(manager.connectionDiagnostics);
         assertNull(channel.pipeline().get("connection_diagnostics_raw"));
         recorder.enable();
         recorder.observe(manager, channel);
         assertNotSame(original, manager.connectionDiagnostics);
         assertEquals(0, original.dispatchRequested(new C00PacketKeepAlive(99)));
         channel.writeAndFlush(new C00PacketKeepAlive(42));
         channel.readOutbound();
         assertEquals(1L, manager.connectionDiagnostics.snapshot(System.nanoTime()).get("outboundPacketsTotal"));
      } finally {
         channel.finishAndReleaseAll();
         recorder.close();
      }
      List<Long> sessions = new ArrayList<>();
      for (JsonObject record : records(path)) {
         if (record.get("event").getAsString().equals("connection_observed")) sessions.add(record.get("session").getAsLong());
      }
      assertEquals(2, sessions.size());
      assertNotEquals(sessions.get(0), sessions.get(1));
   }

   @Test
   void rapidTogglesRemainConsistentAcrossTheClientAndEventLoopThreads() throws Exception {
      NioEventLoopGroup group = new NioEventLoopGroup(1);
      ConnectionDiagnostics recorder = new ConnectionDiagnostics(this.directory.resolve("connection.jsonl"), Thread.currentThread());
      Channel channel = null;
      try {
         channel = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
               channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
            }
         }).register().sync().channel();
         NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
         manager.channel = channel;
         recorder.enable();
         for (int attempt = 0; attempt < 40; attempt++) {
            recorder.observe(manager, channel);
            recorder.disable();
            recorder.enable();
         }
         recorder.observe(manager, channel);
         channel.eventLoop().submit(() -> {}).sync();
         assertNotNull(manager.connectionDiagnostics);
         assertNotNull(channel.pipeline().get("connection_diagnostics_raw"));
         assertNotNull(channel.pipeline().get("connection_diagnostics_packets"));
         recorder.disable();
         channel.eventLoop().submit(() -> {}).sync();
         assertTrue(channel.isOpen());
         assertNull(manager.connectionDiagnostics);
         assertNull(channel.pipeline().get("connection_diagnostics_raw"));
         assertNull(channel.pipeline().get("connection_diagnostics_packets"));
      } finally {
         if (channel != null) channel.close().sync();
         group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
         recorder.close();
      }
   }

   @Test
   void observesFailedWritesWithoutChangingPromisesOrLoggingExceptionMessages() throws Exception {
      Path path = this.directory.resolve("connection.jsonl");
      EmbeddedChannel channel = channel();
      IOException cause = new IOException("Connection reset by peer: private-transport-detail");
      channel.pipeline().addBefore("packet_handler", "failed_transport", new ChannelOutboundHandlerAdapter() {
         @Override
         public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            promise.setFailure(cause);
         }
      });
      ConnectionDiagnostics recorder = new ConnectionDiagnostics(path, Thread.currentThread());
      try {
         recorder.enable();
         NetworkManager manager = observe(recorder, channel);
         ChannelFuture future = channel.writeAndFlush(new C00PacketKeepAlive(7));
         assertSame(cause, future.cause());
         assertTrue(channel.isOpen());
         Map<String, Object> snapshot = manager.connectionDiagnostics.snapshot(System.nanoTime());
         assertEquals(1L, snapshot.get("writeFailedTotal"));
         assertEquals(0L, snapshot.get("writePending"));
         manager.connectionDiagnostics.exception(cause);
      } finally {
         channel.finishAndReleaseAll();
         recorder.close();
      }
      String log = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertTrue(log.contains("write_failed"));
      assertTrue(log.contains("network_exception"));
      assertTrue(log.contains("\"transportError\":\"reset\""));
      assertFalse(log.contains("private-transport-detail"));
   }

   private static EmbeddedChannel channel() {
      EmbeddedChannel channel = new EmbeddedChannel();
      channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
      return channel;
   }

   private static NetworkManager observe(ConnectionDiagnostics recorder, EmbeddedChannel channel) {
      NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
      manager.channel = channel;
      recorder.observe(manager, channel);
      assertNotNull(manager.connectionDiagnostics);
      return manager;
   }

   private static EmbeddedChannel codecChannel(EnumPacketDirection inbound, EnumPacketDirection outbound) {
      EmbeddedChannel channel = new EmbeddedChannel();
      channel.attr(NetworkManager.attrKeyConnectionState).set(EnumConnectionState.PLAY);
      channel.pipeline().addLast("splitter", new MessageDeserializer2());
      channel.pipeline().addLast("decoder", new MessageDeserializer(inbound));
      channel.pipeline().addLast("prepender", new MessageSerializer2());
      channel.pipeline().addLast("encoder", new MessageSerializer(outbound));
      channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
      return channel;
   }

   private static void secure(EmbeddedChannel channel, SecretKey secret) {
      channel.pipeline().addBefore("splitter", "decrypt", new NettyEncryptingDecoder(CryptManager.createNetCipherInstance(2, secret)));
      channel.pipeline().addBefore("prepender", "encrypt", new NettyEncryptingEncoder(CryptManager.createNetCipherInstance(1, secret)));
      channel.pipeline().addBefore("decoder", "decompress", new NettyCompressionDecoder(1));
      channel.pipeline().addBefore("encoder", "compress", new NettyCompressionEncoder(1));
   }

   private static List<JsonObject> records(Path path) throws IOException {
      List<JsonObject> result = new ArrayList<>();
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) result.add(JsonParser.parseString(line).getAsJsonObject());
      return result;
   }
}
