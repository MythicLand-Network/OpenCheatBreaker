package com.cheatbreaker.client.mod.impl;

import com.cheatbreaker.client.diagnostics.ConnectionDiagnostics;
import com.cheatbreaker.client.mod.AbstractMod;
import com.cheatbreaker.client.mod.ModCategory;
import net.minecraft.network.NetworkManager;

public final class ConnectionDiagnosticsMod extends AbstractMod {
   public ConnectionDiagnosticsMod() {
      super("Connection Diagnostics");
      this.setDescription("Records connection, keepalive and client stalls to logs/connection-diagnostics.jsonl.");
      this.setCategories(ModCategory.SERVER, ModCategory.MISC);
      this.setHudOnly(false);
      this.setRenderHudAndDefault(false);
      this.handleString("[NET]");
      this.setEnabledByDefault(true);
   }

   @Override
   public void onEnable() {
      super.onEnable();
      ConnectionDiagnostics.start(this.mc.mcDataDir.toPath(), Thread.currentThread());
      if (this.mc.getNetHandler() != null) {
         NetworkManager manager = this.mc.getNetHandler().getNetworkManager();
         if (manager.getChannel() != null) ConnectionDiagnostics.attach(manager, manager.getChannel());
      }
   }

   @Override
   public void onDisable() {
      ConnectionDiagnostics.stop();
      super.onDisable();
   }
}
