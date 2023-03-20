package com.amazonaws.glue.catalog.converters;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import com.amazonaws.services.glue.model.Table;

import com.google.gson.Gson;

public class ConverterUtils {

  private static final Gson gson = new Gson();

  public static String catalogTableToString(final Table table) {
    return gson.toJson(table);
  }

  public static Table stringToCatalogTable(final String input) {
    return gson.fromJson(input, Table.class);
  }

  public static org.apache.hadoop.hive.metastore.api.Date dateToHiveDate(Date date) {
    return new org.apache.hadoop.hive.metastore.api.Date(TimeUnit.MILLISECONDS.toDays(date.getTime()));
  }

  public static Date hiveDatetoDate(org.apache.hadoop.hive.metastore.api.Date hiveDate) {
    return new Date(TimeUnit.DAYS.toMillis(hiveDate.getDaysSinceEpoch()));
  }

  // XXX(kimtkyeom, junbong): 현재는 묻지도 따지지도 않고 s3 와 s3a 사이로 location을 변환한다. (각 converter의 convertStorageDescriptor 참조)
  // upstream merge가 가능하려면 MetastoreConverter나 Glue <-> Hive converter 사이에서 configurable한 형태가 되어야 하지 않을까..
  public static String convertLocationScheme(String location, String targetScheme) {
    return String.format("%s://%s", targetScheme, StringUtils.substringAfter(location, "://"));
  }
}
