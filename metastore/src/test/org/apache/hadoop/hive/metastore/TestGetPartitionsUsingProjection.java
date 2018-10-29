/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hive.metastore;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.GetPartitionsFilterSpec;
import org.apache.hadoop.hive.metastore.api.GetPartitionsProjectionSpec;
import org.apache.hadoop.hive.metastore.api.GetPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.GetPartitionsResponse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionListComposingSpec;
import org.apache.hadoop.hive.metastore.api.PartitionSpec;
import org.apache.hadoop.hive.metastore.api.PartitionSpecWithSharedSD;
import org.apache.hadoop.hive.metastore.api.PartitionWithoutSD;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.client.builder.DatabaseBuilder;
import org.apache.hadoop.hive.metastore.client.builder.PartitionBuilder;
import org.apache.hadoop.hive.metastore.client.builder.TableBuilder;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_FORMAT;

/**
 * Tests for getPartitionsWithSpecs metastore API. This test create some partitions and makes sure
 * that getPartitionsWithSpecs returns results which are comparable with the get_partitions API when
 * various combinations of projection spec are set. Also checks the JDO code path in addition to
 * directSQL code path
 */
public class TestGetPartitionsUsingProjection {
  private static final Logger LOG = LoggerFactory.getLogger(TestGetPartitionsUsingProjection.class);
  protected static HiveConf hiveConf;
  private static int port;
  private static final String dbName = "test_projection_db";
  private static final String tblName = "test_projection_table";
  private List<Partition> origPartitions;
  private Table tbl;
  private static final String EXCLUDE_KEY_PREFIX = "exclude";
  private HiveMetaStoreClient client;

  @BeforeClass
  public static void startMetaStoreServer() throws Exception {
    HiveConf metastoreConf = new HiveConf();
    metastoreConf.set("hive.in.test", "true");
    metastoreConf.setClass(HiveConf.ConfVars.METASTORE_EXPRESSION_PROXY_CLASS.varname,
        MockPartitionExpressionForMetastore.class, PartitionExpressionProxy.class);
    LOG.info("Starting MetaStore Server on port " + port);
    port = MetaStoreUtils.startMetaStoreWithRetry(metastoreConf);

    hiveConf = new HiveConf(TestGetPartitionsUsingProjection.class);
    hiveConf.setVar(HiveConf.ConfVars.METASTOREURIS, "thrift://localhost:"
        + port);
    hiveConf.setIntVar(HiveConf.ConfVars.METASTORETHRIFTCONNECTIONRETRIES, 3);
    hiveConf.set(HiveConf.ConfVars.PREEXECHOOKS.varname, "");
    hiveConf.set(HiveConf.ConfVars.POSTEXECHOOKS.varname, "");
    hiveConf.set(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY.varname,
        "false");
    hiveConf.set(HiveConf.ConfVars.METASTORE_EXPRESSION_PROXY_CLASS.name(),
        MockPartitionExpressionForMetastore.class.getCanonicalName());
    HiveConf.setIntVar(hiveConf, ConfVars.METASTORE_BATCH_RETRIEVE_MAX, 2);
    HiveConf.setIntVar(hiveConf, ConfVars.METASTORE_LIMIT_PARTITION_REQUEST, 100);
    System.setProperty(HiveConf.ConfVars.PREEXECHOOKS.varname, " ");
    System.setProperty(HiveConf.ConfVars.POSTEXECHOOKS.varname, " ");

    try (HiveMetaStoreClient client = createClient()) {
      Database db = new DatabaseBuilder().setName(dbName).build();
      client.createDatabase(db);
    }
  }

  @AfterClass
  public static void tearDown() throws Exception {
    try (HiveMetaStoreClient client = createClient()) {
      client.dropDatabase(dbName, true, true, true);
    }
  }

  @Before
  public void setup() throws TException {
    // This is default case with setugi off for both client and server
    client = createClient();
    createTestTables();
    origPartitions = client.listPartitions(dbName, tblName, (short) -1);
    tbl = client.getTable(dbName, tblName);
    // set directSQL to true explicitly
    client.setMetaConf(ConfVars.METASTORE_TRY_DIRECT_SQL.varname, "true");
    client.setMetaConf(ConfVars.METASTORE_TRY_DIRECT_SQL_DDL.varname, "true");
  }

  @After
  public void cleanup() {
    dropTestTables();
    client.close();
    client = null;
  }

