package play.db;

import java.beans.PropertyVetoException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import javax.sql.DataSource;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.db.jpa.DomainDBKeyExtractor;
import play.db.jpa.RequestDBKeyExtractor;
import play.exceptions.JPAException;

import com.mchange.v2.c3p0.ComboPooledDataSource;

/**
 * The MDB plugin.  This plugin allows multiple databases to be defined for use by play and its transactions.
 */
public class MDBPlugin extends PlayPlugin
{

	public static Log log = LogFactory.getLog(MDBPlugin.class);

	private static final String MDB_ALL_KEY = "all";
	private static final String MDB_DBCONF_KEY = "db";
	private static final String MDB_CONF_PREFIX = "mdb.";
	public static final String MDB_DRIVER_PREFIX = MDB_CONF_PREFIX + "driver.";
	public static final String MDB_URL_PREFIX = MDB_CONF_PREFIX + "url.";
	public static final String MDB_USER_PREFIX = MDB_CONF_PREFIX + "user.";
	public static final String MDB_PASS_PREFIX = MDB_CONF_PREFIX + "pass.";
	public static final String MDB_POOL_TIMEOUT_PREFIX = MDB_CONF_PREFIX + "pool.timeout.";
	public static final String MDB_POOL_IDLETIME_PREFIX = MDB_CONF_PREFIX + "pool.maxIdleTime.";
	public static final String MDB_POOL_MAX_PREFIX = MDB_CONF_PREFIX + "pool.maxSize.";
	public static final String MDB_POOL_MIN_PREFIX = MDB_CONF_PREFIX + "pool.minSize.";
	public static final String MDB_KEY_PREFIX = MDB_CONF_PREFIX + "key.";
	public static final String MDB_SQLCONF_PREFIX = MDB_CONF_PREFIX + "sql.";
	public static final String MDB_URLPREFIXCONF_PREFIX = MDB_CONF_PREFIX + "urlprefix.";
	public static final String MDB_DEFAULSCHEMA = MDB_CONF_PREFIX + "defaultschema.";

	private static Map<Object, Object> datasources = null;

	/**
	 * The default key extractor, if none is defined within the application classes.
	 */
	public static RequestDBKeyExtractor keyExtractor = new DomainDBKeyExtractor();

	@Override
	public void onApplicationStart()
	{
		if (changed())
		{
			datasources = new HashMap<Object, Object>();
			MDB.credentials = new HashMap<String, DbParameters>();
			Map<String, DbParameters> dbMap = new HashMap<String, DbParameters>();
			try
			{
				dbMap = extractDbParameters();
			}
			catch (Exception e)
			{
				Logger.error(e, "Error collecting data for multiple database plugin");
			}
			
			DbParameters allEntry = dbMap.get(MDB_ALL_KEY);
			if (allEntry == null)
			{
				allEntry = new DbParameters();
			}
			
			for (Entry<String, DbParameters> parm : dbMap.entrySet())
			{

				try
				{
					//
					//	Don't connect to the 'all' entry.
					//
					if (MDB_ALL_KEY.equals(parm.getKey()))
					{
						continue;
					}
					
					//
					//	Substitute entries from the 'all' entry.
					//
					DbParameters db = parm.getValue();
					db.inherit(allEntry);
					
					//
					//	Try to connect.
					//
					makeConnection(db);
				}
				catch (Exception e)
				{
					Logger.error(e, "Cannot connect to the database [" + parm.getKey() + "]: %s", e.getMessage());
				}
			}
			// Check if we have datasource, and so create teh Routing datasource and save it in the generic DB.datasource
			if (datasources != null && datasources.size() > 0) {
				if (DB.datasource == null) {
					// Create RoutingDataSource
					RoutingDataSource routingDataSource = new RoutingDataSource();
					routingDataSource.setTargetDataSources(datasources);
//					routingDataSource.setDefaultTargetDataSource(datasources.entrySet().iterator().next());
					routingDataSource.setLenientFallback(false);
					routingDataSource.afterPropertiesSet();
					DB.datasource = routingDataSource;
				}
			}
		}

		//
		//	Set up the key extractor here, by looking for an application class that implements it.
		//
		List<Class> extractors = Play.classloader.getAssignableClasses(RequestDBKeyExtractor.class);
		if (extractors.size() > 1)
		{
			throw new JPAException("Too many DB Key extract classes.  " +
					"The Multiple DB plugin must use a single extractor class to " +
					"specify its extractor.  These classes where found: " + extractors);
		}
		else if (!extractors.isEmpty())
		{
			Class clazz = extractors.get(0);
			try
			{
				keyExtractor = (RequestDBKeyExtractor) clazz.newInstance();
			}
			catch (InstantiationException e)
			{
				log.error("Unable to instantiate extractor class:",e);
			}
			catch (IllegalAccessException e)
			{
				log.error("Invalid access to extractor class:",e);
			}
			log.debug("Using application DB key extractor class: " + keyExtractor.getClass().getName());
		}
		else
		{
			log.debug("Using default DB key extractor class: " + keyExtractor.getClass().getName());
		}
	}

