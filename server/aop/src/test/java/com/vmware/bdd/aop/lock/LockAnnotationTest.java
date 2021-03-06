package com.vmware.bdd.aop.lock;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.vmware.bdd.manager.TestManager;

public class LockAnnotationTest {

   private static class LockTestThread extends Thread {
      private boolean concurrent;
      private TestManager mgr;
      private boolean failMethod;

      public LockTestThread(boolean concurrent, TestManager mgr) {
         this.concurrent = concurrent;
         this.mgr = mgr;
      }

      public LockTestThread(boolean concurrent, TestManager mgr,
            boolean failMethod) {
         this.concurrent = concurrent;
         this.mgr = mgr;
         this.failMethod = failMethod;
      }

      public void run() {
         try {
            if (!failMethod) {
               if (concurrent) {
                  mgr.competitiveLock(LOCKED_CLUSTER_NAME, 200);
               } else {
                  mgr.exclusiveLock(LOCKED_CLUSTER_NAME, 200);
               }
            } else {
               try {
                  mgr.exclusiveLockFailedOperation(LOCKED_CLUSTER_NAME, 200);
               } catch (Exception e) {
                  System.out.println("Get exception " + e.getMessage());
               }
            }
         } catch (Exception e) {
            System.out.println("Failed to lock for " + e.getMessage());
         }
      }
   }

   private static final String LOCKED_CLUSTER_NAME = "LockedClusterEntity";
   private static final String UNLOCKED_CLUSTER_NAME = "UnLockedClusterEntity";

   private ApplicationContext ctx;
   private TestManager mgr;

   @BeforeTest
   public void beforeTest() {
      ctx =
            new ClassPathXmlApplicationContext(
                  "META-INF/spring/test-aop-context.xml");
      mgr = ctx.getBean(TestManager.class);
   }

   @Test
   public void testSequentialInOneThread() throws Exception {
      mgr.competitiveLock(LOCKED_CLUSTER_NAME, 0);
      mgr.competitiveLock(LOCKED_CLUSTER_NAME, 0);
      mgr.exclusiveLock(LOCKED_CLUSTER_NAME, 0);
      mgr.exclusiveLock(LOCKED_CLUSTER_NAME, 0);
   }

   @Test
   public void testConcurrencyInTwoThread() throws Exception {
      LockTestThread t = new LockTestThread(true, mgr);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      mgr.competitiveLock(LOCKED_CLUSTER_NAME, 0);
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) < 20);
      t.join();
   }

   @Test
   public void testExclusiveInTwoThread() throws Exception {
      LockTestThread t = new LockTestThread(false, mgr);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      mgr.exclusiveLock(LOCKED_CLUSTER_NAME, 0);
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) > 150);
      t.join();
   }

   @Test
   public void testExclusiveCompetitiveInTwoThread() throws Exception {
      LockTestThread t = new LockTestThread(false, mgr);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      mgr.competitiveLock(LOCKED_CLUSTER_NAME, 0);
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) > 150);
      t.join();
   }

   @Test
   public void testReverseInTwoThread() throws Exception {
      LockTestThread t = new LockTestThread(true, mgr);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      mgr.exclusiveLock(LOCKED_CLUSTER_NAME, 0);
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) > 150);
      t.join();
   }

   @Test
   public void testCompetitiveInTwoThreadForTwoClusters() throws Exception {
      LockTestThread t = new LockTestThread(false, mgr);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      mgr.exclusiveLock(UNLOCKED_CLUSTER_NAME, 0);
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) < 20);
      t.join();
   }

   @Test
   public void testReleaseDelayed() throws Exception {
      long start = System.currentTimeMillis();
      LockFactory.getClusterLock(LOCKED_CLUSTER_NAME).writeLock().lock();
      System.out.println("Lock exlusively for " + LOCKED_CLUSTER_NAME
            + " separately.");
      LockTestThread t = new LockTestThread(false, mgr);
      t.start();
      Thread.sleep(50);
      LockFactory.getClusterLock(LOCKED_CLUSTER_NAME).writeLock().unlock();
      t.join();
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) > 230);
   }

   @Test
   public void testExclusiveFailedInTwoThread() throws Exception {
      LockTestThread t = new LockTestThread(false, mgr, true);
      t.start();
      Thread.sleep(20);
      long start = System.currentTimeMillis();
      try {
         mgr.exclusiveLockFailedOperation(LOCKED_CLUSTER_NAME, 0);
      } catch (Exception e) {
         System.out.println("Get exception " + e.getMessage());
      }
      long end = System.currentTimeMillis();
      System.out.println("Lock takes " + (end - start) + "ms.");
      Assert.assertTrue((end - start) > 150);
      t.join();
   }
}