  private void dropTestTables() {
    try {
      client.dropTable(dbName, tblName);
    } catch (TException e) {
      // ignored
    }
  }

  private void createTestTables() throws TException {
    if (client.tableExists(dbName, tblName)) {
      LOG.info("Table is already existing. Dropping it and then recreating");
      client.dropTable(dbName, tblName);
    }
    Table tbl = new TableBuilder().setTableName(tblName).setDbName(dbName)
        .setCols(Arrays.asList(new FieldSchema("col1", "string", "c1 comment"),
            new FieldSchema("col2", "int", "c2 comment")))
        .setPartCols(Arrays.asList(new FieldSchema("state", "string", "state comment"),
            new FieldSchema("city", "string", "city comment")))
        .setTableParams(new HashMap<String, String>(2) {{
          put("tableparam1", "tableval1");
          put("tableparam2", "tableval2");
        }})
        .setBucketCols(Collections.singletonList("col1"))
        .addSortCol("col2", 1)
        .addSerdeParam(SERIALIZATION_FORMAT, "1").setSerdeName(tblName)
        .setSerdeLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")
        .setInputFormat("org.apache.hadoop.hive.ql.io.HiveInputFormat")
        .setOutputFormat("org.apache.hadoop.hive.ql.io.HiveOutputFormat")
        .build();
    client.createTable(tbl);

    Table table = client.getTable(dbName, tblName);
    Assert.assertTrue("Table " + dbName + "." + tblName + " does not exist",
        client.tableExists(dbName, tblName));

    List<Partition> partitions = new ArrayList<>();
    partitions.add(createPartition(Arrays.asList("CA", "SanFrancisco"), table));
    partitions.add(createPartition(Arrays.asList("CA", "PaloAlto"), table));
    partitions.add(createPartition(Arrays.asList("WA", "Seattle"), table));
    partitions.add(createPartition(Arrays.asList("AZ", "Phoenix"), table));

    client.add_partitions(partitions);
  }

  private Partition createPartition(List<String> vals, Table table) throws MetaException {
    return new PartitionBuilder()
        .setTableName(table.getTableName())
        .setDbName(table.getDbName())
        .setCols(table.getSd().getCols())
        .setValues(vals)
        .addPartParam("key1", "S1")
        .addPartParam("key2", "S2")
        .addPartParam(EXCLUDE_KEY_PREFIX + "key1", "e1")
        .addPartParam(EXCLUDE_KEY_PREFIX + "key2", "e2")
        .setBucketCols(table.getSd().getBucketCols())
        .setSortCols(table.getSd().getSortCols())
        .setSerdeName(table.getSd().getSerdeInfo().getName())
        .setSerdeLib(table.getSd().getSerdeInfo().getSerializationLib())
        .setSerdeParams(table.getSd().getSerdeInfo().getParameters())
        .build();
  }

  private static HiveMetaStoreClient createClient() throws MetaException {
    HiveConf.setVar(hiveConf, ConfVars.METASTOREURIS, "thrift://localhost:" + port);
    HiveConf.setBoolVar(hiveConf, ConfVars.METASTORE_EXECUTE_SET_UGI, false);
    return new HiveMetaStoreClient(hiveConf);
  }

