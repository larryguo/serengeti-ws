/***************************************************************************
 * Copyright (c) 2012-2013 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/
package com.vmware.aurora.security;

import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;

import com.vmware.aurora.exception.CertificateMgmtException;
import com.vmware.aurora.global.Configuration;
import com.vmware.aurora.util.AuAssert;

/*
 * CMS key store for storing all sorts of secrets. The file is located in the
 * default resource directory.
 *
 * The Aurora CMS KeyStore was generated by /opt/aurora/etc/postinstall.d/30-cms
 */
public class CmsKeyStore {
   private static Logger logger = Logger.getLogger(CmsKeyStore.class);
   private static ReadWriteLock lock = new ReentrantReadWriteLock();

   static KeyStore keyStore = null;
   static private String cmsKeyStorePath;
   static private String keyStorePswd;
   static private String cmsKeyPswd;
   static private String vcExtPswd;

   private static final String CMS_KEYSTORE = "cms.keystore";
   public static final String CMS_KEYSTORE_PSWD = "cms.keystore_pswd";

   static final public String VC_EXT_KEY = "vc_ext";

   public static final String CMS_AUTO_KEY = "datacloud";

   public static final String CMS_ROOT_CA = "rootCA";

   static {
      try {
         cmsKeyStorePath = Configuration.getString(CMS_KEYSTORE, "/opt/aurora/cms/cms.key");
         keyStorePswd = Configuration.getString(CMS_KEYSTORE_PSWD);
         cmsKeyPswd = Configuration.getString(CMS_KEYSTORE_PSWD);
         vcExtPswd = Configuration.getString(CMS_KEYSTORE_PSWD);
         keyStore = loadKeyStore();
      } catch (Exception e) {
         logger.info("cannot load cms keystore", e);
      }
   }

   public static Lock getWriteLock() {
      logger.debug("Get CMS keystore write lock");
      return lock.writeLock();
   }

   public static Lock getReadLock() {
      logger.debug("Get CMS keystore read lock");
      return lock.readLock();
   }

   public static KeyStore loadKeyStore()
         throws NoSuchAlgorithmException,
                CertificateException,
                IOException,
                KeyStoreException,
                InterruptedException {
      KeyStore store = null;
      Lock lock = getReadLock();
      if (!lock.tryLock(10, TimeUnit.MINUTES)) {
         throw CertificateMgmtException.ACQUIRE_TRUST_LOCK_TIMEOUT();
      }
      try {
         store = JksKeyStoreUtil.loadKeyStore(cmsKeyStorePath, keyStorePswd);
      } finally {
         lock.unlock();
      }
      return store;
   }

   public static String getKeyStoreFilePath() {
      return cmsKeyStorePath;
   }

   public static String getKeyStorePassword() {
      return keyStorePswd;
   }

   public static String getCMSKeyPassword() {
      return cmsKeyPswd;
   }

   public static String getVCExtPassword() {
      return vcExtPswd;
   }

   public static KeyStore setCmsKey(String alias,
         Key key,
         Certificate[] certChain)
         throws KeyStoreException,
                NoSuchAlgorithmException,
                CertificateException,
                IOException,
                InterruptedException {
      KeyStore store = null;
      Lock lock = getWriteLock();
      if (!lock.tryLock(10, TimeUnit.MINUTES)) {
         throw CertificateMgmtException.ACQUIRE_TRUST_LOCK_TIMEOUT();
      }
      try {
         store = JksKeyStoreUtil.loadKeyStore(cmsKeyStorePath, keyStorePswd);
         store.setKeyEntry(alias,
               key,
               CmsKeyStore.getCMSKeyPassword().toCharArray(),
               certChain);
         JksKeyStoreUtil.serializeKeyStore(cmsKeyStorePath, store, keyStorePswd);
      } finally {
         lock.unlock();
      }
      return store;
   }

   public static KeyStore removeCmsKey(String alias)
         throws KeyStoreException,
                NoSuchAlgorithmException,
                CertificateException,
                IOException,
                InterruptedException {
      KeyStore store = null;
      Lock lock = getWriteLock();
      if (!lock.tryLock(10, TimeUnit.MINUTES)) {
         throw CertificateMgmtException.ACQUIRE_TRUST_LOCK_TIMEOUT();
      }
      try {
         store = JksKeyStoreUtil.loadKeyStore(cmsKeyStorePath, keyStorePswd);
         if (store.containsAlias(alias)) {
            store.deleteEntry(alias);
            JksKeyStoreUtil.serializeKeyStore(cmsKeyStorePath, store, keyStorePswd);
         }
      } finally {
         lock.unlock();
      }
      return store;
   }

   static public KeyStore getKeyStore() {
      return keyStore;
   }

   static public Certificate getCertificate(String alias) throws KeyStoreException {
      AuAssert.check(keyStore != null);
      return keyStore.getCertificate(alias);
   }
}