	/**
	 * Extracts the database parameters from the configuration file.
	 * @param dbMap
	 * @return 
	 */
	private static Map<String, DbParameters> extractDbParameters()
	{
		Map<String, DbParameters> dbMap = new HashMap<String, DbParameters>();
		Properties p = Play.configuration;
		for (Entry<Object, Object> entry : p.entrySet())
		{
			if (entry.getKey() instanceof String)
			{
				String propKey = (String) entry.getKey();
				if (propKey == null || !propKey.startsWith(MDB_CONF_PREFIX))
				{
					continue;
				}
				
				String mapKey = StringUtils.substringAfterLast(propKey, ".");
				DbParameters mapEntry = dbMap.get(mapKey);
				if (mapEntry == null)
				{
					mapEntry = new DbParameters();
					dbMap.put(mapKey, mapEntry);
				}
				
				applyParameter((String) entry.getValue(), propKey, mapEntry);
			}
			else
			{
				Logger.warn("Unexpected non-string property key: " + entry.getKey());
			}
		}

		/* Check for the existence of 'db' entries and then overwrite the current dbMap
		 * with the data from the mdb.sql.db database query
		 */
		DbParameters allEntry = dbMap.get(MDB_ALL_KEY);
		DbParameters dbEntry = dbMap.get(MDB_DBCONF_KEY);
		if (dbEntry != null)
		{
			if (allEntry != null)
			{
				dbEntry.inherit(allEntry);
			}
			loadDataSourcesFromDatabase(dbEntry, dbMap);
		}
		return dbMap;
	}

