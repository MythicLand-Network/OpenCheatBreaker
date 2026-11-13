package com.cheatbreaker.client.mod.impl;

import com.cheatbreaker.client.CheatBreaker;
import com.cheatbreaker.client.event.SimpleEvent40;
import com.cheatbreaker.client.mod.AbstractCombatValueHudMod;
import com.cheatbreaker.client.ui.screen.ModsScreen;
import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import org.apache.logging.log4j.LogManager;

public final class HitRegistrationDebugMod extends AbstractCombatValueHudMod {
   public static final String CHANNEL = "ML|HitTrace";
   private static final int VERSION = 1;
   private static final long TIMEOUT_MILLIS = 2500;
   private static final long RETENTION_MILLIS = 15000;
   private static final int MAX_PENDING = 512;
   private static final int MAX_CHAT_NOTICES = 512;
   private static final Gson JSON = new Gson();
   private final Map<Long, Attack> pending = new LinkedHashMap<>();
   private final Deque<ChatNotice> chatNotices = new ArrayDeque<>();
   private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
      0, 1, 5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512), task -> {
         Thread thread = new Thread(task, "hit-registration-writer");
         thread.setDaemon(true);
         return thread;
      }, new ThreadPoolExecutor.AbortPolicy()
   );
   private NetworkManager connection;
   private long session;
   private long sequence;
   private int noDamage;
   private int unconfirmed;
   private Attack click;

   public HitRegistrationDebugMod() {
      super("Hit Registration Debug");
      this.setDescription("Records server-confirmed hits and puts clickable diagnostics in chat.");
      this.handleString("[0 No Damage]");
      this.addEventHandler(SimpleEvent40.class, event -> this.showNextChatNotice());
   }

   public static HitRegistrationDebugMod active() {
      CheatBreaker client = CheatBreaker.getInstance();
      if (client == null || client.getModManager() == null) return null;
      HitRegistrationDebugMod mod = client.getModManager().hitRegistrationDebugMod;
      return mod != null && mod.isEnabled() ? mod : null;
   }

   public void beginClick(MovingObjectPosition hit) {
      if (this.session == 0 || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
            || !(hit.entityHit instanceof EntityPlayer) || this.pending.size() >= MAX_PENDING) return;
      Entity target = hit.entityHit;
      this.click = new Attack(++this.sequence, target.getEntityId(), target.getName());
   }

   public void endClick() {
      Attack attack = this.click;
      this.click = null;
      if (attack == null) return;
      if (!attack.tagged) {
         if (attack.rejectionReason != null) {
            this.report(attack, "Attack Not Sent", attack.rejectionReason, -1, 0, 0, false);
         } else {
            this.report(attack, "Unconfirmed", "client.attack_not_traced", -1, 0, 0, false);
         }
         return;
      }
      this.pending.put(attack.id, attack);
      this.record(attack, "attempt", "client.attack", -1, 0, 0, false);
   }

   public void rejectClick(String reason) {
      if (this.click != null) this.click.rejectionReason = reason;
   }

   public static void localParticle(Entity entity, int kind) {
      HitRegistrationDebugMod mod = active();
      if (mod != null && mod.click != null && mod.click.targetId == entity.getEntityId()) mod.click.particles |= kind;
   }

   public static AttackWrite tagAttack(NetworkManager connection, Packet packet) {
      if (!(packet instanceof C02PacketUseEntity)) return null;
      HitRegistrationDebugMod mod = active();
      if (mod == null || !mod.mc.isCallingFromMinecraftThread() || mod.connection != connection || mod.click == null) return null;
      C02PacketUseEntity use = (C02PacketUseEntity) packet;
      Attack attack = mod.click;
      if (attack.tagged || use.getAction() != C02PacketUseEntity.Action.ATTACK || use.entityId != attack.targetId) return null;
      attack.tagged = true;
      return new AttackWrite(mod, connection, mod.session, attack);
   }

   public void receive(NetworkManager source, PacketBuffer input) {
      if (input.readableBytes() < 10 || input.readUnsignedByte() != VERSION) return;
      int type = input.readUnsignedByte();
      long key = input.readLong();
      if (key == 0) return;
      if (type == 0 && input.readableBytes() == 0) {
         this.reset();
         this.connection = source;
         this.session = key;
         PacketBuffer reply = new PacketBuffer(Unpooled.buffer(10));
         reply.writeByte(VERSION).writeByte(1).writeLong(key);
         source.sendPacket(new C17PacketCustomPayload(CHANNEL, reply));
         return;
      }
      if (this.connection != source || this.session != key) return;
      if (type == 5 && input.readableBytes() == 0) {
         this.reset();
         return;
      }
      if (type != 4 || input.readableBytes() < 35) return;
      long id = input.readLong();
      int targetId = input.readInt();
      int tick = input.readInt();
      int outcome = input.readUnsignedByte();
      double health = input.readDouble();
      double absorption = input.readDouble();
      boolean knockback = input.readBoolean();
      int length = input.readUnsignedByte();
      if (length > 70 || length != input.readableBytes() || outcome > 2
            || !Double.isFinite(health) || !Double.isFinite(absorption) || health < 0 || absorption < 0) return;
      byte[] reasonBytes = new byte[length];
      input.readBytes(reasonBytes);
      String reason = new String(reasonBytes, StandardCharsets.US_ASCII);
      if (!reason.matches("[a-z0-9_.]+")) return;
      Attack attack = this.pending.get(id);
      if (attack == null || attack.targetId != targetId) return;
      this.pending.remove(id);
      if (attack.timedOut && outcome != 2) this.unconfirmed--;
      if (outcome == 0) {
         this.noDamage++;
         this.report(attack, "No Damage", reason, tick, health, absorption, knockback);
      } else if (outcome == 1) {
         this.report(attack, "Hit Confirmed", reason, tick, health, absorption, knockback);
      } else if (!attack.timedOut) {
         this.unconfirmed++;
         this.report(attack, "Unconfirmed", reason, tick, health, absorption, knockback);
      }
   }

   @Override
   public void onClientTickEvent(com.cheatbreaker.client.event.ClientTickEvent event) {
      if (this.connection == null) return;
      if (this.mc.getNetHandler() == null || this.mc.getNetHandler().getNetworkManager() != this.connection) {
         this.reset();
         return;
      }
      long now = System.currentTimeMillis();
      Iterator<Attack> iterator = this.pending.values().iterator();
      while (iterator.hasNext()) {
         Attack attack = iterator.next();
         long age = now - attack.timeMillis;
         if (!attack.timedOut && age >= TIMEOUT_MILLIS) {
            attack.timedOut = true;
            this.unconfirmed++;
            this.report(attack, "Unconfirmed", "server.no_response", -1, 0, 0, false);
         }
         if (age >= RETENTION_MILLIS) iterator.remove();
      }
   }

   @Override
   public void onDisable() {
      this.reset();
      super.onDisable();
   }

   @Override
   public void reset() {
      for (Attack attack : this.pending.values()) {
         if (!attack.timedOut) this.record(attack, "Unconfirmed", "session.ended", -1, 0, 0, false);
      }
      this.pending.clear();
      this.chatNotices.clear();
      this.connection = null;
      this.session = 0;
      this.sequence = 0;
      this.noDamage = 0;
      this.unconfirmed = 0;
      this.click = null;
   }

   @Override
   public Number getNumericValue() {
      return this.noDamage;
   }

   @Override
   public String getLabelText() {
      return "No Damage";
   }

   @Override
   public String getStaticText() {
      return "Recording | 2 No Damage | 1 Unconfirmed";
   }

   @Override
   public String getDynamicText() {
      if (this.mc.currentScreen instanceof ModsScreen) return null;
      return this.session == 0 ? "Waiting for server" :
         "Recording | " + this.noDamage + " No Damage | " + this.unconfirmed + " Unconfirmed";
   }

   @Override
   public String formatText(String text) {
      return text;
   }

   private void report(Attack attack, String status, String reason, int tick, double health, double absorption, boolean knockback) {
      this.record(attack, status, reason, tick, health, absorption, knockback);
      if (this.chatNotices.size() == MAX_CHAT_NOTICES) this.chatNotices.removeFirst();
      this.chatNotices.addLast(new ChatNotice(this.session, attack, status, reason, tick, health, absorption, knockback));
   }

   private void showNextChatNotice() {
      if (this.mc.ingameGUI == null) return;
      ChatNotice notice = this.chatNotices.pollFirst();
      if (notice == null) return;
      Attack attack = notice.attack;
      // The vanilla input field accepts at most 100 characters.
      String summary = "HitTrace " + Long.toHexString(notice.session) + "/" + attack.id + " E" + attack.targetId
         + " T" + notice.tick + " FX" + attack.particles + " KB" + (notice.knockback ? 1 : 0) + " " + notice.reason;
      ChatComponentText label = new ChatComponentText("[" + notice.status + "]");
      label.getChatStyle()
         .setColor("Hit Confirmed".equals(notice.status) ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
         .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, summary))
         .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(
            "Click to copy details into chat\nHealth: " + notice.health + " | Absorption: " + notice.absorption
         )));
      ChatComponentText line = new ChatComponentText("");
      line.appendSibling(label).appendText(" " + attack.name + " #" + attack.id);
      int lineId = (int) (0x48495400L ^ (notice.session * 31 + attack.id));
      this.mc.ingameGUI.getChatGUI().setChatLine(line, lineId, this.mc.ingameGUI.getUpdateCounter(), false);
   }

   private void record(Attack attack, String status, String reason, int tick, double health, double absorption, boolean knockback) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("session", Long.toHexString(this.session));
      fields.put("attack", attack.id);
      fields.put("timeMillis", attack.timeMillis);
      fields.put("targetId", attack.targetId);
      fields.put("target", attack.name);
      fields.put("particles", attack.particles);
      fields.put("status", status);
      fields.put("reason", reason);
      fields.put("tick", tick);
      fields.put("health", health);
      fields.put("absorption", absorption);
      fields.put("knockback", knockback);
      Path log = this.mc.mcDataDir.toPath().resolve("logs").resolve("hit-registration.jsonl");
      try {
         this.writer.execute(() -> {
            try {
               Files.createDirectories(log.getParent());
               if (Files.exists(log) && Files.size(log) >= 16L * 1024 * 1024) {
                  Files.move(log, log.resolveSibling("hit-registration.previous.jsonl"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
               }
               Files.write(log, (JSON.toJson(fields) + "\n").getBytes(StandardCharsets.UTF_8),
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException error) {
               LogManager.getLogger().warn("Cannot write hit registration diagnostics", error);
            }
         });
      } catch (java.util.concurrent.RejectedExecutionException error) {
         LogManager.getLogger().warn("Hit registration diagnostic queue is full");
      }
   }

   private static final class Attack {
      private final long id;
      private final int targetId;
      private final String name;
      private final long timeMillis = System.currentTimeMillis();
      private String rejectionReason;
      private int particles;
      private boolean tagged;
      private boolean timedOut;

      private Attack(long id, int targetId, String name) {
         this.id = id;
         this.targetId = targetId;
         this.name = name;
      }
   }

   private static final class ChatNotice {
      private final long session;
      private final Attack attack;
      private final String status;
      private final String reason;
      private final int tick;
      private final double health;
      private final double absorption;
      private final boolean knockback;

      private ChatNotice(long session, Attack attack, String status, String reason, int tick, double health, double absorption, boolean knockback) {
         this.session = session;
         this.attack = attack;
         this.status = status;
         this.reason = reason;
         this.tick = tick;
         this.health = health;
         this.absorption = absorption;
         this.knockback = knockback;
      }
   }

   public static final class AttackWrite {
      private final HitRegistrationDebugMod mod;
      private final NetworkManager connection;
      private final long session;
      private final Attack attack;

      private AttackWrite(HitRegistrationDebugMod mod, NetworkManager connection, long session, Attack attack) {
         this.mod = mod;
         this.connection = connection;
         this.session = session;
         this.attack = attack;
      }

      public C17PacketCustomPayload marker(int type) {
         PacketBuffer data = new PacketBuffer(Unpooled.buffer(22));
         data.writeByte(VERSION).writeByte(type).writeLong(this.session).writeLong(this.attack.id).writeInt(this.attack.targetId);
         return new C17PacketCustomPayload(CHANNEL, data);
      }

      public void completed(boolean written) {
         this.mod.mc.addScheduledTask(() -> {
            if (this.mod.connection != this.connection || this.mod.session != this.session) return;
            if (written) {
               this.mod.record(this.attack, "sent", "client.write_complete", -1, 0, 0, false);
            } else if (this.mod.pending.remove(this.attack.id) != null) {
               this.mod.report(this.attack, "Attack Not Sent", "client.write_failed", -1, 0, 0, false);
            }
         });
      }
   }
}
