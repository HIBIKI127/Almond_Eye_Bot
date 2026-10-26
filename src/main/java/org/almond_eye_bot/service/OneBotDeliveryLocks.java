package org.almond_eye_bot.service;
/** Bounded locks shared by conversational batches and scheduled notifications. */
public final class OneBotDeliveryLocks {
 private static final Object[] LOCKS=new Object[128];
 static {java.util.Arrays.setAll(LOCKS,i->new Object());}
 private OneBotDeliveryLocks(){}
 public static Object target(String self,boolean group,String target){return LOCKS[Math.floorMod((self+":"+(group?"group":"private")+":"+target).hashCode(),LOCKS.length)];}
}