  @Test
  public void testGetPartitions() throws TException {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);
    validateBasic(response);
  }

  @Test
  public void testPartitionProjectionEmptySpec() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();

    projectSpec.setFieldList(new ArrayList<>(0));
    projectSpec.setExcludeParamKeyPattern("exclude%");

    GetPartitionsResponse response;
    response = client.getPartitionsWithSpecs(request);
    Assert.assertEquals(1, response.getPartitionSpec().size());
    PartitionSpec partitionSpec = response.getPartitionSpec().get(0);
    PartitionSpecWithSharedSD partitionSpecWithSharedSD = partitionSpec.getSharedSDPartitionSpec();

    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull(sharedSD);
    // everything except location in sharedSD should be same
    StorageDescriptor origSd = origPartitions.get(0).getSd().deepCopy();
    origSd.unsetLocation();
    StorageDescriptor sharedSDCopy = sharedSD.deepCopy();
    sharedSDCopy.unsetLocation();
    Assert.assertEquals(origSd, sharedSDCopy);

    List<PartitionWithoutSD> partitionWithoutSDS = partitionSpecWithSharedSD.getPartitions();
    Assert.assertNotNull(partitionWithoutSDS);
    Assert.assertEquals("Unexpected number of partitions returned",
        origPartitions.size(), partitionWithoutSDS.size());
    for (int i = 0; i < origPartitions.size(); i++) {
      Partition origPartition = origPartitions.get(i);
      PartitionWithoutSD retPartition = partitionWithoutSDS.get(i);
      Assert.assertEquals(origPartition.getCreateTime(), retPartition.getCreateTime());
      Assert.assertEquals(origPartition.getLastAccessTime(), retPartition.getLastAccessTime());
      Assert.assertEquals(origPartition.getSd().getLocation(),
          sharedSD.getLocation() + retPartition.getRelativePath());
      validateMap(origPartition.getParameters(), retPartition.getParameters());
      validateList(origPartition.getValues(), retPartition.getValues());
    }
  }

  @Test
  public void testPartitionProjectionAllSingleValuedFields() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();

    List<String> projectedFields = Arrays
        .asList("dbName", "tableName", "createTime", "lastAccessTime", "sd.location",
            "sd.inputFormat", "sd.outputFormat", "sd.compressed", "sd.numBuckets",
            "sd.serdeInfo.name", "sd.serdeInfo.serializationLib"/*, "sd.serdeInfo.serdeType"*/);
    //TODO directSQL does not support serdeType, serializerClass and deserializerClass in serdeInfo
    projectSpec.setFieldList(projectedFields);

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);
    Assert.assertEquals(1, response.getPartitionSpec().size());
    PartitionSpec partitionSpec = response.getPartitionSpec().get(0);
    Assert.assertTrue("DbName is not set", partitionSpec.isSetDbName());
    Assert.assertTrue("tableName is not set", partitionSpec.isSetTableName());
    PartitionSpecWithSharedSD partitionSpecWithSharedSD = partitionSpec.getSharedSDPartitionSpec();

    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull(sharedSD);
    List<PartitionWithoutSD> partitionWithoutSDS = partitionSpecWithSharedSD.getPartitions();
    Assert.assertNotNull(partitionWithoutSDS);
    Assert.assertEquals(partitionWithoutSDS.size(), origPartitions.size());
    comparePartitionForSingleValuedFields(projectedFields, sharedSD, partitionWithoutSDS, 0);
  }

  @Test
  public void testProjectionUsingJDO() throws Throwable {
    // disable direct SQL to make sure
    client.setMetaConf(ConfVars.METASTORE_TRY_DIRECT_SQL.varname, "false");
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    List<String> projectedFields = Collections.singletonList("sd.location");
    projectSpec.setFieldList(projectedFields);

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);
    Assert.assertEquals(1, response.getPartitionSpec().size());
    PartitionSpec partitionSpec = response.getPartitionSpec().get(0);
    Assert.assertTrue("DbName is not set", partitionSpec.isSetDbName());
    Assert.assertTrue("tableName is not set", partitionSpec.isSetTableName());
    PartitionSpecWithSharedSD partitionSpecWithSharedSD = partitionSpec.getSharedSDPartitionSpec();

    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull(sharedSD);
    List<PartitionWithoutSD> partitionWithoutSDS = partitionSpecWithSharedSD.getPartitions();
    Assert.assertNotNull(partitionWithoutSDS);
    Assert.assertEquals(partitionWithoutSDS.size(), origPartitions.size());
    comparePartitionForSingleValuedFields(projectedFields, sharedSD, partitionWithoutSDS, 0);

    // set all the single-valued fields and try using JDO
    request = getGetPartitionsRequest();
    projectSpec = request.getProjectionSpec();
    projectedFields = Arrays
        .asList("dbName", "tableName", "createTime", "lastAccessTime", "sd.location",
            "sd.inputFormat", "sd.outputFormat", "sd.compressed", "sd.numBuckets",
            "sd.serdeInfo.name", "sd.serdeInfo.serializationLib"/*, "sd.serdeInfo.serdeType",
            "sd.serdeInfo.serializerClass", "sd.serdeInfo.deserializerClass"*/);
    //TODO: Note that the above fields in serdeInfo are disabled
    //when HIVE-17990 is backported, we should re-enable these fields to have full-test coverage
    projectSpec.setFieldList(projectedFields);

    response = client.getPartitionsWithSpecs(request);
    Assert.assertEquals(1, response.getPartitionSpec().size());
    partitionSpec = response.getPartitionSpec().get(0);
    Assert.assertTrue("DbName is not set", partitionSpec.isSetDbName());
    Assert.assertTrue("tableName is not set", partitionSpec.isSetTableName());
    partitionSpecWithSharedSD = partitionSpec.getSharedSDPartitionSpec();

    sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull(sharedSD);
    partitionWithoutSDS = partitionSpecWithSharedSD.getPartitions();
    Assert.assertNotNull(partitionWithoutSDS);
    Assert.assertEquals(partitionWithoutSDS.size(), origPartitions.size());
    comparePartitionForSingleValuedFields(projectedFields, sharedSD, partitionWithoutSDS, 0);
  }

  /**
   * Confirms if the partitionWithoutSD object at partitionWithoutSDSIndex index has all the
   * projected fields set to values which are same as the ones set in origPartitions
   * @param projectedFields
   * @param sharedSD
   * @param partitionWithoutSDS
   * @param partitionWithoutSDSIndex
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   * @throws NoSuchMethodException
   */
  private void comparePartitionForSingleValuedFields(List<String> projectedFields,
      StorageDescriptor sharedSD, List<PartitionWithoutSD> partitionWithoutSDS, int partitionWithoutSDSIndex)
      throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    for (Partition origPart : origPartitions) {
      for (String projectField : projectedFields) {
        // dbname, tableName and catName is not stored in partition
        if (projectField.equals("dbName") || projectField.equals("tableName") || projectField
            .equals("catName"))
          continue;
        if (projectField.startsWith("sd")) {
          String sdPropertyName = projectField.substring(projectField.indexOf("sd.") + 3);
          if (sdPropertyName.equals("location")) {
            // in case of location sharedSD has the base location and partition has relative location
            Assert.assertEquals("Location does not match", origPart.getSd().getLocation(),
                sharedSD.getLocation() + partitionWithoutSDS.get(partitionWithoutSDSIndex).getRelativePath());
          } else {
            Assert.assertEquals(PropertyUtils.getNestedProperty(origPart, projectField),
                PropertyUtils.getNestedProperty(sharedSD, sdPropertyName));
          }
        } else {
          Assert.assertEquals(PropertyUtils.getNestedProperty(origPart, projectField),
              PropertyUtils.getNestedProperty(partitionWithoutSDS.get(partitionWithoutSDSIndex), projectField));
        }
      }
      partitionWithoutSDSIndex++;
    }
  }

  @Test
  public void testPartitionProjectionAllMultiValuedFields() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    List<String> projectedFields = Arrays
        .asList("values", "parameters", "sd.cols", "sd.bucketCols", "sd.sortCols", "sd.parameters",
            "sd.skewedInfo", "sd.serdeInfo.parameters");
    projectSpec.setFieldList(projectedFields);

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    Assert.assertEquals(1, response.getPartitionSpec().size());
    PartitionSpec partitionSpec = response.getPartitionSpec().get(0);
    PartitionSpecWithSharedSD partitionSpecWithSharedSD = partitionSpec.getSharedSDPartitionSpec();
    Assert.assertEquals(origPartitions.size(), partitionSpecWithSharedSD.getPartitions().size());
    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    for (int i = 0; i < origPartitions.size(); i++) {
      Partition origPartition = origPartitions.get(i);
      PartitionWithoutSD retPartition = partitionSpecWithSharedSD.getPartitions().get(i);
      for (String projectedField : projectedFields) {
        switch (projectedField) {
        case "values":
          validateList(origPartition.getValues(), retPartition.getValues());
          break;
        case "parameters":
          validateMap(origPartition.getParameters(), retPartition.getParameters());
          break;
        case "sd.cols":
          validateList(origPartition.getSd().getCols(), sharedSD.getCols());
          break;
        case "sd.bucketCols":
          validateList(origPartition.getSd().getBucketCols(), sharedSD.getBucketCols());
          break;
        case "sd.sortCols":
          validateList(origPartition.getSd().getSortCols(), sharedSD.getSortCols());
          break;
        case "sd.parameters":
          validateMap(origPartition.getSd().getParameters(), sharedSD.getParameters());
          break;
        case "sd.skewedInfo":
          if (!origPartition.getSd().getSkewedInfo().getSkewedColNames().isEmpty()) {
            validateList(origPartition.getSd().getSkewedInfo().getSkewedColNames(),
                sharedSD.getSkewedInfo().getSkewedColNames());
          }
          if (!origPartition.getSd().getSkewedInfo().getSkewedColValues().isEmpty()) {
            for (int i1 = 0;
                 i1 < origPartition.getSd().getSkewedInfo().getSkewedColValuesSize(); i1++) {
              validateList(origPartition.getSd().getSkewedInfo().getSkewedColValues().get(i1),
                  sharedSD.getSkewedInfo().getSkewedColValues().get(i1));
            }
          }
          if (!origPartition.getSd().getSkewedInfo().getSkewedColValueLocationMaps().isEmpty()) {
            validateMap(origPartition.getSd().getSkewedInfo().getSkewedColValueLocationMaps(),
                sharedSD.getSkewedInfo().getSkewedColValueLocationMaps());
          }
          break;
        case "sd.serdeInfo.parameters":
          validateMap(origPartition.getSd().getSerdeInfo().getParameters(),
              sharedSD.getSerdeInfo().getParameters());
          break;
        default:
          throw new IllegalArgumentException("Invalid field " + projectedField);
        }
      }
    }
  }

  @Test
  public void testPartitionProjectionIncludeParameters() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec
        .setFieldList(Arrays.asList("dbName", "tableName", "catName", "parameters", "values"));
    projectSpec.setIncludeParamKeyPattern(EXCLUDE_KEY_PREFIX + "%");

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    Assert.assertNotNull("All the partitions should be returned in sharedSD spec",
        partitionSpecWithSharedSD);
    PartitionListComposingSpec partitionListComposingSpec =
        response.getPartitionSpec().get(0).getPartitionList();
    Assert.assertNull("Partition list composing spec should be null since all the "
        + "partitions are expected to be in sharedSD spec", partitionListComposingSpec);
    for (PartitionWithoutSD retPartion : partitionSpecWithSharedSD.getPartitions()) {
      Assert.assertTrue("included parameter key is not found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key1"));
      Assert.assertTrue("included parameter key is not found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key2"));
      Assert.assertEquals("Additional parameters returned other than inclusion keys",
          2, retPartion.getParameters().size());
    }
  }

  @Test
  public void testPartitionProjectionIncludeExcludeParameters() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec
        .setFieldList(Arrays.asList("dbName", "tableName", "catName", "parameters", "values"));
    // test parameter key inclusion using setIncludeParamKeyPattern
    projectSpec.setIncludeParamKeyPattern(EXCLUDE_KEY_PREFIX + "%");
    projectSpec.setExcludeParamKeyPattern("%key1%");

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    Assert.assertNotNull("All the partitions should be returned in sharedSD spec",
        partitionSpecWithSharedSD);
    PartitionListComposingSpec partitionListComposingSpec =
        response.getPartitionSpec().get(0).getPartitionList();
    Assert.assertNull("Partition list composing spec should be null since all the "
        + "partitions are expected to be in sharedSD spec", partitionListComposingSpec);
    for (PartitionWithoutSD retPartion : partitionSpecWithSharedSD.getPartitions()) {
      Assert.assertFalse("excluded parameter key is found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key1"));
      Assert.assertTrue("included parameter key is not found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key2"));
      Assert.assertEquals("Additional parameters returned other than inclusion keys",
          1, retPartion.getParameters().size());
    }
  }

  @Test
  public void testPartitionProjectionExcludeParameters() throws Throwable {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec
        .setFieldList(Arrays.asList("dbName", "tableName", "catName", "parameters", "values"));
    projectSpec.setExcludeParamKeyPattern(EXCLUDE_KEY_PREFIX + "%");

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    Assert.assertNotNull("All the partitions should be returned in sharedSD spec",
        partitionSpecWithSharedSD);
    PartitionListComposingSpec partitionListComposingSpec =
        response.getPartitionSpec().get(0).getPartitionList();
    Assert.assertNull("Partition list composing spec should be null", partitionListComposingSpec);
    for (PartitionWithoutSD retPartion : partitionSpecWithSharedSD.getPartitions()) {
      Assert.assertFalse("excluded parameter key is found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key1"));
      Assert.assertFalse("excluded parameter key is found in the response",
          retPartion.getParameters().containsKey(EXCLUDE_KEY_PREFIX + "key2"));
    }
  }

  @Test
  public void testNestedMultiValuedFieldProjection() throws TException {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec.setFieldList(Arrays.asList("sd.cols.name", "sd.cols.type"));

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull("sd.cols were requested but was not returned", sharedSD.getCols());
    for (FieldSchema col : sharedSD.getCols()) {
      Assert.assertTrue("sd.cols.name was requested but was not returned", col.isSetName());
      Assert.assertTrue("sd.cols.type was requested but was not returned", col.isSetType());
      Assert.assertFalse("sd.cols.comment was not requested but was returned", col.isSetComment());
    }
  }

  @Test
  public void testParameterExpansion() throws TException {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec.setFieldList(Arrays.asList("sd.cols", "sd.serdeInfo"));

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertNotNull("sd.cols were requested but was not returned", sharedSD.getCols());
    Assert.assertEquals("Returned serdeInfo does not match with original serdeInfo",
        origPartitions.get(0).getSd().getCols(), sharedSD.getCols());

    Assert
        .assertNotNull("sd.serdeInfo were requested but was not returned", sharedSD.getSerdeInfo());
    Assert.assertEquals("Returned serdeInfo does not match with original serdeInfo",
        origPartitions.get(0).getSd().getSerdeInfo(), sharedSD.getSerdeInfo());
  }

  @Test
  public void testNonStandardPartitions() throws TException {
    String testTblName = "test_non_standard";
    Table tbl = new TableBuilder()
        .setTableName(testTblName)
        .setDbName(dbName)
        .addCol("ns_c1", "string", "comment 1")
        .addCol("ns_c2", "int", "comment 2")
        .addPartCol("part", "string")
        .addPartCol("city", "string")
        .addBucketCol("ns_c1")
        .addSortCol("ns_c2", 1)
        .addTableParam("tblparamKey", "Partitions of this table are not located within table directory")
        .build();

    client.createTable(tbl);

    Table table = client.getTable(dbName, testTblName);
    Assert.assertNotNull("Unable to create a test table ", table);

    List<Partition> partitions = new ArrayList<>();
    partitions.add(createPartition(Arrays.asList("p1", "SanFrancisco"), table));
    partitions.add(createPartition(Arrays.asList("p1", "PaloAlto"), table));
    partitions.add(createPartition(Arrays.asList("p2", "Seattle"), table));
    partitions.add(createPartition(Arrays.asList("p2", "Phoenix"), table));

    client.add_partitions(partitions);
    // change locations of two of the partitions outside table directory
    List<Partition> testPartitions = client.listPartitions(dbName, testTblName, (short) -1);
    Assert.assertEquals(4, testPartitions.size());
    Partition p1 = testPartitions.get(2);
    p1.getSd().setLocation("/tmp/some_other_location/part=p2/city=Seattle");
    Partition p2 = testPartitions.get(3);
    p2.getSd().setLocation("/tmp/some_other_location/part=p2/city=Phoenix");
    client.alter_partitions(dbName, testTblName, Arrays.asList(p1, p2));

    GetPartitionsRequest request = getGetPartitionsRequest();
    request.getProjectionSpec().setFieldList(Arrays.asList("values", "sd"));
    request.setDbName(dbName);
    request.setTblName(testTblName);

    GetPartitionsResponse response = client.getPartitionsWithSpecs(request);
    Assert.assertNotNull("Response should have returned partition specs",
        response.getPartitionSpec());
    Assert
        .assertEquals("We should have two partition specs", 2, response.getPartitionSpec().size());
    Assert.assertNotNull("One SharedSD spec is expected",
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec());
    Assert.assertNotNull("One composing spec is expected",
        response.getPartitionSpec().get(1).getPartitionList());

    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    Assert.assertNotNull("sd was requested but not returned", partitionSpecWithSharedSD.getSd());
    Assert.assertEquals("shared SD should have table location", table.getSd().getLocation(),
        partitionSpecWithSharedSD.getSd().getLocation());
    List<List<String>> expectedVals = new ArrayList<>(2);
    expectedVals.add(Arrays.asList("p1", "PaloAlto"));
    expectedVals.add(Arrays.asList("p1", "SanFrancisco"));

    for (int i=0; i<partitionSpecWithSharedSD.getPartitions().size(); i++) {
      PartitionWithoutSD retPartition = partitionSpecWithSharedSD.getPartitions().get(i);
      Assert.assertEquals(2, retPartition.getValuesSize());
      validateList(expectedVals.get(i), retPartition.getValues());
      Assert.assertNull("parameters were not requested so should have been null",
          retPartition.getParameters());
    }

    PartitionListComposingSpec composingSpec =
        response.getPartitionSpec().get(1).getPartitionList();
    Assert.assertNotNull("composing spec should have returned 2 partitions",
        composingSpec.getPartitions());
    Assert.assertEquals("composing spec should have returned 2 partitions", 2,
        composingSpec.getPartitionsSize());

    expectedVals.clear();
    expectedVals.add(Arrays.asList("p2", "Phoenix"));
    expectedVals.add(Arrays.asList("p2", "Seattle"));
    for (int i=0; i<composingSpec.getPartitions().size(); i++) {
      Partition partition = composingSpec.getPartitions().get(i);
      Assert.assertEquals(2, partition.getValuesSize());
      validateList(expectedVals.get(i), partition.getValues());
      Assert.assertNull("parameters were not requested so should have been null",
          partition.getParameters());
    }
  }

  @Test(expected = TException.class)
  public void testInvalidProjectFieldNames() throws TException {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec.setFieldList(Arrays.asList("values", "invalid.field.name"));
    client.getPartitionsWithSpecs(request);
  }

  @Test(expected = TException.class)
  public void testInvalidProjectFieldNames2() throws TException {
    GetPartitionsRequest request = getGetPartitionsRequest();
    GetPartitionsProjectionSpec projectSpec = request.getProjectionSpec();
    projectSpec.setFieldList(Arrays.asList(""));
    client.getPartitionsWithSpecs(request);
  }

  private void validateBasic(GetPartitionsResponse response) throws TException {
    Assert.assertNotNull("Response is null", response);
    Assert.assertNotNull("Returned partition spec is null", response.getPartitionSpec());
    Assert.assertEquals(1, response.getPartitionSpecSize());
    PartitionSpecWithSharedSD partitionSpecWithSharedSD =
        response.getPartitionSpec().get(0).getSharedSDPartitionSpec();
    Assert.assertNotNull(partitionSpecWithSharedSD.getSd());
    StorageDescriptor sharedSD = partitionSpecWithSharedSD.getSd();
    Assert.assertEquals("Root location should be set to table location", tbl.getSd().getLocation(),
        sharedSD.getLocation());

    List<PartitionWithoutSD> partitionWithoutSDS = partitionSpecWithSharedSD.getPartitions();
    Assert.assertEquals(origPartitions.size(), partitionWithoutSDS.size());
    for (int i = 0; i < origPartitions.size(); i++) {
      Partition origPartition = origPartitions.get(i);
      PartitionWithoutSD returnedPartitionWithoutSD = partitionWithoutSDS.get(i);
      Assert.assertEquals(String.format("Location returned for Partition %d is not correct", i),
          origPartition.getSd().getLocation(),
          sharedSD.getLocation() + returnedPartitionWithoutSD.getRelativePath());
    }
  }

  private GetPartitionsRequest getGetPartitionsRequest() {
    GetPartitionsRequest request = new GetPartitionsRequest();
    request.setProjectionSpec(new GetPartitionsProjectionSpec());
    request.setFilterSpec(new GetPartitionsFilterSpec());
    request.setTblName(tblName);
    request.setDbName(dbName);
    return request;
  }

  private <K, V> void validateMap(Map<K, V> aMap, Map<K, V> bMap) {
    if ((aMap == null || aMap.isEmpty()) && (bMap == null || bMap.isEmpty())) {
      return;
    }
    // Equality is verified here because metastore updates stats automatically
    // and adds them in the returned partition. So the returned partition will
    // have parameters + some more parameters for the basic stats
    Assert.assertTrue(bMap.size() >= aMap.size());
    for (Entry<K, V> entries : aMap.entrySet()) {
      Assert.assertTrue("Expected " + entries.getKey() + " is missing from the map",
          bMap.containsKey(entries.getKey()));
      Assert.assertEquals("Expected value to be " + aMap.get(entries.getKey()) + " found" + bMap
          .get(entries.getKey()), aMap.get(entries.getKey()), bMap.get(entries.getKey()));
    }
  }

  private <T> void validateList(List<T> aList, List<T> bList) {
    if ((aList == null || aList.isEmpty()) && (bList == null || bList.isEmpty())) {
      return;
    }
    Assert.assertEquals(aList.size(), bList.size());
    Iterator<T> origValuesIt = aList.iterator();
    Iterator<T> retValuesIt = bList.iterator();
    while (origValuesIt.hasNext()) {
      Assert.assertTrue(retValuesIt.hasNext());
      Assert.assertEquals(origValuesIt.next(), retValuesIt.next());
    }
  }
}
