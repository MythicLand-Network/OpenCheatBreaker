package net.minecraft.network;

import com.cheatbreaker.client.diagnostics.ConnectionDiagnostics;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.IThreadListener;
import net.optifine.Config;

public class PacketThreadUtil {
   public static int lastDimensionId = Integer.MIN_VALUE;

   public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> packet, final T value, IThreadListener threadListener) throws ThreadQuickExitException {
      if (!threadListener.isCallingFromMinecraftThread()) {
         final ConnectionDiagnostics.Session diagnostics = value instanceof NetHandlerPlayClient
            ? ((NetHandlerPlayClient)value).getNetworkManager().connectionDiagnostics : null;
         final long queuedNanos = diagnostics == null ? 0 : diagnostics.taskQueued();
         threadListener.addScheduledTask(new Runnable() {
            @Override
            public void run() {
               long startedNanos = diagnostics == null ? 0 : diagnostics.taskStarted(queuedNanos, packet);
               try {
                  PacketThreadUtil.clientPreProcessPacket(packet);
                  packet.processPacket(value);
               } finally {
                  if (diagnostics != null) diagnostics.taskFinished(startedNanos, packet);
               }
            }
         });
         throw ThreadQuickExitException.INSTANCE;
      }

      clientPreProcessPacket(packet);
   }

   public static void clientPreProcessPacket(Packet packet) {
      if (packet instanceof S08PacketPlayerPosLook) {
         Config.getRenderGlobal().onPlayerPositionSet();
      }

      if (packet instanceof S07PacketRespawn) {
         S07PacketRespawn s07PacketRespawn = (S07PacketRespawn)packet;
         lastDimensionId = s07PacketRespawn.getDimensionID();
      } else if (packet instanceof S01PacketJoinGame) {
         S01PacketJoinGame s01PacketJoinGame = (S01PacketJoinGame)packet;
         lastDimensionId = s01PacketJoinGame.getDimension();
      } else {
         lastDimensionId = Integer.MIN_VALUE;
      }
   }
}
