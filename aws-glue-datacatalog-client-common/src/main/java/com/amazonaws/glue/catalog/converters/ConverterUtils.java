package com.amazonaws.glue.catalog.converters;

import org.apache.commons.lang3.StringUtils;
import com.amazonaws.services.glue.model.Table;

import com.google.gson.Gson;

public class ConverterUtils {

  public static final String INDEX_DEFERRED_REBUILD = "DeferredRebuild";
  public static final String INDEX_TABLE_NAME = "IndexTableName";
  public static final String INDEX_HANDLER_CLASS = "IndexHandlerClass";
  public static final String INDEX_DB_NAME = "DbName";
  public static final String INDEX_ORIGIN_TABLE_NAME = "OriginTableName";
  private static final Gson gson = new Gson();

  public static String catalogTableToString(final Table table) {
    return gson.toJson(table);
  }

  public static Table stringToCatalogTable(final String input) {
    return gson.fromJson(input, Table.class);
  }

  // XXX(kimtkyeom): 현재는 묻지도 따지지도 않고 s3 와 s3a 사이로 location을 변환한다. (각 converter의 convertStorageDescriptor 참조)
  // upstream merge가 가능하려면 MetastoreConverter나 Glue <-> Hive converter 사이에서 configurable한 형태가 되어야 하지 않을까..
  public static String convertLocationScheme(String location, String targetScheme) {
    return String.format("%s://%s", targetScheme, StringUtils.substringAfter(location, "://"));
  }
}
