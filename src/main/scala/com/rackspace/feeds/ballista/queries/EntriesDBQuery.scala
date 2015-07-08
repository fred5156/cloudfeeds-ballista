package com.rackspace.feeds.ballista.queries

import java.sql.Connection
import javax.sql.DataSource

import com.rackspace.feeds.ballista.config.CommandOptionsParser
import org.apache.commons.dbutils.DbUtils
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, DateTimeFormat}
import org.slf4j.LoggerFactory


class EntriesDBQuery extends DBQuery {

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd")
  val logger = LoggerFactory.getLogger(getClass)
  
  override def fetch(runDate: DateTime, region: String, dataSource: DataSource, maxRowLimit: String): String = {
    this.fetch(runDate, Set.empty, region, dataSource, maxRowLimit)
  }

  override def fetch(runDate: DateTime, tenantIds: Set[String], region: String, dataSource: DataSource, maxRowLimit: String): String = {
    val tableName = getTableName(runDate, dataSource)

    logger.debug(s"Preparing query to extract data from partitioned entries table[$tableName] for runDate[$runDate], tenantIds[$tenantIds]")
    val runDateStr = dateTimeFormatter.print(runDate)

    if (!isTableExist(tableName, dataSource)) {
      throw new RuntimeException("Partitioned table[$tableName] does not exist")
    }

    var whereClause = ""
    if ((tenantIds != null) && (tenantIds.nonEmpty)) {
      // escape each tenantId to prevent SQL injection
      val escapedTenantIds = tenantIds.map(tid => tid.replace("'", "''"))
      whereClause = "WHERE tenantid in ('" + escapedTenantIds.toArray.mkString("','") + "')"
    }

    s"""
       | COPY (SELECT id,
       |              entryid,
       |              creationdate,
       |              datelastupdated,
       |              regexp_replace(entrybody, E'[\\n\\r]+', ' ', 'g') as entrybody,
       |              array_to_string( categories, '|' ) as categories,
       |              eventtype,
       |              tenantid,
       |              '$region' as region,
       |              '$runDateStr' as date,
       |              feed
       |         FROM $tableName $whereClause
       |         LIMIT $maxRowLimit)
       |   TO STDOUT
       | WITH DELIMITER $PG_DELIMITER
       |      NULL ''  -- Specifies the string that represents a null value
     """.stripMargin
  }

  /**
   * Based on the given runDate, computes the corresponding partitioned entries 
   * table name which contains the data for the given runDate
   *  
   * @param runDate
   * @param dataSource
   * @return partitioned entries table name
   */
  protected def getTableName(runDate: DateTime, dataSource: DataSource): String = {

    val runDateStr = dateTimeFormatter.print(runDate)

    var connection:Connection  = null
  
    try {
      connection  = dataSource.getConnection
      val statement = connection.createStatement

      if (!CommandOptionsParser.isValidRunDate(runDate, DateTime.now.withTimeAtStartOfDay())) {
        logger.error(s"!!!! runDate[$runDate] is invalid. !!!")
        throw new RuntimeException(s"runDate[$runDate] is invalid.")
      }

      /**
       * entries table is partitioned based on day of the week with table name convention
       * entries_1 thru entrires_7 . ISDOW returns the day of the week as Monday(1) to Sunday(7).
       */
      val resultSet = statement.executeQuery (
        s"""
          | select 'entries_' || ltrim(to_char(EXTRACT(ISODOW 
          |   from to_timestamp('$runDateStr', 'YYYY-MM-DD')), '9'))
          | as table_name
        """.stripMargin
      )

      resultSet.next()
      resultSet.getString("table_name")
      
    } catch {
      case e: Exception => throw new RuntimeException("Error retrieving the correct entries partition table", e)
    } finally {
      DbUtils.closeQuietly(connection)
    }
  }

  /**
   * Returns true if the table is present in the underlying database
   *
   * @param tableName
   * @param dataSource
   * @return true/false indicating the presence of the table.
   */
  protected def isTableExist(tableName: String, dataSource: DataSource) = {

    var connection:Connection  = null
    var tableCount:Int = 0
    
    try {
      connection  = dataSource.getConnection
      val statement = connection.createStatement

      val resultSet = statement.executeQuery (
        s"""
          | select count(0) as table_count 
          |   from pg_tables 
          |  where schemaname = 'public' 
          |    and tablename = '$tableName'
        """.stripMargin
      )

      resultSet.next()
      tableCount = resultSet.getInt("table_count")

    } catch {
      case e: Exception => 
        throw new RuntimeException(s"Error validating the existence of partitioned entries table[$tableName]", e)
    } finally {
      DbUtils.closeQuietly(connection)
    }
    
    if (tableCount != 1) {
      false
    } else {
      true
    }
    
  }

}