	/* Extract the DataSource parameters from the given database query
	 * This will overwrite the current datasources (except 'all' and 'db')
	 * @param dbEntry
	 * @param dbMap
	 * @author Remco Moolenaar
	 */
	private static void loadDataSourcesFromDatabase(DbParameters dbEntry, Map<String, DbParameters> dbMap)
	{
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		ResultSet resultSet = null;
		Integer keyID = 1;
		try {
			// Create Datasource
			ComboPooledDataSource ds = makeDatasource(dbEntry);
			connection = ds.getConnection();
			// Prepare statement from the parameter mdb.sql.db 
			preparedStatement = connection.prepareStatement(
				dbEntry.sql, 
				ResultSet.TYPE_FORWARD_ONLY,
				ResultSet.CONCUR_READ_ONLY
			);
			preparedStatement.setFetchSize(50); 
			// Execute query
			resultSet = preparedStatement.executeQuery();
			// Loop through resultset and add/alter the dbMap entries for a new datasource
			while (resultSet.next()) {
				addParameters(resultSet, dbEntry, dbMap, keyID++);
			}
		} catch (Exception e) {
			Logger.error(e, "Error adding datasource from database: " + dbEntry);
		} finally {
			if (resultSet != null) {
				try {
					resultSet.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			if (connection != null) {
				try {
					connection.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/* Extract the DataSource parameters from the given database query
	 * This will overwrite the current datasources (except 'all' and 'db')
	 * @param resultset
	 * @param dbEntry
	 * @param dbMap
	 * @param keyID
	 * @author Remco Moolenaar
	 */
	private static void addParameters(ResultSet resultSet, DbParameters dbEntry, Map<String, DbParameters> dbMap, Integer keyID) throws SQLException
	{
		// Create key from current row number
		String mapKey = keyID.toString();
		// Get/create DBParameters object
		DbParameters mapEntry = dbMap.get(mapKey);
		if (mapEntry == null)
		{
			mapEntry = new DbParameters();
			dbMap.put(mapKey, mapEntry);
		}
		// Add NAME from resultset
		String name = resultSet.getString("name");
		applyParameter((String) name, MDB_KEY_PREFIX, mapEntry);
		// Add JDBC_URL from resultset
		String jdbcUrl = resultSet.getString("jdbc_url");
		applyParameter((String) dbEntry.urlPrefix + jdbcUrl, MDB_URL_PREFIX, mapEntry);
		// Add USER from resultset
		String user = resultSet.getString("username");
		applyParameter((String) user, MDB_USER_PREFIX, mapEntry);
		// Add PASSWORD from resultset
		String pass = resultSet.getString("password");
		applyParameter((String) pass, MDB_PASS_PREFIX, mapEntry);
	}

	/**
	 * @param entry
	 * @param propKey
	 * @param mapEntry
	 */
	private static void applyParameter(String propValue, String propKey,
			DbParameters mapEntry)
	{
		if (propKey.startsWith(MDB_DRIVER_PREFIX))
		{
			mapEntry.driver = propValue;
		}
		else if (propKey.startsWith(MDB_KEY_PREFIX))
		{
			mapEntry.key = propValue;
		}
		else if (propKey.startsWith(MDB_SQLCONF_PREFIX))
		{
			mapEntry.sql = propValue;
		}
		else if (propKey.startsWith(MDB_URLPREFIXCONF_PREFIX))
		{
			mapEntry.urlPrefix = propValue;
		}
		else if (propKey.startsWith(MDB_URL_PREFIX))
		{
			mapEntry.url = propValue;
		}
		else if (propKey.startsWith(MDB_USER_PREFIX))
		{
			mapEntry.user = propValue;
		}
		else if (propKey.startsWith(MDB_PASS_PREFIX))
		{
			mapEntry.pass = propValue;
		}
		else if (propKey.startsWith(MDB_POOL_TIMEOUT_PREFIX))
		{
			mapEntry.poolTimeout = propValue;
		}
		else if (propKey.startsWith(MDB_POOL_IDLETIME_PREFIX))
		{
			mapEntry.idleTime = propValue;
		}
		else if (propKey.startsWith(MDB_POOL_MAX_PREFIX))
		{
			mapEntry.poolMaxSize = propValue;
		}
		else if (propKey.startsWith(MDB_POOL_MIN_PREFIX))
		{
			mapEntry.poolMinSize = propValue;
		}
		else if (propKey.startsWith(MDB_DEFAULSCHEMA))
		{
			// Not used here, just checked......
		}
		else
		{
			Logger.warn("Unrecognized MDB key: " + propKey);
		}
	}

	@Override
	public String getStatus()
	{
		StringWriter sw = new StringWriter();
		PrintWriter out = new PrintWriter(sw);
		out.println("         Multiple DB sources:");
		out.println("=================================================");
		
		if (datasources == null || datasources.isEmpty())
		{
			out.println("Datasources:");
			out.println("~~~~~~~~~~~");
			out.println("(not yet connected)");
			return sw.toString();
		}
		
		for (Entry<Object, Object> entry : datasources.entrySet())
		{
			if (entry == null || entry.getValue() == null || !(entry.getValue() instanceof DataSource))
			{
				out.println("Datasource [" + entry.getKey() + "]:");
				out.println("~~~~~~~~~~~");
				out.println("(not yet connected)");
				continue;
			}
			DataSource datasource = (DataSource) entry.getValue();
			out.println("Datasource [" + entry.getKey() + "]:");
			out.println("~~~~~~~~~~~");
			try {
				out.println("Jdbc url: " + datasource.getConnection().getMetaData().getURL());
			} catch (SQLException e) {
				e.printStackTrace();
			}
//			out.println("Jdbc driver: " + datasource.getDriverClass());
//			out.println("Jdbc user: " + datasource.getUser());
//			out.println("Jdbc password: " + datasource.getPassword());
//			out.println("Min pool size: " + datasource.getMinPoolSize());
//			out.println("Max pool size: " + datasource.getMaxPoolSize());
//			out.println("Initial pool size: " + datasource.getInitialPoolSize());
//			out.println("Checkout timeout: " + datasource.getCheckoutTimeout());
			out.println("");
		}
		out.println("=================================================");
		return sw.toString();
	}

	/**
	 * Creates a connection using the passed database parameters.
	 * @param parms
	 * @throws Exception
	 */
	private static void makeConnection(DbParameters parms) throws Exception
	{
		// Save user schema credentials
		MDB.credentials.put(parms.key, parms);
		// Only create datasources per DATABASE and not per SCHEMA
		if (datasources.get(parms.url) == null) {
			ComboPooledDataSource ds = makeDatasource(parms);
			datasources.put(parms.url, ds);
		}
	}

	/**
	 * @param parms
	 * @return
	 * @throws Exception
	 * @throws SQLException
	 * @throws PropertyVetoException
	 */
	private static ComboPooledDataSource makeDatasource(DbParameters parms)
			throws Exception, SQLException, PropertyVetoException
	{
		// Try the driver
		String driver = parms.driver;
		try
		{
			Driver d = (Driver) Class.forName(driver, true, Play.classloader).newInstance();
			DriverManager.registerDriver(new DBPlugin.ProxyDriver(d));
		}
		catch (Exception e)
		{
			throw new Exception("Driver not found (" + driver + ")");
		}

		// Try the connection
		Connection fake = null;
		try
		{
			if (parms.user == null)
			{
				fake = DriverManager.getConnection(parms.url);
			}
			else
			{
				fake = DriverManager.getConnection(parms.url, parms.user, parms.pass);
			}
		}
		finally
		{
			if (fake != null)
			{
				fake.close();
			}
		}

//		System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
//		System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "INFO");
		ComboPooledDataSource ds = new ComboPooledDataSource();
		ds.setDriverClass(parms.driver);
		ds.setJdbcUrl(parms.url);
		ds.setUser(parms.user);
		ds.setPassword(parms.pass);
        ds.setAcquireRetryAttempts(10);
		ds.setCheckoutTimeout(Integer.parseInt(StringUtils.defaultIfEmpty(parms.poolTimeout, "5000")));
		ds.setMaxIdleTime(Integer.parseInt(StringUtils.defaultIfEmpty(parms.idleTime, "5000")));
		ds.setBreakAfterAcquireFailure(true);
		ds.setMaxPoolSize(Integer.parseInt(StringUtils.defaultIfEmpty(parms.poolMaxSize, "30")));
		ds.setMinPoolSize(Integer.parseInt(StringUtils.defaultIfEmpty(parms.poolMinSize, "1")));
        ds.setIdleConnectionTestPeriod(10);
        ds.setTestConnectionOnCheckin(true);
//        ds.setUnreturnedConnectionTimeout(20);
//        ds.setDebugUnreturnedConnectionStackTraces(true);
        return ds;
	}

	/**
	 * Determine if the datasource(s) have changed.
	 * @return
	 */
	private static boolean changed()
	{
		Map<String, DbParameters> dbMap = extractDbParameters();
		//
		//	Get the 'all' entry
		//
		DbParameters allEntry = dbMap.get(MDB_ALL_KEY);
		if (allEntry == null)
		{
			allEntry = new DbParameters();
		}
		
		boolean hasChanged = false;
		
		//
		//	Loop over all entries to see if a change has occurred.
		//
		for (Entry<String, DbParameters> parm : dbMap.entrySet())
		{
			if (MDB.credentials == null || MDB.credentials.isEmpty())
			{
				return true;
			}
			
			//
			//	Skip the 'all' entry
			//
			if (MDB_ALL_KEY.equals(parm.getKey()))
			{
				continue;
			}
			
			DbParameters db = parm.getValue();
			
			//
			//	Ignore nulls.
			//
			if (db == null || (db.driver == null) || (db.url == null))
			{
				continue;
			}
			
			DbParameters dsParam = (DbParameters) MDB.credentials.get(parm.getKey());
			if (dsParam == null)
			{
				hasChanged |= true;
			}
			else
			{
				if (!StringUtils.defaultString(db.driver).equals(dsParam.driver))
				{
					hasChanged |= true;
				}
				if (!StringUtils.defaultString(db.url).equals(dsParam.url))
				{
					hasChanged |= true;
				}
				if (!StringUtils.defaultString(db.user).equals(dsParam.user))
				{
					hasChanged |= true;
				}
			}
		}
		
		return hasChanged;
	}
	
	/**
	 * Adds a database to the application server while it's running.
	 * @param dbParms
	 */
	public static void addDatabase(Map<String, String> dbParms)
	{
		Map<String, DbParameters> dbMap = extractDbParameters();
		DbParameters allEntry = dbMap.get(MDB_ALL_KEY);
		if (allEntry == null)
		{
			allEntry = new DbParameters();
		}
		
		DbParameters dbParm = new DbParameters();
		for (Entry<String, String> entry : dbParms.entrySet())
		{
			applyParameter(entry.getValue(), entry.getKey(), dbParm);
		}
		
		//
		//	Inherit from the 'all' entry.
		//
		dbParm.inherit(allEntry);
		
		try
		{
			synchronized (MDB.credentials)
			{
				MDB.credentials.put(dbParm.key, dbParm);
			}
			Logger.info("Connected to %s", dbParm.url);
		}
		catch (Exception e)
		{
			Logger.error(e, "Error adding database: " + dbParms);
		}
	}
}
