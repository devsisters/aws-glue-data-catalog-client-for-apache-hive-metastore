package com.amazonaws.glue.catalog.converters;

import com.amazonaws.glue.catalog.metastore.AWSGlueClientFactory;
import org.apache.commons.lang3.StringUtils;
import com.amazonaws.services.glue.model.Table;

import com.google.gson.Gson;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.PartFilterExprUtil;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.parser.ExpressionTree;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.log4j.Logger;

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

  // Convert given directSql partition pruning expr to glue filter expr
  // This logic is inspired from https://github.com/apache/hive/blob/cb213d88304034393d68cc31a95be24f5aac62b6/metastore/src/java/org/apache/hadoop/hive/metastore/MetaStoreDirectSql.java#L966
  public static class PartitionFilterConverter extends ExpressionTree.TreeVisitor {
    private static final Logger logger = Logger.getLogger(PartitionFilterConverter.class);

    // Need to be convert to hive metastore api model
    private final org.apache.hadoop.hive.metastore.api.Table table;
    private final ExpressionTree.FilterBuilder filterBuffer;

    private PartitionFilterConverter(Table table, String dbname) {
      this.table = CatalogToHiveConverter.convertTable(table, dbname);
      this.filterBuffer = new ExpressionTree.FilterBuilder(false);
    }

    // hive 에서 넘어온 partition filter expr을 glue catalog 맥락으로 전환
    // 이 과정에서 모종의 이슈가 발생 할 경우 로그로 정보만 남기고 null 리턴 (이 경우 partition pruning을 하지 않도록 fallback)
    public static String convertHiveToCatalog(Table table, String dbname, String filter) {
      try {
        final ExpressionTree tree = (filter != null && !filter.isEmpty())
                ? PartFilterExprUtil.getFilterParser(filter).tree : ExpressionTree.EMPTY_TREE;
        PartitionFilterConverter visitor = new PartitionFilterConverter(table, dbname);
        tree.accept(visitor);

        // (kimtkyeom) TODO: Need to throw?
        // 현 시점에서는 null 리턴하여 partition filtering 을 하지 않도록 fallback
        if (visitor.filterBuffer.hasError()) {
          logger.error(String.format("Error while converting filter expr, fallback to non-filtering, %s", visitor.filterBuffer.getErrorMessage()));
          return null;
        }

        return visitor.filterBuffer.getFilter();
      } catch (MetaException e) {
        logger.error("Error while converting filter expr, fallback to non-filtering", e);
        return null;
      }
    }

    @Override
    protected void beginTreeNode(ExpressionTree.TreeNode node) throws MetaException {
      filterBuffer.append(" (");
    }

    @Override
    protected void midTreeNode(ExpressionTree.TreeNode node) throws MetaException {
      filterBuffer.append((node.getAndOr() == ExpressionTree.LogicalOperator.AND) ? " and " : " or ");
    }

    @Override
    protected void endTreeNode(ExpressionTree.TreeNode node) throws MetaException {
      filterBuffer.append(") ");
    }

    @Override
    protected boolean shouldStop() {
      return filterBuffer.hasError();
    }

    private static enum FilterType {
      Integral,
      String,
      Date,

      Invalid;

      static FilterType fromType(String colTypeStr) {
        if (colTypeStr.equals(serdeConstants.STRING_TYPE_NAME)) {
          return FilterType.String;
        } else if (colTypeStr.equals(serdeConstants.DATE_TYPE_NAME)) {
          return FilterType.Date;
        } else if (serdeConstants.IntegralTypes.contains(colTypeStr)) {
          return FilterType.Integral;
        }
        return FilterType.Invalid;
      }

      public static FilterType fromClass(Object value) {
        if (value instanceof String) {
          return FilterType.String;
        } else if (value instanceof Long) {
          return FilterType.Integral;
        } else if (value instanceof java.sql.Date) {
          return FilterType.Date;
        }
        return FilterType.Invalid;
      }
    }

    @Override
    public void visit(ExpressionTree.LeafNode node) throws MetaException {
      int partColIndex = node.getPartColIndexForFilter(table, filterBuffer);
      if (filterBuffer.hasError()) return;

      // We skipped 'like', other ops should all work as long as the types are right.
      String colTypeStr = table.getPartitionKeys().get(partColIndex).getType();
      FilterType colType = FilterType.fromType(colTypeStr);
      if (colType == FilterType.Invalid) {
        filterBuffer.setError("Filter pushdown not supported for type " + colTypeStr);
        return;
      }
      FilterType valType = FilterType.fromClass(node.value);
      Object nodeValue = node.value;
      if (valType == FilterType.Invalid) {
        filterBuffer.setError("Filter pushdown not supported for value " + node.value.getClass());
        return;
      }

      String partFilterExpr = "";
      if (colType == FilterType.Integral) {
        partFilterExpr = node.keyName + node.operator.getOp() + node.value;
      } else if (colType == FilterType.String) {
        partFilterExpr = node.keyName + node.operator.getOp() + "'" + node.value + "'";
      } else if (colType == FilterType.Date && valType == FilterType.String) {
        // (kimtkyeom) directSql 로 들어오는 partition pruning expr 중 string 형태로 filter value가 들어오는 경우가 있음.
        // Glue는 unquoted date str을 받지 않으므로 quote를 추가해주는 대응을 한다.
        partFilterExpr = node.keyName + node.operator.getOp() + "'" + node.value + "'";
      } else if (colType == FilterType.Date && valType == FilterType.Date) {
        // (kimtkyeom) Date type 필터가 date type value로 들어온 경우, string으로 format
        String dateFormatted = HiveMetaStore.PARTITION_DATE_FORMAT.get().format((java.sql.Date) nodeValue);
        partFilterExpr = node.keyName + node.operator.getOp() + "'" + dateFormatted + "'";
      }

      filterBuffer.append(partFilterExpr);
    }
  }
}