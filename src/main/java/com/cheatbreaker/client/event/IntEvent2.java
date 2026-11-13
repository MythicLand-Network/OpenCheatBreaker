package com.cheatbreaker.client.event;

public class IntEvent2 extends CancellableEvent {
   public int intValue;
   private String rejectionReason;

   public int getIntValue() {
      return this.intValue;
   }

   public IntEvent2(int n) {
      this.intValue = n;
   }

   public void rejectClick(String reason) {
      this.rejectionReason = reason;
      this.setFlag(true);
   }

   public String getRejectionReason() {
      return this.rejectionReason;
   }
}
