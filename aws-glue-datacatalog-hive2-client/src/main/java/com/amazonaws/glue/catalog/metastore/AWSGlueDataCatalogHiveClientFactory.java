package com.amazonaws.glue.catalog.metastore;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaHookLoader;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.MetaException;
//import org.apache.hadoop.hive.ql.metadata.HiveMetaStoreClientFactory;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveMetastoreClientFactory;

import java.util.concurrent.ConcurrentHashMap;

public class AWSGlueDataCatalogHiveClientFactory {

  public IMetaStoreClient createMetaStoreClient(
      HiveConf conf, HiveMetaHookLoader hookLoader,
      boolean allowEmbedded,
      ConcurrentHashMap<String, Long> concurrentHashMap
  ) throws MetaException {
    AWSCatalogMetastoreClient client = new AWSCatalogMetastoreClient(conf, hookLoader);
    return client;
  }

}
